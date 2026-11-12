/**
 * One running `qeploy share web`: the page in Chrome on one side, the device tunnel to the Qeploy
 * server on the other (docs/device-preview-share-design.md §4, §7, §8).
 *
 *   Chrome ──screencast──▶ SCREEN_CONFIG / SCREEN_FRAME ──▶ server ──▶ viewers
 *   Chrome ◀──CDP input─── INPUT ◀──────────────────────── server ◀── the viewer holding control
 *
 * The CDP side is two small objects, `browser` and `page`, each with `send(method, params)` and
 * `on(event, fn)` — a real flattened session in production, a fake in tests. The tunnel is a real
 * `ws` WebSocket (the built-in one cannot carry the Authorization header).
 */
import WebSocket from 'ws';
import {
  KIND,
  HEADER_BYTES,
  MAX_PAYLOAD_BYTES,
  decodeFrame,
  encodeFrame,
  encodeJsonFrame,
  parseJsonPayload,
} from './protocol.js';
import { createPointerState, mapInput } from './input.js';
import {
  ACK_BUFFER_LIMIT,
  MAX_FPS,
  ackDelay,
  backoffDelay,
  describeOrigin,
  historyEntry,
  isAllowedNavigation,
} from './policy.js';

/** §5: a granted control lasts ten minutes. */
export const CONTROL_TTL_SECONDS = 600;

/** GOODBYE reasons after which reconnecting would be wrong (§4.1). */
export const TERMINAL_GOODBYE = new Set(['revoked', 'expired', 'replaced']);

/**
 * Pings keep the tunnel alive through proxies that drop idle connections (nginx's default read
 * timeout is 60 s, and a still page sends no frames), and notice a dead network sooner than TCP.
 */
const PING_INTERVAL_MS = 25000;

/** How often a changing screen may trigger a title lookup. */
const TITLE_CHECK_MS = 1000;

const noop = () => {};

/** WebSocket readyState OPEN — the same number in `ws` and in the WHATWG API. */
const OPEN = 1;

export class ShareSession {
  /**
   * @param {object} o
   * @param {URL} o.target the shared page
   * @param {string} [o.targetLabel] what HELLO announces; defaults to the URL
   * @param {{width: number, height: number, mobile: boolean}|null} [o.viewport]
   * @param {{send: Function, on: Function}} o.browser
   * @param {{send: Function, on: Function}} o.page
   * @param {string} [o.targetId] the shared tab; any other tab that opens is closed
   * @param {string} o.tunnelUrl already checked (wss, `?v=1`)
   * @param {string} o.token the PAT
   * @param {string} o.cliVersion
   * @param {(question: string) => Promise<boolean>} o.askControl
   * @param {(text: string) => void} [o.log] one line of status for the owner
   */
  constructor({
    target,
    targetLabel,
    viewport = null,
    browser,
    page,
    targetId = null,
    tunnelUrl,
    token,
    cliVersion,
    askControl,
    log = noop,
    WebSocketImpl = WebSocket,
    backoff = backoffDelay,
    pingIntervalMs = PING_INTERVAL_MS,
    platform = process.platform,
    now = Date.now,
  }) {
    Object.assign(this, {
      target,
      targetLabel: targetLabel ?? target.href,
      viewport,
      browser,
      page,
      targetId,
      tunnelUrl,
      token,
      cliVersion,
      askControl,
      log,
      WebSocketImpl,
      backoff,
      pingIntervalMs,
      platform,
      now,
    });
    this.origin = target.origin;
    this.screen = { width: 0, height: 0, url: target.href, title: '' };
    this.pointer = createPointerState();
    this.mainFrameId = null;
    this.lastTitleCheck = -Infinity;
    this.titleTimer = null;

    this.socket = null;
    this.ready = false; // WELCOME received on the current socket
    this.everConnected = false;
    this.attempt = 0;
    this.reconnectTimer = null;
    this.pingTimer = null;
    this.sentConfig = null;

    this.latestFrame = null;
    this.ackQueue = [];
    this.lastAckAt = -Infinity;
    this.ackTimer = null;
    this.warnedLargeFrame = false;

    this.controlQueue = Promise.resolve();
    this.presenceLine = null;

    this.closed = false;
    this.done = new Promise((resolve) => {
      this.resolveDone = resolve;
    });
  }

