/**
 * `qeploy share web <url>` (§7) and `qeploy share android` (§12) — share an app screen on this
 * machine through a link.
 *
 * Both run the same link lifecycle: check what can be checked locally, make the link, print it,
 * start the screen, and on any ending revoke the link unless the server already ended it. What
 * differs — Chrome over CDP, or the emulator over scrcpy — is a "source" with `prepare` (before the
 * link exists), `launch` (returns the running session) and `cleanup`.
 *
 * Everything that touches the outside world — Chrome, adb, sockets, signals, the terminal — comes
 * from a runtime object that tests replace through `deps.share`.
 */
import { readFileSync } from 'node:fs';
import { UsageError } from '../confirm.js';
import { terminalSafe } from '../render.js';
import { askControl } from './ask.js';
import { CdpConnection, attachFirstPage } from './cdp.js';
import { NO_CHROME_MESSAGE, findChrome, launchChrome } from './chrome.js';
import { checkTunnelUrl, parseTargetUrl, parseTtl, parseViewport } from './policy.js';
import { ShareSession } from './session.js';
import { Adb, NO_ADB_MESSAGE, PACKAGE_PATTERN, findAdb } from './android/adb.js';
import { ScrcpyDevice, findScrcpy } from './android/scrcpy.js';
import { AndroidShareSession } from './android/session.js';

const CLI_VERSION = JSON.parse(readFileSync(new URL('../../package.json', import.meta.url), 'utf8')).version;

/** Mirrors EXIT in index.js (imported from there it would be a cycle). */
const EXIT_OK = 0;
const EXIT_ERROR = 1;
const EXIT_AUTH = 3;

const DEFAULT_TTL_MINUTES = 60;
const MAX_LABEL_LENGTH = 255;
const REVOKE_TIMEOUT_MS = 5000;
const SIGNALS = ['SIGINT', 'SIGTERM', 'SIGHUP'];

export const defaultShareRuntime = {
  findChrome: ({ env }) => findChrome({ env }),
  launchChrome,
  connectCdp: (url) => CdpConnection.connect(url),
  attachPage: attachFirstPage,
  createSession: (options) => new ShareSession(options),
  findAdb: ({ env }) => findAdb({ env }),
  createAdb: (path) => new Adb(path),
  findScrcpy: ({ env }) => findScrcpy({ env }),
  createScrcpyDevice: (options) => new ScrcpyDevice(options),
  createAndroidSession: (options) => new AndroidShareSession(options),
  askControl,
  /** @returns {() => void} unsubscribe */
  onInterrupt(handler) {
    for (const signal of SIGNALS) process.on(signal, handler);
    return () => {
      for (const signal of SIGNALS) process.off(signal, handler);
    };
  },
  forceExit: (code) => process.exit(code),
};

/** How each way of ending is reported, and with which exit code. */
const ENDINGS = {
  interrupt: { code: EXIT_OK, text: '공유를 끝냈습니다.' },
  revoked: { code: EXIT_OK, text: '공유가 회수되어 끝났습니다.' },
  expired: { code: EXIT_OK, text: '공유 링크가 만료되어 끝났습니다.' },
  replaced: { code: EXIT_ERROR, text: '같은 링크로 다른 연결이 들어와 이 연결을 끝냅니다.' },
  auth: {
    code: EXIT_AUTH,
    text: '터널 인증에 실패했습니다. 토큰이 만료·폐기되었거나 WRITE 스코프가 아닙니다.',
  },
  'chrome-exit': { code: EXIT_ERROR, text: 'Chrome 이 종료되어 공유를 끝냅니다.' },
  'device-exit': { code: EXIT_ERROR, text: '에뮬레이터 화면 연결이 끊겨 공유를 끝냅니다.' },
  rejected: {
    code: EXIT_ERROR,
    text: '서버가 이 CLI 의 터널 연결을 받지 않습니다(HTTP 400). @qeploy/cli 를 최신으로 올려 주세요.',
  },
};

/** Ends where the link is already over or belongs to another connection — revoking is not ours. */
const KEEP_LINK = new Set(['revoked', 'expired', 'replaced', 'gone']);

