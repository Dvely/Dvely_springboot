/**
 * One running `qeploy share web`: the page in Chrome on one side, the device tunnel to the Qeploy
 * server on the other (docs/device-preview-share-design.md §4, §7, §8).
 *
 *   Chrome ──screencast──▶ SCREEN_CONFIG / SCREEN_FRAME ──▶ server ──▶ viewers
 *   Chrome ◀──CDP input─── INPUT ◀──────────────────────── server ◀── the viewer holding control
 *
 * The CDP side is two small objects, `browser` and `page`, each with `send(method, params)` and
 * `on(event, fn)` — a real flattened session in production, a fake in tests. The tunnel, its
 * reconnects and the control prompts are TunnelSession's (tunnel.js).
 */
import { KIND, MAX_PAYLOAD_BYTES, encodeFrame } from './protocol.js';
import { createPointerState, mapInput } from './input.js';
import { ACK_BUFFER_LIMIT, MAX_FPS, ackDelay, describeOrigin, historyEntry, isAllowedNavigation } from './policy.js';
import { TunnelSession, noop } from './tunnel.js';

export { CONTROL_TTL_SECONDS, TERMINAL_GOODBYE } from './tunnel.js';

/** How often a changing screen may trigger a title lookup. */
const TITLE_CHECK_MS = 1000;

export class ShareSession extends TunnelSession {
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
  constructor({ target, targetLabel, viewport = null, browser, page, targetId = null, platform = process.platform, ...tunnel }) {
    super(tunnel);
    Object.assign(this, {
      target,
      targetLabel: targetLabel ?? target.href,
      viewport,
      browser,
      page,
      targetId,
      platform,
    });
    this.origin = target.origin;
    this.screen = { width: 0, height: 0, url: target.href, title: '' };
    this.pointer = createPointerState();
    this.mainFrameId = null;
    this.lastTitleCheck = -Infinity;
    this.titleTimer = null;

    this.latestFrame = null;
    this.ackQueue = [];
    this.lastAckAt = -Infinity;
    this.ackTimer = null;
    this.warnedLargeFrame = false;
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

  // ── TunnelSession hooks ──────────────────────────────────────────────────

  helloPayload() {
    return { cliVersion: this.cliVersion, kind: 'WEB', target: this.targetLabel };
  }

  onReady() {
    this.sendConfigIfChanged();
    // Chrome sends nothing while the page is still, so a viewer would otherwise wait for a change.
    if (this.latestFrame) this.sendFrame(this.latestFrame);
    this.pumpAcks();
  }

  handleInput(input) {
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

  onFinished() {
    clearTimeout(this.ackTimer);
    clearTimeout(this.titleTimer);
  }
}
