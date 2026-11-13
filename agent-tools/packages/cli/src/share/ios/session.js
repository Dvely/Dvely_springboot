/**
 * One running `qeploy share ios` (docs/device-preview-share-design.md §13):
 *
 *   simulator ─stream-video JPEG─▶ SCREEN_CONFIG / SCREEN_FRAME ──▶ server ──▶ viewers (as `share web`)
 *   simulator ◀─ AXe commands ─── INPUT ◀───────────────────────── server ◀── the viewer holding control
 *
 * The screen takes phase 1's JPEG path unchanged: a config before any frame, the config and the
 * latest frame again on every WELCOME, frames skipped while the tunnel is backed up. Inputs become
 * AXe commands run one at a time (AxeCommands); the screen-size read (`displays`) and the app
 * guard's read (`describe-ui`) wait in the same line, so nothing overtakes an input.
 *
 * Typed text goes through the simulator's hardware keyboard, which types in whatever input mode the
 * simulator is in (see switchKeyboard). On a simulator with a Korean keyboard the session reads the
 * mode before a run of text that needs the other one, switches, and reads it again.
 *
 * `video` and `commands` are AxeVideo and AxeCommands in production and fakes in tests; `simctl` is
 * only used by the app guard (§13.3). The tunnel, reconnects and control prompts are TunnelSession's.
 */
import { KIND, MAX_PAYLOAD_BYTES, encodeFrame } from '../protocol.js';
import { TunnelSession } from '../tunnel.js';
import { activeScreenSize, foregroundApp, jpegSize, keyboardMode } from './axe-protocol.js';
import { HID, axeCommands, createGestureState, mapIosInput, mergeItem } from './input.js';
import { hasKoreanKeyboard } from './simctl.js';

/** Above this much unsent on the tunnel, frames are skipped; the next one replaces them anyway. */
export const HIGH_WATER_BYTES = 1024 * 1024;
/** §13.3: how often the guard checks which app is in front. */
export const GUARD_INTERVAL_MS = 2000;

/** Korean ↔ English. iOS's Caps Lock language switch; emoji is not in its cycle (measured, iOS 26.5). */
export const CAPS_LOCK = Object.freeze(['key', String(HID.CAPS_LOCK)]);
/** Ctrl+Space: to the input source used before this one. Only used to leave the emoji keyboard. */
export const PREVIOUS_INPUT_SOURCE = Object.freeze(['key-combo', '--modifiers', String(HID.LEFT_CTRL), '--key', String(HID.SPACE)]);
/**
 * After Ctrl+Space iOS shows its list of input sources for about a second. A second Ctrl+Space
 * meanwhile moves down the list, and keys typed meanwhile were lost (measured), so nothing follows
 * it until then.
 */
export const INPUT_SOURCE_SETTLE_MS = 1500;
/**
 * The keyboard can still read the old mode just after Caps Lock (right after leaving emoji: ko at
 * once, en a second later, measured), so an unchanged read is retried this often, up to three reads.
 */
export const KEYBOARD_READ_RETRY_MS = 500;
const KEYBOARD_READS_AFTER_SWITCH = 3;

/** Told to the owner once per share, the first time a viewer's input is held back. */
const NOTICES = Object.freeze({
  edge: '화면 위·아래 가장자리에서 시작하는 스와이프는 보내지 않았습니다 (홈 화면·알림 센터로 나가는 길이라서).',
  'non-ascii': '영문·숫자·기호·한글이 아닌 글자(é, 漢字, 이모지 등)가 섞인 입력은 넣지 않았습니다 (키보드로 칠 수 없어서).',
  hangul: '시뮬레이터 언어가 한국어가 아니어서 한글은 넣지 않았습니다 (한글 키보드가 없음). 영문·숫자·기호만 들어갑니다.',
  keyboard:
    '시뮬레이터 키보드를 한글·영문 중 필요한 쪽으로 바꾸지 못해 그 입력을 넣지 않았습니다. 시뮬레이터 설정의 하드웨어 키보드에서 Caps Lock 언어 전환이 켜져 있는지 확인해 주세요.',
  paste: '붙여넣기(Cmd+V)는 보내지 않았습니다 (시뮬레이터 클립보드가 내 Mac 클립보드와 이어져 있을 수 있어서).',
});

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** An app-supplied label, short enough for one terminal line (terminalSafe is applied by the log). */
function shortLabel(label) {
  return String(label).replace(/\s+/g, ' ').trim().slice(0, 60);
}