function ending(result) {
  if (result.reason === 'gone') {
    return result.status === 410
      ? { code: EXIT_OK, text: '공유 링크가 이미 끝났습니다(회수 또는 만료).' }
      : { code: EXIT_ERROR, text: '공유 링크를 찾을 수 없어 끝냅니다.' };
  }
  const known = ENDINGS[result.reason] ?? { code: EXIT_ERROR, text: '공유를 끝냅니다.' };
  if (result.reason === 'device-exit' && result.error?.message) {
    return { ...known, text: `${known.text} (${result.error.message.slice(0, 200)})` };
  }
  return known;
}

export function linksText(share, ttlMinutes) {
  const safe = (v) => terminalSafe(String(v ?? ''));
  return [
    `보기 링크:    ${safe(share.viewUrl)}`,
    `              남에게 줄 링크입니다. 보기만 되고, 조작은 내가 승인해야 합니다. (${ttlMinutes}분 뒤 만료)`,
    `내 제어 링크: ${safe(share.controlUrl)}`,
    '              나만 쓰는 링크입니다. 열면 바로 조작할 수 있으니 남에게 주지 마세요.',
    'Ctrl+C 로 공유를 끝냅니다.',
  ].join('\n');
}

function withTimeout(promise, ms) {
  let timer;
  return Promise.race([
    promise,
    new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(`${ms / 1000}초 안에 끝나지 않았습니다`)), ms);
    }),
  ]).finally(() => clearTimeout(timer));
}

// ── sources ────────────────────────────────────────────────────────────────

/** §7: a dev web app in a throwaway Chrome. */
function webSource({ args, flags, rt, env }) {
  const targetLabel = String(args[1] ?? '').trim();
  const target = parseTargetUrl(targetLabel);
  const viewport = flags.viewport === undefined ? null : parseViewport(flags.viewport);
  let executable = null;
  return {
    apiKind: 'WEB',
    targetLabel,
    prepare() {
      // Before the link exists: a link nothing can serve would still take one of the five a user
      // may have open.
      executable = rt.findChrome({ env });
      if (!executable) throw new Error(NO_CHROME_MESSAGE);
    },
    async launch({ state, common }) {
      state.chrome = await rt.launchChrome({ executable, headed: Boolean(flags.headed), viewport });
      state.killNow = () => state.chrome?.killNow?.();
      state.conn = await rt.connectCdp(state.chrome.wsUrl);
      const { browser, page, targetId } = await rt.attachPage(state.conn);
      const session = rt.createSession({ target, targetLabel, viewport, browser, page, targetId, ...common });
      state.chrome.onExit(() => session.stop('chrome-exit'));
      return session;
    },
    async cleanup(state) {
      state.conn?.close();
      if (state.chrome) {
        await state.chrome.close().catch(() => {});
        return 'Chrome 과 임시 프로필을 지웠습니다.';
      }
      return null;
    },
  };
}

/** "1280", "2M", "800k" → a whole number within bounds. */
function parseAmount(raw, name, { min, max }) {
  const m = /^(\d+)(k|m)?$/i.exec(String(raw).trim());
  const n = m ? Number(m[1]) * (m[2] ? (m[2].toLowerCase() === 'm' ? 1_000_000 : 1_000) : 1) : NaN;
  if (!Number.isSafeInteger(n) || n < min || n > max) {
    throw new UsageError(`${name} 는 ${min}~${max} 사이의 수여야 합니다: ${raw}`);
  }
  return n;
}

