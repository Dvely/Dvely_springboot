/**
 * Pinning — the part every share kind has in common (docs/device-preview-share-design.md §14).
 *
 * A viewer points at one spot of the shared screen. The server asks the device what is there
 * (PIN_PROBE), the device answers with one description of the same shape whatever the platform
 * (PIN_RESULT): the screen, the element and its bounds, a few ancestors, component names, source
 * candidates in the owner's project, a crop around the element and the whole screen.
 *
 * Since §15 a point's answer also carries a scope ladder (`scopes`, narrow to wide, the screen
 * last) so the viewer can widen the pin without asking again, and a probe can ask for the whole
 * screen or a dragged rectangle (§15.4) instead of a point.
 *
 * Reading is all a probe does. Nothing here clicks, types or runs anything the viewer sent; the
 * viewer only chooses the point.
 */
import { terminalSafe } from '../render.js';

/**
 * §14.3. The server drops anything over these, so the device keeps under them.
 *
 * The result JSON's limit is the server's to say (§15.9): a server that takes scope ladders sends
 * `pinResultMax` in WELCOME, up to 48 KiB, and an older one says nothing and keeps 16 KiB.
 */
export const MAX_RESULT_JSON_BYTES = 16 * 1024;
export const MAX_RESULT_JSON_BYTES_SCOPED = 48 * 1024;
export const MAX_CROP_BYTES = 512 * 1024;
export const MAX_SCREEN_BYTES = 1024 * 1024;

export const LIMIT = Object.freeze({
  text: 200,
  selector: 300,
  path: 300,
  label: 512,
  ancestors: 5,
  components: 8,
  hints: 5,
  /** §15.3: a ladder has at most this many steps, the screen included, and each says this much */
  scopes: 6,
  scopeText: 60,
  /** §15.4: a dragged area lists this many elements inside it, each saying this much */
  members: 12,
  memberText: 60,
  /** §15.4: an empty spot names at most one neighbour per side */
  near: 4,
});

/**
 * The server answers a viewer with `timeout` 5 s after it asked. A device answer later than that
 * is thrown away, so the device gives up a little earlier and sends what it has.
 */
export const PIN_DEADLINE_MS = 4000;

const PROBE_ID = /^p[0-9]{1,9}$/;

/**
 * A point (`{probeId, x, y}`, §14.3), the whole screen (`{probeId, screen: true}`, §15.6) or a dragged
 * rectangle (`{probeId, rect: {x, y, w, h}}`, §15.4), all in screen ratios.
 *
 * Anything else — a rectangle that also names a point or the screen, one with no area or reaching
 * past the screen — is dropped rather than guessed at: the server only sends what HELLO announced,
 * so an odd shape is a bug or someone else talking.
 *
 * @returns {{probeId: string, x: number, y: number}|{probeId: string, screen: true}|
 *   {probeId: string, rect: {x: number, y: number, w: number, h: number}}|null} null when the server
 *   sent something odd
 */
export function parseProbe(payload) {
  if (!payload || typeof payload !== 'object') return null;
  const { probeId, x, y } = payload;
  if (typeof probeId !== 'string' || !PROBE_ID.test(probeId)) return null;
  if (payload.rect !== undefined) {
    if (x !== undefined || y !== undefined || payload.screen === true) return null;
    const rect = parseRect(payload.rect);
    return rect ? { probeId, rect } : null;
  }
  if (payload.screen === true) return x === undefined && y === undefined ? { probeId, screen: true } : null;
  if (!isRatio(x) || !isRatio(y)) return null;
  return { probeId, x, y };
}

/** A rectangle inside the screen with some area: 0 ≤ x, y; w, h > 0; x + w ≤ 1; y + h ≤ 1. */
function parseRect(r) {
  if (!r || typeof r !== 'object' || Array.isArray(r)) return null;
  const { x, y, w, h } = r;
  if (![x, y, w, h].every((v) => typeof v === 'number' && Number.isFinite(v))) return null;
  if (x < 0 || y < 0 || !(w > 0) || !(h > 0) || x + w > 1 || y + h > 1) return null;
  return { x, y, w, h };
}

