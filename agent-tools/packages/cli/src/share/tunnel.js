/**
 * The device-tunnel half of a share (docs/device-preview-share-design.md §4.1, §5): one `ws`
 * WebSocket to the Qeploy server, reconnected with backoff, plus the owner's side of control —
 * approval prompts and the presence line.
 *
 * What is shared — a Chrome tab (`share web`) or an Android emulator (`share android`) — lives in a
 * subclass, which fills in the hooks:
 *
 *   helloPayload()       what HELLO announces
 *   onReady()            WELCOME arrived on a new socket: send the screen again
 *   handleInput(input)   one §4.3 input from the viewer holding control
 *   onKeyframeRequest()  the server needs a fresh key frame (H.264 only, §12.3)
 *   onFinished()         the share is over: stop timers, release the device
 */
import WebSocket from 'ws';
import { KIND, HEADER_BYTES, MAX_PAYLOAD_BYTES, decodeFrame, encodeJsonFrame, parseJsonPayload } from './protocol.js';
import { backoffDelay } from './policy.js';

/** §5: a granted control lasts ten minutes. */
export const CONTROL_TTL_SECONDS = 600;

/** GOODBYE reasons after which reconnecting would be wrong (§4.1). */
export const TERMINAL_GOODBYE = new Set(['revoked', 'expired', 'replaced']);

/**
 * Pings keep the tunnel alive through proxies that drop idle connections (nginx's default read
 * timeout is 60 s, and a still screen sends no frames), and notice a dead network sooner than TCP.
 */
export const PING_INTERVAL_MS = 25000;

/** WebSocket readyState OPEN — the same number in `ws` and in the WHATWG API. */
export const OPEN = 1;

export const noop = () => {};

export class TunnelSession {
  /**
   * @param {object} o
   * @param {string} o.tunnelUrl already checked (wss, `?v=1`)
   * @param {string} o.token the PAT
   * @param {string} o.cliVersion
   * @param {(question: string) => Promise<boolean>} o.askControl
   * @param {(text: string) => void} [o.log] one line of status for the owner
   */
  constructor({
    tunnelUrl,
    token,
    cliVersion,
    askControl,
    log = noop,
    WebSocketImpl = WebSocket,
    backoff = backoffDelay,
    pingIntervalMs = PING_INTERVAL_MS,
    now = Date.now,
  }) {
    Object.assign(this, { tunnelUrl, token, cliVersion, askControl, log, WebSocketImpl, backoff, pingIntervalMs, now });

    this.socket = null;
    this.ready = false; // WELCOME received on the current socket
    this.everConnected = false;
    this.attempt = 0;
    this.reconnectTimer = null;
    this.pingTimer = null;
    this.sentConfig = null; // the SCREEN_CONFIG the current socket last got

    this.controlQueue = Promise.resolve();
    this.presenceLine = null;

    this.closed = false;
    this.done = new Promise((resolve) => {
      this.resolveDone = resolve;
    });
  }

  /** Ends the share from this side (Ctrl+C, the shared thing going away). Safe to call more than once. */
  stop(reason = 'interrupt') {
    this.finish({ reason });
  }

  // ── hooks ────────────────────────────────────────────────────────────────

  helloPayload() {
    return { cliVersion: this.cliVersion };
  }

  onReady() {}

  handleInput() {}

  onKeyframeRequest() {}

  onFinished() {}

  // ── tunnel ───────────────────────────────────────────────────────────────

  connect() {
    if (this.closed) return;
    const socket = new this.WebSocketImpl(this.tunnelUrl, {
      headers: { Authorization: `Bearer ${this.token}` },
      perMessageDeflate: false,
      maxPayload: HEADER_BYTES + MAX_PAYLOAD_BYTES,
      handshakeTimeout: 15000,
    });
    this.socket = socket;
    this.ready = false;
    this.sentConfig = null;

    let handshakeStatus = null;
    let lastError = null;

    socket.on('unexpected-response', (_req, res) => {
      // 404/410 mean the link is gone and 401/403 that the token is refused — neither gets better
      // by retrying. Handling the event means ws leaves the socket to us, so end it here.
      handshakeStatus = res.statusCode;
      socket.terminate();
    });
    socket.on('open', () => {
      if (socket !== this.socket) return;
      this.sendRaw(encodeJsonFrame(KIND.HELLO, this.helloPayload()));
      this.startPing(socket);
    });
    socket.on('message', (data, isBinary) => {
      if (socket !== this.socket || isBinary === false) return;
      this.onTunnelFrame(data);
    });
    socket.on('error', (e) => {
      lastError = e;
    });
    socket.on('close', () => this.onTunnelClose(socket, handshakeStatus, lastError));
  }

  onTunnelFrame(data) {
    let frame;
    try {
      frame = decodeFrame(data);
    } catch {
      return;
    }
    if (frame.streamId !== 0) return; // only stream 0 is in use (§4.1)
    const body = () => parseJsonPayload(frame.payload) ?? {};
    switch (frame.kind) {
      case KIND.WELCOME:
        return this.onWelcome(body());
      case KIND.GOODBYE:
        return this.onGoodbye(body());
      case KIND.INPUT:
        return this.onInput(body());
      case KIND.CONTROL_REQUEST:
        return this.onControlRequest(body());
      case KIND.PRESENCE:
        return this.onPresence(body());
      case KIND.KEYFRAME_REQUEST:
        return this.onKeyframeRequest();
      default:
        // A kind this CLI does not know yet (§10 relaying, say) is not an error.
        return undefined;
    }
  }

