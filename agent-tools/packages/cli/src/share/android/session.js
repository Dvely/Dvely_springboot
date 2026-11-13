/**
 * One running `qeploy share android` (docs/device-preview-share-design.md §12):
 *
 *   emulator ─scrcpy H.264─▶ SCREEN_CONFIG / SCREEN_FRAME ──▶ server ──▶ viewers (WebCodecs)
 *   emulator ◀─scrcpy control── INPUT ◀───────────────────── server ◀── the viewer holding control
 *
 * The tunnel, reconnects and control prompts are TunnelSession's. `device` is a ScrcpyDevice in
 * production and a fake in tests; `adb` is only used for the app guard (§12.5).
 */
import { KIND, MAX_PAYLOAD_BYTES, encodeFrame } from '../protocol.js';
import { TunnelSession } from '../tunnel.js';
import { ALLOWED_SYSTEM_PACKAGES } from './adb.js';
import { createTouchState, isKeyboardText, mapAndroidInput } from './input.js';
import { encodeSetClipboard, encodeText, screenFramePayload } from './scrcpy-protocol.js';

/** §12.3: above this much unsent on the tunnel, frames that are not key frames are dropped. */
export const HIGH_WATER_BYTES = 2 * 1024 * 1024;
/** Below this, a fresh key frame is asked for and sending resumes. */
export const LOW_WATER_BYTES = 512 * 1024;
const DRAIN_POLL_MS = 100;
/** One RESET_VIDEO a second at most — each restarts the encoder. */
const RESET_MIN_INTERVAL_MS = 1000;
export const GUARD_INTERVAL_MS = 1000;
/**
 * After a clipboard paste, the next message waits this long, and text typed meanwhile is merged
 * into one paste. The app reads the clipboard some time after the PASTE key; a SET_CLIPBOARD before
 * then makes the earlier paste insert the later text. Measured on Chrome in an API 35 emulator,
 * pasting 가·나·다·라·마 at a fixed gap: 200 ms gave 마마마마마, 350 ms 가나다마마, 500 ms and 800 ms
 * 가나다라마.
 */
export const PASTE_GAP_MS = 600;

export class AndroidShareSession extends TunnelSession {
  /**
   * @param {object} o
   * @param {{start: Function, sendControl: Function, resetVideo: Function, stop: Function}} o.device
   * @param {{foregroundPackage: Function, launchApp: Function}} [o.adb] for the app guard
   * @param {string|null} o.pkg the shared app; null shares the whole emulator
   * @param {boolean} [o.guard] keep the share inside `pkg` (§12.5)
   * @param {string} o.targetLabel what HELLO announces
   * @param {string} o.deviceName the config's title
   */
  constructor({ device, adb = null, pkg = null, guard = true, targetLabel, deviceName, guardIntervalMs = GUARD_INTERVAL_MS, ...tunnel }) {
    super(tunnel);
    Object.assign(this, { device, adb, pkg, guard: guard && Boolean(pkg) && Boolean(adb), targetLabel, deviceName, guardIntervalMs });
    this.screen = { width: 0, height: 0, codec: null };
    this.touch = createTouchState();
    this.needKey = true;
    this.lastResetAt = -Infinity;
    this.resetTimer = null;
    this.drainTimer = null;
    this.guardTimer = null;
    this.guardBusy = false;
    this.outside = null; // the package the guard last pulled the share back from
    this.warnedLargeFrame = false;
    this.outbox = []; // control messages in order; {text} entries next to each other are merged while they wait
    this.outboxTimer = null;
    this.pasteReadyAt = -Infinity;
  }

  /**
   * Starts the scrcpy server, then the tunnel; resolves when the share is over.
   *
   * @returns {Promise<{reason: string, status?: number, error?: Error}>} TunnelSession's reasons, or
   *   `device-exit` when the emulator's video stream ended
   */
  async run() {
    await this.device.start({
      onSize: (size) => this.onSize(size),
      onCodec: (codec) => this.onCodec(codec),
      onFrame: (frame) => this.onFrame(frame),
      onClose: (error) => this.finish({ reason: 'device-exit', error }),
    });
    if (this.closed) return this.done;
    if (this.guard) this.startGuard();
    this.connect();
    return this.done;
  }

  // ── screen ───────────────────────────────────────────────────────────────

  onSize({ width, height }) {
    this.screen.width = width;
    this.screen.height = height;
    // A new capture session: its key frame is on the way, and nothing before it decodes after it.
    this.needKey = true;
    this.touch.down = false;
    this.sendConfigIfChanged();
  }

  onCodec(codec) {
    this.screen.codec = codec;
    this.sendConfigIfChanged();
  }

  sendConfigIfChanged() {
    const { width, height, codec } = this.screen;
    if (!this.ready || width <= 0 || height <= 0 || !codec) return;
    const config = JSON.stringify({ format: 'h264', codec, width, height, url: this.pkg ?? '', title: this.deviceName ?? '' });
    if (config === this.sentConfig) return;
    if (this.sendRaw(encodeFrame(KIND.SCREEN_CONFIG, config))) {
      this.sentConfig = config;
      this.needKey = true;
    }
  }

