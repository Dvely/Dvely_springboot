/**
 * The rules of `qeploy share`, kept pure so each one is testable on its own: what may be shared,
 * where the shared tab may go, how fast frames flow, and how long to wait before reconnecting.
 */
import { UsageError } from '../confirm.js';
import { PROTOCOL_VERSION } from './protocol.js';

const LOOPBACK = new Set(['localhost', '127.0.0.1', '[::1]']);
const isLoopback = (hostname) => LOOPBACK.has(hostname) || hostname.endsWith('.localhost');

/**
 * The page to share. http(s) only: `file://` would hand a remote controller the owner's disk, and
 * other schemes (chrome://, data:) are not apps.
 *
 * @returns {URL}
 */
export function parseTargetUrl(raw) {
  const text = String(raw ?? '').trim();
  if (!text) {
    throw new UsageError('공유할 주소가 필요합니다. 예: qeploy share web http://localhost:5173');
  }
  let url;
  try {
    url = new URL(text);
  } catch {
    throw new UsageError(`올바른 주소가 아닙니다: ${text} (예: http://localhost:5173)`);
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new UsageError(`http 또는 https 주소만 공유할 수 있습니다: ${text}`);
  }
  if (url.username || url.password) {
    // The label is shown to everyone who opens the link and stored on the server.
    throw new UsageError('주소에 아이디·비밀번호를 넣을 수 없습니다.');
  }
  return url;
}

/** `390x844` → a mobile-sized viewport. Narrower than 600 px is treated as a phone. */
export function parseViewport(raw) {
  const m = /^(\d{2,4})x(\d{2,4})$/i.exec(String(raw ?? '').trim());
  if (!m) throw new UsageError(`--viewport 는 너비x높이 형식이어야 합니다 (예: 390x844): ${raw}`);
  const width = Number(m[1]);
  const height = Number(m[2]);
  if (width < 200 || height < 200 || width > 3840 || height > 3840) {
    throw new UsageError('--viewport 의 너비·높이는 200~3840 사이여야 합니다.');
  }
  return { width, height, mobile: width < 600 };
}

/** §6.2: ttlMinutes is 1..1440 (24 hours). */
export function parseTtl(raw) {
  const text = String(raw ?? '').trim();
  const n = Number(text);
  if (!/^\d+$/.test(text) || !Number.isSafeInteger(n) || n < 1 || n > 1440) {
    throw new UsageError(`--ttl 은 1~1440 사이의 분이어야 합니다: ${raw}`);
  }
  return n;
}

/**
 * Where the PAT is about to be sent, as the server handed it out.
 *
 * Same rule as the API address (#482): plain ws:// only on loopback, so the token never crosses a
 * network in the clear. The protocol version is added when the server left it out (§4.1).
 */
export function checkTunnelUrl(raw) {
  let url;
  try {
    url = new URL(String(raw ?? ''));
  } catch {
    throw new Error(`서버가 준 터널 주소가 올바르지 않습니다: ${raw}`);
  }
  if (url.protocol !== 'wss:' && !(url.protocol === 'ws:' && isLoopback(url.hostname))) {
    throw new Error(`wss 가 아닌 터널 주소로는 토큰을 보내지 않습니다: ${url.origin}`);
  }
  if (!url.searchParams.has('v')) url.searchParams.set('v', PROTOCOL_VERSION);
  return url.toString();
}

/**
 * §8: the shared tab stays on the app. A top-level document may load only from the shared URL's
 * origin — not another port on localhost (the owner's other dev services), not the LAN, not
 * file://. `about:blank` is the empty tab Chrome starts with.
 *
 * @param {string} allowedOrigin e.g. "http://localhost:5173"
 * @param {string} url the document about to load
 */
export function isAllowedNavigation(allowedOrigin, url) {
  if (typeof url !== 'string') return false;
  if (url === 'about:blank' || url.startsWith('about:blank#') || url.startsWith('about:blank?')) return true;
  let parsed;
  try {
    parsed = new URL(url);
  } catch {
    return false;
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return false;
  return parsed.origin === allowedOrigin;
}

/** For messages: the origin only, since a full URL can carry tokens in its query. */
export function describeOrigin(url) {
  try {
    const parsed = new URL(url);
    return parsed.origin !== 'null' ? parsed.origin : `${parsed.protocol}//`;
  } catch {
    return '(알 수 없는 주소)';
  }
}

const BACKOFF_SECONDS = [1, 2, 4, 8, 16, 30];

/** §7: 1, 2, 4, 8, 16, then 30 seconds for every later attempt. `attempt` starts at 0. */
export function backoffDelay(attempt) {
  const i = Number.isInteger(attempt) && attempt > 0 ? Math.min(attempt, BACKOFF_SECONDS.length - 1) : 0;
  return BACKOFF_SECONDS[i] * 1000;
}

export const MAX_FPS = 15;
export const ACK_BUFFER_LIMIT = 1024 * 1024;
const BUFFER_POLL_MS = 50;

/**
 * §4.4 flow control. Chrome sends the next screencast frame only after the previous one is
 * acknowledged, so holding the ack is how the CLI slows the screen down:
 *   - while the tunnel's send buffer holds a MiB or more (the server or network is behind),
 *   - and to at most `maxFps` frames a second.
 *
 * @returns {number} milliseconds to wait before acknowledging; 0 means now
 */
export function ackDelay({
  now,
  lastAckAt,
  bufferedAmount,
  maxFps = MAX_FPS,
  maxBuffered = ACK_BUFFER_LIMIT,
  pollMs = BUFFER_POLL_MS,
}) {
  if (bufferedAmount >= maxBuffered) return pollMs;
  const interval = 1000 / maxFps;
  return Math.max(0, Math.ceil(lastAckAt + interval - now));
}

/**
 * The history entry `delta` steps from the current one, for back (-1) and forward (+1).
 *
 * @param {{currentIndex: number, entries: {id: number, url: string}[]}} history
 * @returns {{id: number, url: string}|null}
 */
export function historyEntry(history, delta) {
  const entries = Array.isArray(history?.entries) ? history.entries : [];
  const index = Number.isInteger(history?.currentIndex) ? history.currentIndex + delta : -1;
  return index >= 0 && index < entries.length ? entries[index] : null;
}