/** §12: an app on an Android emulator, through the user's scrcpy server. */
function androidSource({ args, flags, rt, env, log }) {
  if (args.length > 1) {
    throw new UsageError('share android 는 위치 인자를 받지 않습니다. 기기는 --serial, 앱은 --app 으로 정합니다.');
  }
  const app = flags.app === undefined ? null : String(flags.app).trim();
  if (app !== null && !PACKAGE_PATTERN.test(app)) {
    throw new UsageError(`--app 에는 패키지 이름을 줍니다 (예: com.example.app): ${flags.app}`);
  }
  if (flags.serial !== undefined && !/^[A-Za-z0-9._:-]{1,64}$/.test(String(flags.serial))) {
    throw new UsageError(`--serial 값이 올바르지 않습니다: ${flags.serial}`);
  }
  const maxSize = flags.maxSize === undefined ? 1280 : parseAmount(flags.maxSize, '--max-size', { min: 320, max: 4096 });
  const bitRate =
    flags.bitRate === undefined ? 2_000_000 : parseAmount(flags.bitRate, '--bit-rate', { min: 200_000, max: 16_000_000 });
  const anyApp = Boolean(flags.anyApp);

  const prepared = { adb: null, scrcpy: null, pkg: null, deviceName: null };
  const source = {
    apiKind: 'ANDROID',
    targetLabel: '',
    async prepare() {
      const adbPath = rt.findAdb({ env });
      if (!adbPath) throw new Error(NO_ADB_MESSAGE);
      prepared.scrcpy = rt.findScrcpy({ env });

      const adb = rt.createAdb(adbPath);
      const online = (await adb.devices()).filter((d) => d.state === 'device');
      let serial = flags.serial === undefined ? null : String(flags.serial);
      if (serial && !online.some((d) => d.serial === serial)) {
        throw new Error(`연결된 기기 중에 ${serial} 이 없습니다. \`adb devices\` 로 확인해 주세요.`);
      }
      if (!serial) {
        const emulators = online.filter((d) => d.serial.startsWith('emulator-'));
        if (emulators.length === 0) {
          throw new Error(
            '실행 중인 Android 에뮬레이터가 없습니다. Android Studio 의 Device Manager 나 `emulator -avd <이름>` 으로 띄워 주세요.'
          );
        }
        if (emulators.length > 1) {
          throw new Error(`에뮬레이터가 여러 대입니다 (${emulators.map((d) => d.serial).join(', ')}). --serial 로 하나를 고르세요.`);
        }
        serial = emulators[0].serial;
      }
      const device = adb.withSerial(serial);
      if (!(await device.isEmulator())) {
        throw new Error(`${serial} 는 에뮬레이터가 아닙니다. 실제 기기는 공유하지 않습니다 (계정·사진이 담긴 기기라서).`);
      }

      let pkg = app;
      if (pkg) {
        if (!(await device.isInstalled(pkg))) throw new Error(`${serial} 에 ${pkg} 앱이 설치되어 있지 않습니다.`);
      } else {
        const top = await device.foregroundPackage();
        const home = await device.homePackage().catch(() => null);
        const onHome = !top || top === home || top === 'com.android.systemui';
        if (onHome && !anyApp) {
          throw new Error(
            '지금 에뮬레이터 앞에 떠 있는 앱이 없습니다(홈 화면). 공유할 앱을 연 뒤 다시 실행하거나 --app <패키지> 로 정하세요.'
          );
        }
        pkg = onHome ? null : top;
      }
      if (anyApp) {
        log(
          '--any-app: 앱을 묶지 않고 에뮬레이터 전체를 공유합니다. 조작권을 받은 사람은 다른 앱과, 에뮬레이터 Chrome 으로 내 localhost(10.0.2.2)에도 닿습니다.'
        );
      }

      prepared.adb = device;
      prepared.pkg = pkg;
      prepared.deviceName = await device.deviceName().catch(() => serial);
      source.targetLabel = `${prepared.deviceName} · ${pkg ?? '에뮬레이터 전체'}`;
    },
    async launch({ state, common }) {
      if (app) await prepared.adb.launchApp(app);
      const device = rt.createScrcpyDevice({
        adb: prepared.adb,
        serverPath: prepared.scrcpy.serverPath,
        version: prepared.scrcpy.version,
        maxSize,
        bitRate,
      });
      state.device = device;
      return rt.createAndroidSession({
        device,
        adb: prepared.adb,
        pkg: prepared.pkg,
        guard: !anyApp,
        targetLabel: source.targetLabel,
        deviceName: prepared.deviceName,
        ...common,
      });
    },
    async cleanup(state) {
      if (!state.device) return null;
      await Promise.resolve(state.device.stop()).catch(() => {});
      return '에뮬레이터의 scrcpy 서버를 끝냈습니다.';
    },
  };
  return source;
}

