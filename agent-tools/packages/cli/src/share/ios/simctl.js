/**
 * The Apple tools `share ios` calls (docs/device-preview-share-design.md §13.3, §13.4): which
 * simulators are booted, which apps they have and under what names, launching the shared app (and
 * its process id, which the app guard follows), the simulator's language (whether it has a Korean
 * keyboard), and whether Simulator syncs its pasteboard with the Mac's.
 *
 * The booted list is also the "simulators only" guard. AXe drives real iPhones too, by the same
 * `--udid`, so a UDID is only accepted when `simctl` lists it as a booted simulator.
 */
import { execFile } from 'node:child_process';

const EXEC_TIMEOUT_MS = 15000;
const MAX_OUTPUT_BYTES = 16 * 1024 * 1024;

/** Apple bundle IDs: letters, digits, `.` and `-` (`_` too, as some older apps have it). Never a leading `-`. */
export const BUNDLE_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,254}$/;

/** A simulator UDID as typed by the user; membership in the booted list is the real check. */
export const UDID_PATTERN = /^[A-Za-z0-9-]{1,64}$/;

/**
 * Runs a tool and resolves with its stdout. `input` is written to its stdin, which is closed either
 * way so nothing waits on it.
 *
 * @returns {Promise<string>}
 * @throws {Error} with the last line of stderr, and the stdout read so far as `error.stdout`
 *   (AXe prints its JSON error envelope there)
 */
export function runTool(file, args, { input, timeout = EXEC_TIMEOUT_MS } = {}) {
  return new Promise((resolve, reject) => {
    const child = execFile(file, args, { timeout, maxBuffer: MAX_OUTPUT_BYTES, encoding: 'utf8' }, (error, stdout, stderr) => {
      if (error) {
        const detail = String(stderr || error.message).trim().split('\n').at(-1);
        const failure = new Error(`${[file, ...args.slice(0, 2)].join(' ')} 실패: ${detail}`);
        failure.stdout = String(stdout ?? '');
        reject(failure);
        return;
      }
      resolve(String(stdout));
    });
    child.stdin?.on('error', () => {}); // a tool that exits without reading its input
    child.stdin?.end(input);
  });
}

/** `com.apple.CoreSimulator.SimRuntime.iOS-18-2` → `iOS 18.2` */
function runtimeName(key) {
  const m = /SimRuntime\.([A-Za-z]+)-([\d-]+)$/.exec(key);
  return m ? `${m[1]} ${m[2].replaceAll('-', '.')}` : key;
}

/**
 * `xcrun simctl list devices booted -j` → the booted iOS simulators. Other runtimes (a paired watch,
 * visionOS) are left out: they are not what AXe shares.
 *
 * @returns {{udid: string, name: string, runtime: string}[]}
 */
export function parseBootedSimulators(json) {
  const data = typeof json === 'string' ? JSON.parse(json) : json;
  const found = [];
  for (const [runtime, devices] of Object.entries(data?.devices ?? {})) {
    if (!/SimRuntime\.iOS-/.test(runtime) || !Array.isArray(devices)) continue;
    for (const device of devices) {
      if (device?.state !== 'Booted' || typeof device.udid !== 'string') continue;
      found.push({ udid: device.udid, name: String(device.name ?? device.udid), runtime: runtimeName(runtime) });
    }
  }
  return found;
}

/**
 * `simctl listapps` (converted to JSON by plutil) → apps by bundle ID. Keys are bundle IDs;
 * `CFBundleDisplayName` is the name under the icon, `CFBundleName` the fallback.
 *
 * @returns {Map<string, {bundleId: string, displayName: string|null, name: string|null, user: boolean}>}
 */
export function parseAppList(json) {
  const data = typeof json === 'string' ? JSON.parse(json) : json;
  const apps = new Map();
  if (!data || typeof data !== 'object') return apps;
  const text = (v) => (typeof v === 'string' && v.trim() ? v.trim() : null);
  for (const [bundleId, info] of Object.entries(data)) {
    if (!info || typeof info !== 'object') continue;
    apps.set(bundleId, {
      bundleId,
      displayName: text(info.CFBundleDisplayName),
      name: text(info.CFBundleName),
      user: info.ApplicationType === 'User',
    });
  }
  return apps;
}

/** How a name on screen is compared with an app's names: the same text, ignoring case and spacing at the ends. */
export function appNameKey(name) {
  return String(name ?? '').normalize('NFC').trim().toLowerCase();
}