  onFrame(frame) {
    if (this.closed || !this.ready) return;
    this.sendConfigIfChanged();
    if (this.sentConfig === null) return; // §4.2: the viewer learns the size and codec first

    const payload = screenFramePayload(frame);
    if (payload.length > MAX_PAYLOAD_BYTES) {
      if (!this.warnedLargeFrame) {
        this.log('화면 한 장이 2MiB 를 넘어 건너뜁니다. --bit-rate 나 --max-size 를 낮추면 나아집니다.');
        this.warnedLargeFrame = true;
      }
      this.waitForKey();
      return;
    }
    if (this.needKey && !frame.key) return;
    if (this.bufferedAmount > HIGH_WATER_BYTES) {
      // §12.3: the network is behind. Skip ahead to a fresh key frame once it has caught up.
      this.waitForKey();
      return;
    }
    if (this.sendRaw(encodeFrame(KIND.SCREEN_FRAME, payload))) this.needKey = false;
  }

  /** Drops frames until a key frame, asked for as soon as the tunnel has room for it. */
  waitForKey() {
    this.needKey = true;
    if (this.drainTimer) return;
    const poll = () => {
      this.drainTimer = null;
      if (this.closed || !this.needKey) return;
      if (this.bufferedAmount > LOW_WATER_BYTES) {
        this.drainTimer = setTimeout(poll, DRAIN_POLL_MS);
        return;
      }
      this.requestKeyFrame();
    };
    poll();
  }

  /** RESET_VIDEO, at most once a second; a request inside that second is kept for its end. */
  requestKeyFrame() {
    if (this.closed || this.resetTimer) return;
    const wait = this.lastResetAt + RESET_MIN_INTERVAL_MS - this.now();
    if (wait > 0) {
      this.resetTimer = setTimeout(() => {
        this.resetTimer = null;
        this.requestKeyFrame();
      }, wait);
      return;
    }
    this.lastResetAt = this.now();
    this.device.resetVideo();
  }

  // ── TunnelSession hooks ──────────────────────────────────────────────────

  helloPayload() {
    return { cliVersion: this.cliVersion, kind: 'ANDROID', target: this.targetLabel };
  }

  onReady() {
    // The server has nothing of this stream yet: it needs the size, the codec and a key frame.
    this.sendConfigIfChanged();
    this.needKey = true;
    this.requestKeyFrame();
  }

  onKeyframeRequest() {
    this.requestKeyFrame();
  }

  handleInput(input) {
    for (const item of mapAndroidInput(input, this.screen, this.touch)) {
      const last = this.outbox.at(-1);
      if (!Buffer.isBuffer(item) && last && !Buffer.isBuffer(last)) last.text += item.text;
      else this.outbox.push(Buffer.isBuffer(item) ? item : { text: item.text });
    }
    this.drainOutbox();
  }

  /**
   * Sends the outbox in order, holding everything behind a paste for PASTE_GAP_MS. Text that waits
   * there — Korean typed a syllable at a time, with its spaces — goes in as one paste afterwards.
   */
  drainOutbox() {
    if (this.outboxTimer || this.closed) return;
    while (this.outbox.length) {
      const wait = this.pasteReadyAt - this.now();
      if (wait > 0) {
        this.outboxTimer = setTimeout(() => {
          this.outboxTimer = null;
          this.drainOutbox();
        }, wait);
        return;
      }
      const item = this.outbox.shift();
      if (Buffer.isBuffer(item)) {
        this.device.sendControl(item);
      } else if (isKeyboardText(item.text)) {
        for (const message of encodeText(item.text)) this.device.sendControl(message);
      } else {
        this.device.sendControl(encodeSetClipboard(item.text, { paste: true }));
        this.pasteReadyAt = this.now() + PASTE_GAP_MS;
      }
    }
  }

  onFinished() {
    clearTimeout(this.resetTimer);
    clearTimeout(this.drainTimer);
    clearTimeout(this.outboxTimer);
    clearInterval(this.guardTimer);
    Promise.resolve(this.device.stop()).catch(() => {});
  }

  // ── §12.5 app guard ──────────────────────────────────────────────────────

  startGuard() {
    this.guardTimer = setInterval(() => this.checkForeground(), this.guardIntervalMs);
  }

  async checkForeground() {
    if (this.closed || this.guardBusy) return;
    this.guardBusy = true;
    try {
      const top = await this.adb.foregroundPackage();
      if (this.closed || !top || top === this.pkg || ALLOWED_SYSTEM_PACKAGES.includes(top)) {
        if (top === this.pkg) this.outside = null;
        return;
      }
      if (this.outside !== top) {
        this.log(`공유 중인 앱 밖(${top})으로 나가 ${this.pkg} 를 다시 앞으로 올렸습니다.`);
        this.outside = top;
      }
      await this.adb.launchApp(this.pkg);
    } catch {
      // adb hiccup: the next tick looks again
    } finally {
      this.guardBusy = false;
    }
  }
}