/** The point a probe stands for: its own, or the centre of its rectangle (§15.4). */
export function probePoint(probe) {
  if (probe?.rect) return { x: probe.rect.x + probe.rect.w / 2, y: probe.rect.y + probe.rect.h / 2 };
  return { x: probe?.x, y: probe?.y };
}

/**
 * The result JSON limit from WELCOME's `pinResultMax` (§15.9). Anything but a whole number between
 * the old and the new limit is a server this CLI does not understand, and gets the old limit.
 */
export function pinResultLimit(value) {
  return Number.isInteger(value) && value >= MAX_RESULT_JSON_BYTES && value <= MAX_RESULT_JSON_BYTES_SCOPED ? value : MAX_RESULT_JSON_BYTES;
}

const isRatio = (v) => typeof v === 'number' && Number.isFinite(v) && v >= 0 && v <= 1;

/** Whitespace folded, trimmed, cut to `max` code points. Empty → null. */
export function clip(value, max = LIMIT.text) {
  if (value === null || value === undefined) return null;
  const text = String(value).replace(/\s+/g, ' ').trim();
  if (!text) return null;
  const points = Array.from(text);
  return points.length > max ? points.slice(0, max).join('') : text;
}

/** A 0..1 ratio rounded to four places (what the viewer draws with). */
export const r4 = (v) => Math.round(Math.min(Math.max(v, 0), 1) * 10000) / 10000;

/**
 * The answer when nothing could be read; the point (if there was one) and the screen still go (§14.1).
 * A rectangle's point is its centre, as in its answer when it is read (§15.4).
 */
export function unresolved(probe, reason, screen = null) {
  const at = probePoint(probe);
  const point = isRatio(at.x) && isRatio(at.y) ? { point: { x: r4(at.x), y: r4(at.y) } } : {};
  return { probeId: probe.probeId, ok: false, reason, ...point, ...(screen ? { screen } : {}) };
}

/** The last rung of every scope ladder: the whole screen (§15.3). A fresh object each time. */
export const screenScope = () => ({ kind: 'screen', type: 'screen', bounds: { x: 0, y: 0, w: 1, h: 1 } });

/**
 * The answer to a whole-screen probe (§15.6): where the screen is and the screen itself, no element.
 * The ladder is the screen alone, so whoever reads `scopes` sees what was chosen.
 */
export function screenResult(probe, screen) {
  return { probeId: probe.probeId, ok: true, ...(screen ? { screen } : {}), scopes: [screenScope()], defaultScope: 0 };
}

/**
 * Keeps the description under `max` bytes (16 KiB, or what the server allowed in WELCOME) by letting
 * go of the least useful parts first.
 *
 * A dragged area's extras go first (§15.9): the words of the elements inside it (the crop shows
 * them), then the neighbours of an empty spot, then the list itself — its count stays on the region
 * step. Then the scope ladder, from its widest step inwards: an outer step is the cheapest thing to
 * lose, since the viewer can still choose the first step or the whole screen. The first step (the
 * element, or the region) and the screen step stay, and so does `element`, through everything but
 * the last resort.
 *
 * @returns {string} JSON
 */