/** The names an app may show as its accessibility root label. */
export function appNamesOf(app) {
  return [app?.displayName, app?.name].filter(Boolean);
}

/**
 * The home screen. listapps lists it like an app ("SpringBoard"), and on the home screen describe-ui
 * names it, but sharing it would be sharing the whole simulator.
 */
export const HOME_SCREEN_BUNDLE_ID = 'com.apple.springboard';

/**
 * The app whose display name (or bundle name) is `label`, never the home screen. Several apps can
 * share a name; one the user installed wins over a system app, since that is what is being developed.
 */
export function findAppByName(apps, label) {
  const want = appNameKey(label);
  if (!want) return null;
  const matches = [...apps.values()].filter(
    (app) => app.bundleId !== HOME_SCREEN_BUNDLE_ID && appNamesOf(app).some((n) => appNameKey(n) === want)
  );
  matches.sort((a, b) => Number(b.user) - Number(a.user));
  return matches[0] ?? null;
}

/** `simctl launch` prints `com.apple.mobilesafari: 34990`, the same id again when it was running. */
export function parseLaunchPid(output) {
  const m = /:\s*(\d+)\s*$/m.exec(String(output ?? ''));
  return m ? Number(m[1]) : null;
}

/** `defaults read -g AppleLanguages` prints an old-style array, `(\n    "ko-KR"\n)`. */
export function parseAppleLanguages(output) {
  return [...String(output ?? '').matchAll(/"?([A-Za-z]{2,3}(?:[-_][A-Za-z0-9]+)*)"?\s*[,)\n]/g)].map((m) => m[1]);
}

/**
 * Whether the simulator has a Korean keyboard to type Hangul with: its first AppleLanguages entry is
 * Korean. Simulators take the Mac's language when they are created, and with it its keyboard.
 */
export function hasKoreanKeyboard(language) {
  return String(language ?? '').toLowerCase().startsWith('ko');
}

/**
 * `defaults read com.apple.iphonesimulator PasteboardAutomaticSync`: `1` or `0`. Simulator syncs by
 * default, so anything but an explicit off counts as on.
 */
export function parsePasteboardSync(output) {
  const value = String(output ?? '').trim().toLowerCase();
  return !(value === '0' || value === 'false' || value === 'no');
}

export class Simctl {
  /** @param {{run?: typeof runTool}} [o] tests replace `run` */
  constructor({ run = runTool } = {}) {
    this.run = run;
  }

  async bootedSimulators() {
    let out;
    try {
      out = await this.run('xcrun', ['simctl', 'list', 'devices', 'booted', '-j']);
    } catch (e) {
      throw new Error(`시뮬레이터 목록을 읽지 못했습니다. Xcode 를 설치하고 한 번 실행해 주세요. (${e.message})`);
    }
    return parseBootedSimulators(out);
  }

  /** listapps prints an old-style (text) plist; plutil turns it into JSON. */
  async apps(udid) {
    const plist = await this.run('xcrun', ['simctl', 'listapps', udid]);
    return parseAppList(await this.run('plutil', ['-convert', 'json', '-o', '-', '-'], { input: plist }));
  }

  /**
   * Starts the app, or brings it to the front when it is already running.
   *
   * @returns {Promise<number|null>} its process id
   */
  async launch(udid, bundleId) {
    if (!BUNDLE_ID_PATTERN.test(bundleId)) throw new Error(`번들 ID 가 올바르지 않습니다: ${bundleId}`);
    return parseLaunchPid(await this.run('xcrun', ['simctl', 'launch', udid, bundleId]));
  }

  /** @returns {Promise<string[]>} the simulator's languages, most preferred first; empty when unread */
  async languages(udid) {
    try {
      return parseAppleLanguages(await this.run('xcrun', ['simctl', 'spawn', udid, 'defaults', 'read', '-g', 'AppleLanguages']));
    } catch {
      return [];
    }
  }

  /** @returns {Promise<boolean>} whether Simulator syncs its pasteboard with the Mac's (§13.4) */
  async pasteboardSyncOn() {
    try {
      return parsePasteboardSync(await this.run('defaults', ['read', 'com.apple.iphonesimulator', 'PasteboardAutomaticSync']));
    } catch {
      return true; // never set: Simulator's default, which is on
    }
  }
}
