/**
 * Finds and launches the Chrome that `qeploy share web` drives.
 *
 * Always with a brand-new profile directory (§8): the person given control can click anything in
 * that browser, so it must not hold the owner's cookies, saved passwords or extensions. The
 * directory is removed when the share ends.
 */
import { spawn } from 'node:child_process';
import { existsSync, rmSync } from 'node:fs';
import { mkdtemp, rm } from 'node:fs/promises';
import { homedir, tmpdir } from 'node:os';
import path from 'node:path';

export const NO_CHROME_MESSAGE =
  'Chrome 을 찾지 못했습니다. Google Chrome · Chromium · Microsoft Edge 중 하나를 설치하거나,\n' +
  '실행 파일 경로를 CHROME_PATH 환경변수로 알려주세요.\n' +
  '  예: export CHROME_PATH="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"';

const LINUX_NAMES = [
  'google-chrome',
  'google-chrome-stable',
  'chromium',
  'chromium-browser',
  'microsoft-edge',
  'microsoft-edge-stable',
];

/** Where Chrome-family browsers usually live, most preferred first. */
export function chromeCandidates({ env = process.env, platform = process.platform, home = homedir() } = {}) {
  if (platform === 'darwin') {
    const apps = [
      'Google Chrome.app/Contents/MacOS/Google Chrome',
      'Chromium.app/Contents/MacOS/Chromium',
      'Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
    ];
    return [
      ...apps.map((a) => path.posix.join('/Applications', a)),
      ...apps.map((a) => path.posix.join(home, 'Applications', a)),
    ];
  }
  if (platform === 'win32') {
    const w = path.win32;
    const programFiles = env.PROGRAMFILES ?? env.ProgramFiles ?? 'C:\\Program Files';
    const programFilesX86 = env['PROGRAMFILES(X86)'] ?? env['ProgramFiles(x86)'] ?? 'C:\\Program Files (x86)';
    const localAppData = env.LOCALAPPDATA ?? w.join(home, 'AppData', 'Local');
    return [
      w.join(programFiles, 'Google', 'Chrome', 'Application', 'chrome.exe'),
      w.join(programFilesX86, 'Google', 'Chrome', 'Application', 'chrome.exe'),
      w.join(localAppData, 'Google', 'Chrome', 'Application', 'chrome.exe'),
      w.join(localAppData, 'Chromium', 'Application', 'chrome.exe'),
      w.join(programFilesX86, 'Microsoft', 'Edge', 'Application', 'msedge.exe'),
      w.join(programFiles, 'Microsoft', 'Edge', 'Application', 'msedge.exe'),
    ];
  }
  // Linux and the other Unixes: whatever is on PATH, then the snap location PATH often lacks.
  const dirs = String(env.PATH ?? '')
    .split(':')
    .filter(Boolean);
  return [...LINUX_NAMES.flatMap((name) => dirs.map((d) => path.posix.join(d, name))), '/snap/bin/chromium'];
}

/**
 * @returns {string|null} the executable to run, or null when none is installed
 * @throws when CHROME_PATH is set but points nowhere — silently falling back would run a browser
 *   the user did not choose
 */
export function findChrome({ env = process.env, platform = process.platform, exists = existsSync, home } = {}) {
  if (env.CHROME_PATH) {
    if (exists(env.CHROME_PATH)) return env.CHROME_PATH;
    throw new Error(`CHROME_PATH 가 가리키는 파일이 없습니다: ${env.CHROME_PATH}`);
  }
  return chromeCandidates({ env, platform, ...(home ? { home } : {}) }).find((p) => exists(p)) ?? null;
}

/**
 * @param {{profileDir: string, headed?: boolean}} options
 */
export function chromeArgs({ profileDir, headed = false }) {
  return [
    `--user-data-dir=${profileDir}`,
    // Port 0: Chrome picks a free loopback port and prints it, so two shares never collide.
    '--remote-debugging-port=0',
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-extensions',
    // Keep the throwaway profile away from the OS keychain (macOS would otherwise prompt for it).
    '--password-store=basic',
    '--use-mock-keychain',
    ...(headed ? [] : ['--headless=new', '--window-size=1280,800']),
    'about:blank',
  ];
}