export function fitResult(result, max = MAX_RESULT_JSON_BYTES) {
  const limit = pinResultLimit(max);
  let json = JSON.stringify(result);
  if (Buffer.byteLength(json) <= limit) return json;
  const slim = structuredClone(result);
  const steps = [];
  // §15.9: the look-alikes' boxes go first; their count stays on the step.
  if (Array.isArray(slim.scopes) && slim.scopes.some((sc) => sc?.peers?.bounds?.length)) {
    steps.push(() => slim.scopes.forEach((sc) => sc?.peers && (sc.peers = { count: sc.peers.count, bounds: [] })));
  }
  if (Array.isArray(slim.members) && slim.members.some((m) => m?.text !== undefined)) {
    steps.push(() => (slim.members = slim.members.map((m) => (m && typeof m === 'object' ? withoutText(m) : m))));
  }
  if (slim.near !== undefined) steps.push(() => delete slim.near);
  if (slim.members !== undefined) steps.push(() => delete slim.members);
  // The containers, widest first. The element, a sentence (§15.7), a dragged region and the screen stay.
  const container = (sc) => sc?.kind === 'group' || sc?.kind === 'component';
  const outer = Array.isArray(slim.scopes) ? slim.scopes.filter(container).length : 0;
  for (let i = 0; i < outer; i++) {
    steps.push(() => {
      let at = -1;
      slim.scopes.forEach((sc, j) => container(sc) && (at = j));
      if (at < 0) return;
      slim.scopes.splice(at, 1);
      if (at < slim.defaultScope) slim.defaultScope -= 1;
      if (!(slim.defaultScope < slim.scopes.length)) slim.defaultScope = 0;
    });
  }
  steps.push(
    () => delete slim.ancestors,
    () => (slim.sourceHints = slim.sourceHints?.slice(0, 2)),
    () => (slim.components = slim.components?.slice(0, 3)),
    () => delete slim.sourceHints,
    () => {
      if (slim.element) slim.element = { type: slim.element.type, bounds: slim.element.bounds };
      // The element step is the first, or the second after a sentence step (§15.7).
      const at = Array.isArray(slim.scopes) ? slim.scopes.findIndex((sc) => sc?.kind === 'element') : -1;
      const first = at >= 0 ? slim.scopes[at] : null;
      if (first) slim.scopes[at] = { kind: first.kind, type: first.type, bounds: first.bounds };
    },
    () => delete slim.components,
    () => slim.screen && (slim.screen = { label: clip(slim.screen.label, 100) })
  );
  for (const step of steps) {
    step();
    json = JSON.stringify(slim);
    if (Buffer.byteLength(json) <= limit) return json;
  }
  return JSON.stringify(unresolved({ probeId: result.probeId, x: result.point?.x, y: result.point?.y }, 'NO_ELEMENT'));
}

function withoutText(member) {
  const { text, ...rest } = member;
  return rest;
}

const isJpeg = (b) => Buffer.isBuffer(b) && b.length >= 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff;

/**
 * `[json length u32 BE][json][crop length u32 BE][crop JPEG][screen JPEG]` (§14.3). An image that is
 * missing, not a JPEG or over its limit goes as zero bytes.
 *
 * @param {number} [max] the result JSON limit the server allowed (pinResultLimit)
 */
export function encodePinResult(result, crop, screen, max = MAX_RESULT_JSON_BYTES) {
  const json = Buffer.from(fitResult(result, max), 'utf8');
  const cropBytes = isJpeg(crop) && crop.length <= MAX_CROP_BYTES ? crop : Buffer.alloc(0);
  const screenBytes = isJpeg(screen) && screen.length <= MAX_SCREEN_BYTES ? screen : Buffer.alloc(0);
  const head = Buffer.alloc(4);
  head.writeUInt32BE(json.length);
  const mid = Buffer.alloc(4);
  mid.writeUInt32BE(cropBytes.length);
  return Buffer.concat([head, json, mid, cropBytes, screenBytes]);
}

/** Rejects after `ms`, so one stuck read cannot hold the probe queue. */
export function withDeadline(promise, ms) {
  let timer;
  return Promise.race([
    promise,
    new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error('deadline')), ms);
    }),
  ]).finally(() => clearTimeout(timer));
}

// ── feedback notices (§14.4) ───────────────────────────────────────────────

/** @returns {{feedbackId: number, guest: boolean, name: string, label: string, comment: string, state: string}|null} */
export function parseNotice(payload) {
  if (!payload || typeof payload !== 'object') return null;
  const id = payload.feedbackId;
  if (!Number.isSafeInteger(id) || id <= 0) return null;
  const state = typeof payload.state === 'string' ? payload.state : '';
  const author = payload.author && typeof payload.author === 'object' ? payload.author : {};
  return {
    feedbackId: id,
    guest: author.role !== 'OWNER',
    name: oneLine(author.name, 20) || '이름 없는 사람',
    label: oneLine(payload.label, 80) || '짚은 자리',
    comment: oneLine(payload.comment, 120),
    state,
  };
}

/**
 * Everything here was typed by a viewer, so it is made safe for the owner's terminal: no escape
 * sequences, no control characters, one line.
 */
function oneLine(value, max) {
  return clip(terminalSafe(String(value ?? '')), max) ?? '';
}

export function noticeLine(n) {
  const who = n.guest ? `손님 '${n.name}'` : '내가 남김';
  const comment = n.comment ? ` · "${n.comment}"` : '';
  return `피드백 #${n.feedbackId} · ${who} · ${n.label}${comment}`;
}
