/**
 * Pinning — the part every share kind has in common (docs/device-preview-share-design.md §14).
 *
 * A viewer points at one spot of the shared screen. The server asks the device what is there
 * (PIN_PROBE), the device answers with one description of the same shape whatever the platform
 * (PIN_RESULT): the screen, the element and its bounds, a few ancestors, component names, source
 * candidates in the owner's project, a crop around the element and the whole screen.
 *
 * Reading is all a probe does. Nothing here clicks, types or runs anything the viewer sent; the
 * viewer only chooses the point.
 */
import { terminalSafe } from '../render.js';

/** §14.3. The server drops anything over these, so the device keeps under them. */
export const MAX_RESULT_JSON_BYTES = 16 * 1024;
export const MAX_CROP_BYTES = 512 * 1024;
export const MAX_SCREEN_BYTES = 1024 * 1024;

export const LIMIT = Object.freeze({ text: 200, selector: 300, path: 300, label: 512, ancestors: 5, components: 8, hints: 5 });

/**
 * The server answers a viewer with `timeout` 5 s after it asked. A device answer later than that
 * is thrown away, so the device gives up a little earlier and sends what it has.
 */
export const PIN_DEADLINE_MS = 4000;

const PROBE_ID = /^p[0-9]{1,9}$/;

/** @returns {{probeId: string, x: number, y: number}|null} null when the server sent something odd */
export function parseProbe(payload) {
  if (!payload || typeof payload !== 'object') return null;
  const { probeId, x, y } = payload;
  if (typeof probeId !== 'string' || !PROBE_ID.test(probeId)) return null;
  if (!isRatio(x) || !isRatio(y)) return null;
  return { probeId, x, y };
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

/** The answer when nothing could be read; the point and the screen still go (§14.1). */
export function unresolved(probe, reason, screen = null) {
  return { probeId: probe.probeId, ok: false, reason, point: { x: r4(probe.x), y: r4(probe.y) }, ...(screen ? { screen } : {}) };
}

/**
 * Keeps the description under 16 KiB by letting go of the least useful parts first.
 *
 * @returns {string} JSON
 */
export function fitResult(result) {
  let json = JSON.stringify(result);
  if (Buffer.byteLength(json) <= MAX_RESULT_JSON_BYTES) return json;
  const slim = structuredClone(result);
  const steps = [
    () => delete slim.ancestors,
    () => (slim.sourceHints = slim.sourceHints?.slice(0, 2)),
    () => (slim.components = slim.components?.slice(0, 3)),
    () => delete slim.sourceHints,
    () => slim.element && (slim.element = { type: slim.element.type, bounds: slim.element.bounds }),
    () => delete slim.components,
    () => slim.screen && (slim.screen = { label: clip(slim.screen.label, 100) }),
  ];
  for (const step of steps) {
    step();
    json = JSON.stringify(slim);
    if (Buffer.byteLength(json) <= MAX_RESULT_JSON_BYTES) return json;
  }
  return JSON.stringify(unresolved({ probeId: result.probeId, x: result.point?.x ?? 0, y: result.point?.y ?? 0 }, 'NO_ELEMENT'));
}

const isJpeg = (b) => Buffer.isBuffer(b) && b.length >= 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff;

/**
 * `[json length u32 BE][json][crop length u32 BE][crop JPEG][screen JPEG]` (§14.3). An image that is
 * missing, not a JPEG or over its limit goes as zero bytes.
 */
export function encodePinResult(result, crop, screen) {
  const json = Buffer.from(fitResult(result), 'utf8');
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