  onWelcome() {
    this.ready = true;
    this.attempt = 0;
    this.log(this.everConnected ? '다시 연결했습니다.' : '연결했습니다. 화면을 보내는 중입니다.');
    this.everConnected = true;
    this.onReady();
  }

  onGoodbye({ reason } = {}) {
    if (TERMINAL_GOODBYE.has(reason)) {
      this.finish({ reason });
      return;
    }
    // Anything else (a server restart, say): the close that follows reconnects.
    this.log(`서버가 연결을 끊었습니다${typeof reason === 'string' ? ` (${reason.slice(0, 40)})` : ''}.`);
  }

  onInput(payload) {
    // The viewer's envelope is {type: "input", input}; the tunnel carries the inner object. Taking
    // either keeps a server that forwards the whole message working.
    const input = payload?.type === 'input' && payload.input ? payload.input : payload;
    if (this.closed || !input || typeof input !== 'object') return;
    this.handleInput(input);
  }

  onControlRequest({ viewerId, name } = {}) {
    if (typeof viewerId !== 'string' && typeof viewerId !== 'number') return;
    const who = displayName(name);
    // One question at a time: two prompts racing for the same terminal would garble both.
    this.controlQueue = this.controlQueue.then(async () => {
      if (this.closed) return;
      let granted = false;
      try {
        granted = (await this.askControl(`'${who}' 님이 조작권을 요청합니다. 10분 동안 허용할까요?`)) === true;
      } catch {
        granted = false;
      }
      if (this.closed) return;
      this.sendRaw(encodeJsonFrame(KIND.CONTROL_DECISION, { viewerId, granted, ttlSeconds: CONTROL_TTL_SECONDS }));
      this.log(granted ? `'${who}' 님에게 10분 동안 조작권을 줬습니다.` : `'${who}' 님의 조작 요청을 거절했습니다.`);
    });
  }

  onPresence({ viewers, controller } = {}) {
    const count = Number.isInteger(viewers) && viewers >= 0 ? viewers : 0;
    const who = controller && typeof controller === 'object' ? displayName(controller.name ?? controller.viewerId) : null;
    const line = `보는 사람 ${count}명 · ${who ? `조작 중: ${who}` : '조작하는 사람 없음'}`;
    if (line === this.presenceLine) return;
    this.presenceLine = line;
    this.log(line);
  }

  onTunnelClose(socket, handshakeStatus, error) {
    if (socket !== this.socket) return;
    this.socket = null;
    this.ready = false;
    this.stopPing();
    if (this.closed) return;

    if (handshakeStatus === 404 || handshakeStatus === 410) {
      this.finish({ reason: 'gone', status: handshakeStatus });
      return;
    }
    if (handshakeStatus === 401 || handshakeStatus === 403) {
      this.finish({ reason: 'auth', status: handshakeStatus });
      return;
    }
    if (handshakeStatus === 400) {
      // The server refused the request itself — the protocol version (`?v=`) it does not speak.
      this.finish({ reason: 'rejected', status: handshakeStatus });
      return;
    }

    const delay = this.backoff(this.attempt++);
    const why = handshakeStatus ? ` (HTTP ${handshakeStatus})` : error?.message ? ` (${error.message})` : '';
    this.log(`터널이 끊겼습니다${why}. ${Math.max(1, Math.round(delay / 1000))}초 뒤 다시 연결합니다.`);
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.connect();
    }, delay);
  }

  startPing(socket) {
    this.stopPing();
    if (!this.pingIntervalMs) return;
    let waiting = false;
    socket.on('pong', () => {
      waiting = false;
    });
    this.pingTimer = setInterval(() => {
      if (waiting) {
        // No pong for a whole interval: the connection is dead even if TCP has not noticed.
        socket.terminate();
        return;
      }
      waiting = true;
      try {
        socket.ping();
      } catch {
        // closing already
      }
    }, this.pingIntervalMs);
  }

  stopPing() {
    if (this.pingTimer) clearInterval(this.pingTimer);
    this.pingTimer = null;
  }

  /** Bytes queued on the tunnel and not yet handed to the network. */
  get bufferedAmount() {
    return this.socket?.bufferedAmount ?? 0;
  }

  /** @returns {boolean} whether it went out */
  sendRaw(frame) {
    const socket = this.socket;
    if (!socket || socket.readyState !== OPEN) return false;
    socket.send(frame, { binary: true });
    return true;
  }

  finish(result) {
    if (this.closed) return;
    this.closed = true;
    this.ready = false;
    clearTimeout(this.reconnectTimer);
    this.stopPing();
    try {
      this.onFinished();
    } catch {
      // ending regardless
    }
    const socket = this.socket;
    this.socket = null;
    if (socket) {
      try {
        if (socket.readyState === OPEN) socket.close(1000);
        else socket.terminate();
      } catch {
        // already gone
      }
    }
    this.resolveDone(result);
  }
}

/** A viewer-chosen name, made safe to print in the owner's terminal. */
export function displayName(name) {
  const text = String(name ?? '')
    // eslint-disable-next-line no-control-regex
    .replace(/[\u0000-\u001f\u007f-\u009f]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 40);
  return text || '이름 없는 사람';
}