export class IosShareSession extends TunnelSession {
  /**
   * @param {object} o
   * @param {{start: Function, stop: Function}} o.video AxeVideo
   * @param {{request: Function, stop: Function}} o.commands AxeCommands
   * @param {{launch: Function}} [o.simctl] for the app guard
   * @param {string} o.udid
   * @param {string|null} o.bundleId the shared app; null shares the whole simulator
   * @param {number|null} [o.appPid] its process id; the guard compares the app in front with it
   * @param {boolean} [o.guard] keep the share inside the app (§13.3)
   * @param {string|null} [o.language] the simulator's first language, for its keyboard
   * @param {string} o.targetLabel what HELLO announces
   * @param {string} o.deviceName the config's title
   */
  constructor({
    video,
    commands,
    simctl = null,
    udid,
    bundleId = null,
    appPid = null,
    guard = true,
    language = null,
    targetLabel,
    deviceName,
    guardIntervalMs = GUARD_INTERVAL_MS,
    inputSourceSettleMs = INPUT_SOURCE_SETTLE_MS,
    keyboardReadRetryMs = KEYBOARD_READ_RETRY_MS,
    ...tunnel
  }) {
    super(tunnel);
    Object.assign(this, { video, commands, simctl, udid, bundleId, appPid, targetLabel, deviceName, guardIntervalMs, inputSourceSettleMs, keyboardReadRetryMs });
    this.guard = guard && Boolean(bundleId) && Boolean(simctl);
    // Korean and English to switch between, or English only and never a switch.
    this.hangul = hasKoreanKeyboard(language);
    this.keyboardMode = null; // 'ko' | 'en' as last read or switched to; null until known

    this.frameSize = null; // pixels of the latest JPEG
    this.latestFrame = null;
    this.sentFrame = null; // the last frame the current socket got
    this.points = null; // the screen in points, which AXe's coordinates use
    this.sizeBusy = false;
    this.warnedLargeFrame = false;

    this.gesture = createGestureState();
    this.outbox = []; // input items not yet run; text and scrolls merge while they wait
    this.pumping = false;
    this.noticed = new Set();
    this.failedCodes = new Set();

    this.guardTimer = null;
    this.guardBusy = false;
    this.outside = null; // the pid the guard last pulled the share back from
  }

  /**
   * Reads the screen size, starts the video, then the tunnel; resolves when the share is over.
   *
   * @returns {Promise<{reason: string, status?: number, error?: Error}>} TunnelSession's reasons, or
   *   `device-exit` when stream-video ended
   */
  async run() {
    await this.readScreenSize();
    if (this.closed) return this.done;
    await this.video.start({
      onFrame: (jpeg) => this.onJpeg(jpeg),
      onClose: (error) => this.finish({ reason: 'device-exit', error }),
    });
    if (this.closed) return this.done;
    if (this.guard) this.guardTimer = setInterval(() => this.checkForeground(), this.guardIntervalMs);
    this.connect();
    return this.done;
  }

  // ── screen ───────────────────────────────────────────────────────────────

  onJpeg(jpeg) {
    if (this.closed) return;
    const size = jpegSize(jpeg);
    if (!size) return; // not a JPEG whose size can be told: the viewer could not place it
    if (jpeg.length > MAX_PAYLOAD_BYTES) {
      if (!this.warnedLargeFrame) {
        this.log('화면 한 장이 2MiB 를 넘어 건너뜁니다. --scale 을 낮추면 나아집니다.');
        this.warnedLargeFrame = true;
      }
      return;
    }
    const resized = this.frameSize !== null && (size.width !== this.frameSize.width || size.height !== this.frameSize.height);
    this.frameSize = size;
    this.latestFrame = jpeg;
    // A rotation: the size in points turned with it, and taps must use the new one.
    if (resized) this.readScreenSize();
    this.sendLatest();
  }