  /**
   * Sets the page up, opens the tunnel, and resolves when the share is over.
   *
   * @returns {Promise<{reason: string, status?: number}>} reason is one of interrupt, revoked,
   *   expired, replaced, gone (the handshake got 404/410), auth (401/403), rejected (400), or
   *   whatever stop() got
   */
  async run() {
    await this.setupPage();
    if (!this.closed) this.connect();
    return this.done;
  }

  /** Ends the share from this side (Ctrl+C, Chrome exiting). Safe to call more than once. */
  stop(reason = 'interrupt') {
    this.finish({ reason });
  }

  // ── Chrome ───────────────────────────────────────────────────────────────

  async setupPage() {
    const { browser, page } = this;

    browser.on('Target.targetCreated', ({ targetInfo } = {}) => this.onTargetCreated(targetInfo));
    browser.on('Target.targetInfoChanged', ({ targetInfo } = {}) => this.onTargetInfoChanged(targetInfo));
    page.on('Page.screencastFrame', (params) => this.onScreencastFrame(params));
    page.on('Fetch.requestPaused', (params) => this.onRequestPaused(params));
    page.on('Page.frameNavigated', ({ frame } = {}) => this.onFrameNavigated(frame));
    page.on('Page.navigatedWithinDocument', (params) => this.onNavigatedWithinDocument(params));
    page.on('Page.loadEventFired', () => this.refreshTitle());
    page.on('Page.javascriptDialogOpening', (params) => this.onDialog(params));
    // Intercepted and never answered, which is how a file chooser is refused (§8).
    page.on('Page.fileChooserOpened', () => this.log('파일 선택 창을 막았습니다 (공유 중에는 파일을 올릴 수 없습니다).'));

    if (this.viewport) {
      await page.send('Emulation.setDeviceMetricsOverride', {
        width: this.viewport.width,
        height: this.viewport.height,
        deviceScaleFactor: this.viewport.mobile ? 2 : 1,
        mobile: this.viewport.mobile,
      });
    }
    await browser.send('Browser.setDownloadBehavior', { behavior: 'deny' });
    await browser.send('Target.setDiscoverTargets', { discover: true });
    await page.send('Page.enable');
    const { frameTree } = (await page.send('Page.getFrameTree')) ?? {};
    this.mainFrameId = frameTree?.frame?.id ?? null;
    await page.send('Page.setInterceptFileChooserDialog', { enabled: true });
    await page.send('Fetch.enable', { patterns: [{ resourceType: 'Document', requestStage: 'Request' }] });

    if (this.viewport) {
      this.screen.width = this.viewport.width;
      this.screen.height = this.viewport.height;
    } else {
      const metrics = await page.send('Page.getLayoutMetrics').catch(() => null);
      const vp = metrics?.cssLayoutViewport ?? metrics?.layoutViewport;
      this.screen.width = Math.round(vp?.clientWidth ?? 0);
      this.screen.height = Math.round(vp?.clientHeight ?? 0);
    }

    // Started on the blank tab, before navigating. Leaving about:blank for the app swaps renderer
    // processes, and for a moment afterwards the page answers "Not attached to an active page"
    // (measured on Chrome 157); a screencast already running carries on across the swap.
    await page.send('Page.startScreencast', { format: 'jpeg', quality: 70, maxWidth: 1280, maxHeight: 1280 });

    const navigation = await page.send('Page.navigate', { url: this.target.href });
    if (navigation?.errorText) {
      this.log(
        `${this.target.origin} 를 열지 못했습니다 (${navigation.errorText}). dev 서버가 켜져 있는지 확인하세요. ` +
          '공유는 계속되고, 서버를 켠 뒤 새로고침하면 보입니다.'
      );
    }
  }

  onTargetCreated(info) {
    if (this.closed || !info || info.type !== 'page' || !this.targetId || info.targetId === this.targetId) return;
    // A popup or target=_blank tab would be a second page nobody sees, outside the navigation guard
    // (Fetch is enabled on the shared tab only).
    this.browser.send('Target.closeTarget', { targetId: info.targetId }).catch(noop);
    this.log('새 창·탭은 공유하지 않아 닫았습니다.');
  }