/** Chrome prints the browser endpoint on stderr once DevTools is ready. */
export function parseDevToolsUrl(text) {
  const m = /DevTools listening on (ws:\/\/\S+)/.exec(String(text));
  return m ? m[1] : null;
}

/**
 * @returns {Promise<{wsUrl: string, profileDir: string, onExit: (fn: Function) => void,
 *   close: () => Promise<void>, killNow: () => void}>}
 */
export async function launchChrome({
  executable,
  headed = false,
  spawnImpl = spawn,
  timeoutMs = 30000,
  makeTempDir = () => mkdtemp(path.join(tmpdir(), 'qeploy-share-')),
} = {}) {
  const profileDir = await makeTempDir();
  const removeProfile = () => rm(profileDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });

  let child;
  try {
    child = spawnImpl(executable, chromeArgs({ profileDir, headed }), { stdio: ['ignore', 'ignore', 'pipe'] });
  } catch (e) {
    await removeProfile();
    throw new Error(`Chrome 을 실행하지 못했습니다: ${e.message}`);
  }

  // A spawn failure (ENOENT) arrives as an 'error' event; one with no listener would crash the CLI
  // instead of reaching the message below.
  child.on('error', () => {});
  let exited = child.exitCode !== null;
  let closing = false;
  const exitListeners = [];
  child.once('exit', () => {
    exited = true;
    if (!closing) for (const fn of exitListeners) fn();
  });

  let wsUrl;
  try {
    wsUrl = await waitForDevTools(child, timeoutMs);
  } catch (e) {
    closing = true;
    if (!exited) child.kill('SIGKILL');
    await removeProfile();
    throw e;
  }
  // Keep draining stderr: a full pipe would block Chrome on its next log line.
  child.stderr?.resume();

  const waitExit = (ms) =>
    exited
      ? Promise.resolve(true)
      : new Promise((resolve) => {
          const timer = setTimeout(() => resolve(false), ms);
          child.once('exit', () => {
            clearTimeout(timer);
            resolve(true);
          });
        });

  /** No waiting, just make sure nothing is left behind. Synchronous so it can run on 'exit'. */
  const killNow = () => {
    closing = true;
    process.off('exit', killNow);
    if (!exited) child.kill('SIGKILL');
    try {
      rmSync(profileDir, { recursive: true, force: true });
    } catch {
      // Chrome may still hold files open for a moment; the OS temp cleaner gets the rest.
    }
  };
  // A child process outlives a parent that dies of an uncaught error or calls process.exit, which
  // would leave a headless Chrome — and the profile it writes to — running with nobody to stop it.
  process.once('exit', killNow);

  return {
    wsUrl,
    profileDir,
    process: child,
    onExit(fn) {
      exitListeners.push(fn);
    },
    async close() {
      closing = true;
      process.off('exit', killNow);
      if (!exited) {
        child.kill('SIGTERM');
        if (!(await waitExit(5000))) {
          child.kill('SIGKILL');
          await waitExit(2000);
        }
      }
      await removeProfile();
    },
    /** For a second Ctrl+C. */
    killNow,
  };
}

function waitForDevTools(child, timeoutMs) {
  return new Promise((resolve, reject) => {
    let seen = '';
    const finish = (fn, value) => {
      clearTimeout(timer);
      child.stderr?.off('data', onData);
      child.off('exit', onExit);
      child.off('error', onError);
      fn(value);
    };
    const tail = () => {
      const lines = seen.trim().split('\n').slice(-5).join('\n');
      return lines ? `\n${lines}` : '';
    };
    const onData = (chunk) => {
      seen = (seen + chunk).slice(-16384);
      const url = parseDevToolsUrl(seen);
      if (url) finish(resolve, url);
    };
    const onExit = (code, signal) =>
      finish(reject, new Error(`Chrome 이 시작하자마자 종료됐습니다 (${signal ?? `code ${code}`}).${tail()}`));
    const onError = (e) => finish(reject, new Error(`Chrome 을 실행하지 못했습니다: ${e.message}`));
    const timer = setTimeout(
      () => finish(reject, new Error(`Chrome 이 ${Math.round(timeoutMs / 1000)}초 안에 준비되지 않았습니다.${tail()}`)),
      timeoutMs
    );
    child.stderr?.setEncoding('utf8');
    child.stderr?.on('data', onData);
    child.once('exit', onExit);
    child.once('error', onError);
  });
}