  /**
   * Sends the latest frame unless this socket already has it. stream-video captures at a fixed rate
   * whether or not anything changed, and a still screen comes out as byte-identical JPEGs (every one
   * of 53 in a row, measured): resending them would spend the server's bandwidth on nothing.
   * Buffer.equals compares lengths first, so a changed frame costs no byte comparison.
   */
  sendLatest() {
    if (!this.ready || !this.latestFrame) return;
    this.sendConfigIfChanged();
    if (this.sentConfig === null) return; // §4.2: the viewer learns the size first
    if (this.sentFrame && this.sentFrame.equals(this.latestFrame)) return;
    if (this.bufferedAmount > HIGH_WATER_BYTES) return; // §4.4: behind; a newer frame follows
    if (this.sendRaw(encodeFrame(KIND.SCREEN_FRAME, this.latestFrame))) this.sentFrame = this.latestFrame;
  }

  sendConfigIfChanged() {
    if (!this.ready || !this.frameSize) return;
    const { width, height } = this.frameSize;
    const config = JSON.stringify({ format: 'jpeg', width, height, url: this.bundleId ?? '', title: this.deviceName ?? '' });
    if (config === this.sentConfig) return;
    if (this.sendRaw(encodeFrame(KIND.SCREEN_CONFIG, config))) {
      this.sentConfig = config;
      this.sentFrame = null;
    }
  }

  /** `displays`: the screen size in points, at the start and after each rotation. */
  async readScreenSize() {
    if (this.sizeBusy) return;
    this.sizeBusy = true;
    try {
      const size = activeScreenSize(await this.commands.request(['displays']));
      if (size) this.points = size;
    } catch {
      // AXe hiccup: the next rotation or input asks again
    } finally {
      this.sizeBusy = false;
    }
  }

  // ── §13.3 app guard ──────────────────────────────────────────────────────

  /**
   * Compares the process in front with the shared app's. By pid, not by name: the home screen's
   * label is blank, and a name can be localised (설정 on screen, Settings in listapps).
   */
  async checkForeground() {
    if (this.closed || this.guardBusy) return;
    this.guardBusy = true;
    try {
      if (this.appPid === null) {
        // Not known (simctl printed no pid): launching brings the app forward and says it.
        this.appPid = await this.simctl.launch(this.udid, this.bundleId);
        return;
      }
      const reply = await this.commands.request(['describe-ui']);
      if (reply && !reply.didError) this.noteKeyboard(keyboardMode(reply));
      const front = reply?.didError ? null : foregroundApp(reply);
      // The screen could not be read: there is nothing to compare, so nothing is done.
      if (this.closed || front?.pid == null) return;
      if (front.pid === this.appPid) {
        this.outside = null;
        return;
      }
      if (this.outside !== front.pid) {
        const where = front.label ? shortLabel(front.label) : '홈 화면';
        this.log(`공유 중인 앱 밖(${where})으로 나가 ${this.bundleId} 를 다시 앞으로 올렸습니다.`);
        this.outside = front.pid;
      }
      // Relaunched after a crash or by hand, the app has a new pid; launch reports it.
      this.appPid = (await this.simctl.launch(this.udid, this.bundleId)) ?? this.appPid;
    } catch {
      // AXe or simctl hiccup: the next tick looks again
    } finally {
      this.guardBusy = false;
    }
  }

  // ── input ────────────────────────────────────────────────────────────────

  handleInput(input) {
    if (!this.points) this.readScreenSize(); // the first read failed; until one works there is nowhere to tap
    for (const item of mapIosInput(input, this.points, this.gesture, this.now(), { hangul: this.hangul })) {
      if (item.kind === 'notice') this.notice(item.notice);
      else if (!mergeItem(this.outbox.at(-1), item)) this.outbox.push(item);
    }
    this.pump();
  }