  onTargetInfoChanged(info) {
    if (!info || info.targetId !== this.targetId) return;
    this.refreshTitle();
  }

  /**
   * At most one lookup a second while frames keep coming, plus one after the last of them — a
   * click that sets document.title repaints once and then the screen goes still.
   */
  scheduleTitleCheck() {
    if (this.titleTimer) return;
    const wait = this.lastTitleCheck + TITLE_CHECK_MS - this.now();
    if (wait <= 0) {
      this.refreshTitle();
      return;
    }
    this.titleTimer = setTimeout(() => {
      this.titleTimer = null;
      if (!this.closed) this.refreshTitle();
    }, wait);
  }

  /**
   * Reads the page title from the navigation history.
   *
   * Not from Target.targetInfoChanged: Chrome (157) sends that only on navigation, with the URL as
   * the title, and never for <title> or document.title. So the title is read when a page loads or
   * moves, and while the screen is changing (scheduleTitleCheck).
   */
  async refreshTitle() {
    this.lastTitleCheck = this.now();
    try {
      const history = await this.page.send('Page.getNavigationHistory');
      const title = history?.entries?.[history.currentIndex]?.title;
      if (this.closed || typeof title !== 'string' || title === this.screen.title) return;
      this.screen.title = title;
      this.sendConfigIfChanged();
    } catch {
      // The tab may be mid-navigation; the next load or frame asks again.
    }
  }

  /** §8: only the shared origin may load as the top-level document. */
  onRequestPaused({ requestId, request, frameId } = {}) {
    const topLevel = !this.mainFrameId || frameId === this.mainFrameId;
    const url = request?.url;
    if (!topLevel || isAllowedNavigation(this.origin, url)) {
      this.page.send('Fetch.continueRequest', { requestId }).catch(noop);
      return;
    }
    this.page.send('Fetch.failRequest', { requestId, errorReason: 'BlockedByClient' }).catch(noop);
    this.log(`다른 주소로 가는 이동을 막았습니다: ${describeOrigin(url)} (이 공유는 ${this.origin} 안에서만 움직입니다)`);
  }

  onFrameNavigated(frame) {
    if (!frame || frame.parentId) return;
    if (this.mainFrameId && frame.id !== this.mainFrameId) return;
    // An error page (including the one our own block shows) carries the address it failed on.
    if (!frame.unreachableUrl && !isAllowedNavigation(this.origin, frame.url)) {
      // Fetch does not see every scheme; anything that slipped past it is sent back.
      this.log(`허용하지 않은 주소로 이동해 되돌립니다: ${describeOrigin(frame.url)}`);
      this.page.send('Page.navigate', { url: this.target.href }).catch(noop);
      return;
    }
    this.screen.url = frame.unreachableUrl || frame.url;
    this.sendConfigIfChanged();
    this.refreshTitle();
  }

  onNavigatedWithinDocument({ frameId, url } = {}) {
    if (this.mainFrameId && frameId !== this.mainFrameId) return;
    if (typeof url !== 'string') return;
    this.screen.url = url;
    this.sendConfigIfChanged();
    this.refreshTitle();
  }

  /** A dialog is not part of the screencast, so nobody could answer it; it would freeze the page. */
  onDialog({ type } = {}) {
    const accept = type === 'alert' || type === 'beforeunload';
    this.page.send('Page.handleJavaScriptDialog', { accept }).catch(noop);
    this.log(`페이지의 ${type ?? '알림'} 창을 ${accept ? '닫았습니다' : '취소했습니다'} (공유 화면에는 보이지 않습니다).`);
  }

  // ── Screen ───────────────────────────────────────────────────────────────