const SOURCES = { web: webSource, android: androidSource };

export const shareCommand = {
  usage:
    'qeploy share web <url> [--viewport 390x844] [--ttl 60] [--headed] [--json]\n' +
    '  qeploy share android [--app <패키지>] [--any-app] [--serial emulator-5554] [--max-size 1280] [--bit-rate 2M] [--ttl 60] [--json]',
  summary:
    '내 기기에서 도는 앱 화면(웹 앱, Android 에뮬레이터)을 링크로 공유한다. 보는 사람은 설치 없이 보고, 내가 승인하면 조작한다 (WRITE 스코프 토큰 필요)',
  write: true,
  async run({ client, args, flags, deps, out, err }) {
    const kind = args[0];
    const makeSource = Object.hasOwn(SOURCES, kind ?? '') ? SOURCES[kind] : null;
    if (!makeSource) {
      throw new UsageError(
        kind ? `지원하지 않는 공유 대상입니다: ${kind} (web 또는 android)` : '공유할 대상이 필요합니다 (web 또는 android).'
      );
    }
    const ttlMinutes = flags.ttl === undefined ? undefined : parseTtl(flags.ttl);
    const rt = { ...defaultShareRuntime, ...(deps.share ?? {}) };
    const env = deps.env ?? process.env;
    const log = (text) => err(terminalSafe(text));

    const source = makeSource({ args, flags, rt, env, log });
    await source.prepare();

    const state = {};
    let session = null;
    let stopReason = null;

    const interrupt = () => {
      if (stopReason) {
        // Second Ctrl+C: the user does not want to wait for the revoke or for the screen to close.
        state.killNow?.();
        rt.forceExit(130);
        return;
      }
      stopReason = 'interrupt';
      log('공유를 끝내는 중입니다… (한 번 더 누르면 바로 끝냅니다)');
      session?.stop('interrupt');
    };
    // Installed before the link is created, so Ctrl+C while it is being made still revokes it.
    const unsubscribe = rt.onInterrupt(interrupt);

    let share = null;
    let result = { reason: 'error' };
    let revoked = false;
    let cleaned = null;
    try {
      share = await client.createDeviceShare({
        kind: source.apiKind,
        targetLabel: source.targetLabel.slice(0, MAX_LABEL_LENGTH),
        ttlMinutes,
      });
      if (!share?.shareId || !share?.tunnelUrl) {
        throw new Error('서버 응답에 공유 정보(shareId·tunnelUrl)가 없습니다.');
      }
      const tunnelUrl = checkTunnelUrl(share.tunnelUrl);

      // --json is for scripts that read the links, so it goes out now, not when the share ends.
      out(flags.json ? JSON.stringify(share, null, 2) : linksText(share, ttlMinutes ?? DEFAULT_TTL_MINUTES));

      if (stopReason) {
        result = { reason: stopReason };
      } else {
        session = await source.launch({
          state,
          common: {
            tunnelUrl,
            token: client.token,
            cliVersion: CLI_VERSION,
            askControl: (question) => rt.askControl(question, { onInterrupt: interrupt }),
            log,
          },
        });
        if (stopReason) session.stop(stopReason);
        result = await session.run();
      }
    } finally {
      unsubscribe();
      if (share?.shareId && !KEEP_LINK.has(result.reason)) {
        try {
          await withTimeout(Promise.resolve(client.revokeDeviceShare(share.shareId)), REVOKE_TIMEOUT_MS);
          revoked = true;
        } catch (e) {
          log(`링크를 회수하지 못했습니다 (${e.message}). 웹에서 회수하거나 만료를 기다려 주세요.`);
        }
      }
      if (session && result.reason === 'error') session.stop('error');
      cleaned = await source.cleanup(state);
    }

    const { code, text } = ending(result);
    log([text, revoked ? '링크를 회수했습니다.' : null, cleaned].filter(Boolean).join(' '));
    return { printed: true, exitCode: code, data: share };
  },
};
