/**
 * The adb calls `share android` makes (docs/device-preview-share-design.md §12.1, §12.5): finding
 * adb and the emulator, telling an emulator from a real phone, and which app is in front.
 */
import { execFile, spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { homedir } from 'node:os';
import { delimiter, join } from 'node:path';

const EXEC_TIMEOUT_MS = 15000;

export const NO_ADB_MESSAGE =
  'adb 를 찾지 못했습니다. Android SDK platform-tools 를 설치하거나 ANDROID_HOME 을 SDK 경로로 지정해 주세요.';

/** Packages an app may hand the screen to without leaving the share: permission dialogs. */
export const ALLOWED_SYSTEM_PACKAGES = Object.freeze([
  'com.google.android.permissioncontroller',
  'com.android.permissioncontroller',
]);

/**
 * @returns {string|null}
 */
export function findAdb({ env = process.env, platform = process.platform, home = homedir(), exists = existsSync } = {}) {
  const exe = platform === 'win32' ? 'adb.exe' : 'adb';
  const sdkRoots = [env.ANDROID_HOME, env.ANDROID_SDK_ROOT];
  if (platform === 'darwin') sdkRoots.push(join(home, 'Library', 'Android', 'sdk'));
  else if (platform === 'win32') sdkRoots.push(env.LOCALAPPDATA && join(env.LOCALAPPDATA, 'Android', 'Sdk'));
  else sdkRoots.push(join(home, 'Android', 'Sdk'));
  for (const root of sdkRoots.filter(Boolean)) {
    const candidate = join(root, 'platform-tools', exe);
    if (exists(candidate)) return candidate;
  }
  for (const dir of String(env.PATH ?? '').split(delimiter).filter(Boolean)) {
    const candidate = join(dir, exe);
    if (exists(candidate)) return candidate;
  }
  return null;
}

/** `adb devices` → [{serial, state}] */
export function parseDevices(output) {
  return String(output ?? '')
    .split(/\r?\n/)
    .slice(1)
    .map((line) => line.trim().split(/\s+/))
    .filter((parts) => parts.length >= 2 && parts[0])
    .map(([serial, state]) => ({ serial, state }));
}

/** The package of `topResumedActivity=ActivityRecord{… u0 com.example/.Main t12}`. */
export function parseForegroundPackage(output) {
  const m = /(?:topResumedActivity|ResumedActivity)[=:]\s*ActivityRecord\{\S+\s+u\d+\s+([A-Za-z0-9_.]+)\//.exec(String(output ?? ''));
  return m ? m[1] : null;
}

/** The last line of `cmd package resolve-activity --brief`: `com.example/.Main`. */
export function parseResolvedComponent(output) {
  const lines = String(output ?? '')
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter(Boolean);
  const last = lines.at(-1) ?? '';
  return /^[A-Za-z0-9_.]+\/[A-Za-z0-9_.$]+$/.test(last) ? last : null;
}

export const PACKAGE_PATTERN = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/;

/**
 * Whether the emulator running `avdName` has a window, from the local process list (`ps -Ao args=`).
 *
 * §12.5, measured on emulator 37.2 (2026-11-13): with a window, what the guest copies lands on the
 * Mac clipboard (the emulator's "Enable clipboard sharing", on by default); started with
 * `-no-window`, nothing crossed in either direction. Android Studio's embedded emulator
 * (`-qt-hide-window`) counts as having one — Studio passes the clipboard on itself.
 *
 * @returns {boolean|null} null when no local emulator process runs that AVD (another user, remote)
 */
export function emulatorHasWindow(psOutput, avdName) {
  if (!avdName) return null;
  const lines = String(psOutput ?? '')
    .split(/\r?\n/)
    .filter((l) => /(^|\/)(qemu-system-[\w-]+|emulator)(\s|$)/.test(l.trim().split(/\s+/)[0] ?? ''));
  const runs = lines.filter((l) => {
    const args = l.trim().split(/\s+/);
    return args.some((a, i) => (a === '-avd' && args[i + 1] === avdName) || a === `@${avdName}`);
  });
  if (runs.length === 0) return null;
  return !runs.some((l) => l.trim().split(/\s+/).includes('-no-window'));
}

function localProcessList() {
  return new Promise((resolve) => {
    execFile('/bin/ps', ['-Ao', 'args='], { timeout: 5000, maxBuffer: 8 * 1024 * 1024 }, (error, stdout) =>
      resolve(error ? '' : String(stdout))
    );
  });
}

export class Adb {
  /**
   * @param {string} path adb executable
   * @param {string|null} [serial]
   */
  constructor(path, serial = null) {
    this.path = path;
    this.serial = serial;
  }

  withSerial(serial) {
    return new Adb(this.path, serial);
  }

  args(rest) {
    return this.serial ? ['-s', this.serial, ...rest] : rest;
  }

  /** @returns {Promise<string>} stdout */
  run(rest, { timeout = EXEC_TIMEOUT_MS } = {}) {
    return new Promise((resolve, reject) => {
      execFile(this.path, this.args(rest), { timeout, maxBuffer: 8 * 1024 * 1024 }, (error, stdout, stderr) => {
        if (error) {
          const detail = String(stderr || error.message).trim().split('\n').at(-1);
          reject(new Error(`adb ${rest.slice(0, 2).join(' ')} 실패: ${detail}`));
          return;
        }
        resolve(String(stdout));
      });
    });
  }

  shell(command, options) {
    return this.run(['shell', command], options);
  }

  /** A long-running `adb shell …`, its output going to the callbacks. */
  spawnShell(command) {
    return spawn(this.path, this.args(['shell', command]), { stdio: ['ignore', 'pipe', 'pipe'] });
  }

  async devices() {
    return parseDevices(await this.run(['devices']));
  }

  /** §12.5: only emulators — a real phone carries the owner's accounts and photos. */
  async isEmulator() {
    const out = await this.shell('getprop ro.boot.qemu; getprop ro.kernel.qemu');
    return out.split(/\r?\n/).some((l) => l.trim() === '1');
  }

  /** @returns {Promise<boolean|null>} see emulatorHasWindow */
  async hasWindow(ps = localProcessList) {
    const avd = (await this.shell('getprop ro.boot.qemu.avd_name')).trim();
    return emulatorHasWindow(await ps(), avd);
  }

  async deviceName() {
    const out = await this.shell('getprop ro.boot.qemu.avd_name; getprop ro.product.model');
    return (
      out
        .split(/\r?\n/)
        .map((l) => l.trim())
        .find(Boolean) ?? this.serial ?? 'Android'
    );
  }

  async foregroundPackage() {
    return parseForegroundPackage(await this.shell('dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity'));
  }

  async homePackage() {
    const out = await this.shell('cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME');
    return parseResolvedComponent(out)?.split('/')[0] ?? null;
  }

  async isInstalled(pkg) {
    if (!PACKAGE_PATTERN.test(pkg)) return false;
    const out = await this.shell(`pm path ${pkg}`).catch(() => '');
    return out.includes('package:');
  }

  /** Brings the app's task to the front the way the launcher does (or starts it). */
  async launchApp(pkg) {
    if (!PACKAGE_PATTERN.test(pkg)) throw new Error(`패키지 이름이 올바르지 않습니다: ${pkg}`);
    const component = parseResolvedComponent(
      await this.shell(`cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER ${pkg}`)
    );
    if (!component) throw new Error(`${pkg} 에 실행할 화면(런처 액티비티)이 없습니다.`);
    // FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_RESET_TASK_IF_NEEDED, as a launcher tap.
    await this.shell(`am start -n ${component} -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000`);
  }

  async push(local, remote) {
    await this.run(['push', local, remote], { timeout: 60000 });
  }

  async forward(local, remote) {
    await this.run(['forward', local, remote]);
  }

  async removeForward(local) {
    await this.run(['forward', '--remove', local]).catch(() => {});
  }
}