  onScreencastFrame({ data, metadata, sessionId } = {}) {
    if (this.closed) return;
    this.ackQueue.push(sessionId);

    const frame = typeof data === 'string' ? Buffer.from(data, 'base64') : Buffer.alloc(0);
    if (frame.length > MAX_PAYLOAD_BYTES) {
      // §4.4: over 2 MiB is dropped. Still acknowledged, or Chrome would stop sending altogether.
      if (!this.warnedLargeFrame) {
        this.log('화면 한 장이 2MiB 를 넘어 건너뜁니다. --viewport 로 화면을 줄이면 나아집니다.');
        this.warnedLargeFrame = true;
      }
    } else if (frame.length > 0) {
      const width = Math.round(metadata?.deviceWidth ?? 0);
      const height = Math.round(metadata?.deviceHeight ?? 0);
      if (width > 0 && height > 0) {
        this.screen.width = width;
        this.screen.height = height;
      }
      this.latestFrame = frame;
      if (this.ready) {
        this.sendConfigIfChanged();
        this.sendFrame(frame);
      }
      this.scheduleTitleCheck();
    }
    this.pumpAcks();
  }

  sendFrame(frame) {
    // A frame never goes out before the viewer knows the screen's size and address (§4.2).
    if (this.sentConfig === null) return;
    this.sendRaw(encodeFrame(KIND.SCREEN_FRAME, frame));
  }

  /** Sends SCREEN_CONFIG when the size, address or title differs from what this socket last got. */
  sendConfigIfChanged() {
    if (!this.ready || this.screen.width <= 0 || this.screen.height <= 0) return;
    const config = JSON.stringify({
      format: 'jpeg',
      width: this.screen.width,
      height: this.screen.height,
      url: this.screen.url,
      title: this.screen.title,
    });
    if (config === this.sentConfig) return;
    if (this.sendRaw(encodeFrame(KIND.SCREEN_CONFIG, config))) this.sentConfig = config;
  }

  /**
   * Acknowledges held screencast frames one at a time, as fast as §4.4 allows. While the tunnel is
   * down nothing is acknowledged, so Chrome stops producing frames nobody would receive.
   */
  pumpAcks() {
    if (this.closed || this.ackTimer || this.ackQueue.length === 0 || !this.ready) return;
    const delay = ackDelay({
      now: this.now(),
      lastAckAt: this.lastAckAt,
      bufferedAmount: this.socket?.bufferedAmount ?? 0,
      maxFps: MAX_FPS,
      maxBuffered: ACK_BUFFER_LIMIT,
    });
    if (delay > 0) {
      this.ackTimer = setTimeout(() => {
        this.ackTimer = null;
        this.pumpAcks();
      }, delay);
      return;
    }
    const sessionId = this.ackQueue.shift();
    this.lastAckAt = this.now();
    this.page.send('Page.screencastFrameAck', { sessionId }).catch(noop);
    this.pumpAcks();
  }

  // ── Tunnel ───────────────────────────────────────────────────────────────

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
      this.sendRaw(encodeJsonFrame(KIND.HELLO, { cliVersion: this.cliVersion, kind: 'WEB', target: this.targetLabel }));
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
    if (frame.streamId !== 0) return; // phase 1 has only stream 0
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
    this.sendConfigIfChanged();
    // Chrome sends nothing while the page is still, so a viewer would otherwise wait for a change.
    if (this.latestFrame) this.sendFrame(this.latestFrame);
    this.pumpAcks();
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
    const { commands, nav } = mapInput(input, this.screen, this.pointer, { platform: this.platform });
    for (const { method, params } of commands) {
      this.page.send(method, params).catch(noop);
    }
    if (nav) this.navigate(nav).catch(noop);
  }

  /** §8: the only address changes a controller can make are back, forward and reload. */
  async navigate(action) {
    if (action === 'reload') {
      await this.page.send('Page.reload', {});
      return;
    }
    const history = await this.page.send('Page.getNavigationHistory');
    const entry = historyEntry(history, action === 'back' ? -1 : 1);
    if (!entry) return;
    if (!isAllowedNavigation(this.origin, entry.url)) {
      this.log(`기록에 있는 다른 주소로는 이동하지 않습니다: ${describeOrigin(entry.url)}`);
      return;
    }
    await this.page.send('Page.navigateToHistoryEntry', { entryId: entry.id });
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
    clearTimeout(this.ackTimer);
    clearTimeout(this.titleTimer);
    this.stopPing();
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
function displayName(name) {
  const text = String(name ?? '')
    // eslint-disable-next-line no-control-regex
    .replace(/[\u0000-\u001f\u007f-\u009f]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 40);
  return text || '이름 없는 사람';
}