  /**
   * Runs the outbox in order, one AXe command at a time. The keyboard is switched here, as the
   * commands go out, because only here is it known which mode the simulator is in by then.
   */
  async pump() {
    if (this.pumping) return;
    this.pumping = true;
    try {
      while (this.outbox.length && !this.closed) {
        const item = this.outbox.shift();
        const needs = item.kind === 'text' && this.hangul && item.mode !== 'any' ? item.mode : null;
        // Text the keyboard is not set for would come out as the wrong letters, so it is not typed.
        if (needs && needs !== this.keyboardMode && !(await this.switchKeyboard(needs))) {
          this.notice('keyboard');
          continue;
        }
        for (const args of axeCommands(item)) await this.runCommand(args);
      }
    } finally {
      this.pumping = false;
    }
  }

  /** @returns {Promise<object|null>} the result, or null when AXe could not be run */
  async runCommand(args) {
    const reply = await this.commands.request(args).catch(() => null);
    if (reply?.didError) this.reportFailure(reply.error);
    return reply;
  }

  /**
   * Gets the simulator keyboard to `target` ('ko' or 'en'), reading it before and after — measured on
   * iOS 26.5 with a ko-KR simulator:
   *   - Caps Lock swaps Korean and English, and keys typed right after it come out in the new mode.
   *   - Ctrl+Space goes to the previously used input source, which can be the emoji keyboard, so it
   *     cannot be relied on to reach English. Caps Lock does nothing on the emoji keyboard, so there
   *     Ctrl+Space is used once to get back to a language first.
   *   - Where the Caps Lock language switch is turned off, Caps Lock turns caps lock on instead; the
   *     keyboard then still reads the same after KEYBOARD_READS_AFTER_SWITCH reads, and it is pressed
   *     again to undo that.
   *
   * @returns {Promise<boolean>} whether text for `target` may be typed now. True also when no
   *   keyboard is up: no field has focus, so there is nothing to switch and the keys go nowhere.
   */
  async switchKeyboard(target) {
    let now = await this.readKeyboard();
    if (now === 'emoji') {
      await this.runCommand(PREVIOUS_INPUT_SOURCE);
      await sleep(this.inputSourceSettleMs);
      now = await this.readKeyboard();
    }
    if (now !== target && (now === 'ko' || now === 'en')) {
      await this.runCommand(CAPS_LOCK);
      let after = await this.readKeyboard();
      for (let read = 1; read < KEYBOARD_READS_AFTER_SWITCH && after === now; read++) {
        await sleep(this.keyboardReadRetryMs);
        after = await this.readKeyboard();
      }
      if (after === now) await this.runCommand(CAPS_LOCK);
      now = after;
    }
    this.noteKeyboard(now);
    return now === target || now === null;
  }

  /** @returns {Promise<'ko'|'en'|'emoji'|null>} */
  async readKeyboard() {
    const reply = await this.commands.request(['describe-ui']).catch(() => null);
    return reply && !reply.didError ? keyboardMode(reply) : null;
  }

  /** Remembers a keyboard read; the emoji keyboard (or none) leaves the next text to read again. */
  noteKeyboard(mode) {
    this.keyboardMode = mode === 'ko' || mode === 'en' ? mode : null;
  }

  notice(name) {
    if (this.noticed.has(name) || !NOTICES[name]) return;
    this.noticed.add(name);
    this.log(NOTICES[name]);
  }

  /** Once per error code: a busy simulator would otherwise fill the terminal. */
  reportFailure(error) {
    const code = typeof error?.code === 'string' ? error.code : 'commandFailed';
    if (this.failedCodes.has(code)) return;
    this.failedCodes.add(code);
    const message = typeof error?.message === 'string' ? ` ${error.message.slice(0, 160)}` : '';
    this.log(`AXe 가 입력을 넣지 못했습니다 (${code}).${message}`);
  }

  // ── TunnelSession hooks ──────────────────────────────────────────────────

  helloPayload() {
    return { cliVersion: this.cliVersion, kind: 'IOS', target: this.targetLabel };
  }

  onReady() {
    // A new socket: the server has neither the size nor a frame of this screen yet. (connect()
    // already cleared sentConfig, so the config goes again, and with it the frame.)
    this.sendLatest();
  }

  onFinished() {
    clearInterval(this.guardTimer);
    this.outbox = [];
    this.commands.stop();
    Promise.resolve(this.video.stop()).catch(() => {});
  }
}
