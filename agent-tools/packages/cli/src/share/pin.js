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
 * screen instead of a point.
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
});

/**
 * The server answers a viewer with `timeout` 5 s after it asked. A device answer later than that
 * is thrown away, so the device gives up a little earlier and sends what it has.
 */
export const PIN_DEADLINE_MS = 4000;

const PROBE_ID = /^p[0-9]{1,9}$/;

/**
 * A point (`{probeId, x, y}`, §14.3) or the whole screen (`{probeId, screen: true}`, §15.6).
 *
 * A shape this CLI does not read yet — a dragged `rect` (§15.4), or a screen probe that also names a
 * point — is dropped rather than guessed at: the server only sends what HELLO announced.
 *
 * @returns {{probeId: string, x: number, y: number}|{probeId: string, screen: true}|null} null when
 *   the server sent something odd
 */
export function parseProbe(payload) {
  if (!payload || typeof payload !== 'object') return null;
  const { probeId, x, y } = payload;
  if (typeof probeId !== 'string' || !PROBE_ID.test(probeId)) return null;
  if (payload.rect !== undefined) return null;
  if (payload.screen === true) return x === undefined && y === undefined ? { probeId, screen: true } : null;
  if (!isRatio(x) || !isRatio(y)) return null;
  return { probeId, x, y };
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

/** The answer when nothing could be read; the point (if there was one) and the screen still go (§14.1). */
export function unresolved(probe, reason, screen = null) {
  const point = isRatio(probe.x) && isRatio(probe.y) ? { point: { x: r4(probe.x), y: r4(probe.y) } } : {};
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
 * The scope ladder goes first, from its widest step inwards (§15.9): an outer step is the cheapest
 * thing to lose, since the viewer can still choose the element or the whole screen. The element
 * step and the screen step stay, and so does `element`, through everything but the last resort.
 *
 * @returns {string} JSON
 */
export function fitResult(result, max = MAX_RESULT_JSON_BYTES) {
  const limit = pinResultLimit(max);
  let json = JSON.stringify(result);
  if (Buffer.byteLength(json) <= limit) return json;
  const slim = structuredClone(result);
  const steps = [];
  // Narrow to wide, the screen last: the step before the screen is the widest one left.
  const outer = Array.isArray(slim.scopes) ? slim.scopes.length - 2 : 0;
  for (let i = 0; i < outer; i++) {
    steps.push(() => {
      slim.scopes.splice(slim.scopes.length - 2, 1);
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
      const first = Array.isArray(slim.scopes) ? slim.scopes[0] : null;
      if (first?.kind === 'element') slim.scopes[0] = { kind: first.kind, type: first.type, bounds: first.bounds };
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
