/**
 * Runs the user's AXe (https://github.com/cameroncooke/AXe, MIT) for `share ios`
 * (docs/device-preview-share-design.md §13.1). Xcode's simctl cannot put a tap or a key into a
 * simulator, AXe can. Nothing of AXe ships with this CLI; it is found on the machine.
 *
 *   screen  `axe stream-video --format raw`   one long-running process, JPEGs on stdout  (AxeVideo)
 *   input   `axe --output json <command>`     one process per command, strictly in turn   (AxeCommands)
 *
 * AXe 2.0.4 has no long-lived command server, so each tap or key is its own process: about 0.3 s
 * each once warm, 1.9 s for the very first (measured). Commands are run one after another, never
 * side by side, so a typed word or a tap-then-type arrives in the order it was made.
 */
import { execFileSync, spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { homedir } from 'node:os';
import { delimiter, join } from 'node:path';
import { RawJpegDemuxer, isSupportedAxeVersion, parseAxeJson, parseAxeVersion } from './axe-protocol.js';
import { runTool } from './simctl.js';

export const NO_AXE_MESSAGE =
  'AXe 를 찾지 못했습니다. iOS 시뮬레이터에 화면을 받고 탭·입력을 넣는 데 필요합니다. ' +
  '`brew install cameroncooke/axe/axe` 로 AXe 2.x 를 설치해 주세요. 다른 위치라면 QEPLOY_AXE 에 실행 파일 경로를 지정합니다.';

/** Where Homebrew links it, for a PATH that lacks them (a GUI-launched shell, a CI step). */
const HOMEBREW_BIN_DIRS = ['/opt/homebrew/bin', '/usr/local/bin'];

/** §13.1: stream-video's JPEG quality. */
export const JPEG_QUALITY = 70;
/** The longest input is a 5 s hold or draw, after a cold start of about 2 s. */
export const COMMAND_TIMEOUT_MS = 20000;

function defaultVersionOf(executable) {
  try {
    return execFileSync(executable, ['--version'], { timeout: 5000, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  } catch {
    return '';
  }
}

/**
 * The first `axe` that is AXe 2.x: QEPLOY_AXE if set, else PATH, then `~/.local/bin` (a manual
 * install) and Homebrew's bin directories.
 *
 * The version is checked on each candidate rather than on the first, because `axe` is also the
 * command of Deque's accessibility checker (`@axe-core/cli`), which an npm global install puts on
 * PATH too.
 *
 * @returns {{path: string, version: string}}
 * @throws {Error} NO_AXE_MESSAGE, or why the axe found is not usable
 */
export function findAxe({ env = process.env, home = homedir(), exists = existsSync, versionOf = defaultVersionOf } = {}) {
  let candidates;
  if (env.QEPLOY_AXE) {
    if (!exists(env.QEPLOY_AXE)) throw new Error(`QEPLOY_AXE 가 가리키는 파일이 없습니다: ${env.QEPLOY_AXE}`);
    candidates = [env.QEPLOY_AXE];
  } else {
    const dirs = [...String(env.PATH ?? '').split(delimiter).filter(Boolean), join(home, '.local', 'bin'), ...HOMEBREW_BIN_DIRS];
    candidates = [...new Set(dirs.map((dir) => join(dir, 'axe')))].filter((p) => exists(p));
  }
  if (candidates.length === 0) throw new Error(NO_AXE_MESSAGE);

  let first = null;
  for (const path of candidates) {
    const version = parseAxeVersion(versionOf(path));
    if (version && isSupportedAxeVersion(version)) return { path, version };
    first ??= { path, version };
  }
  if (!first.version) {
    throw new Error(`${first.path} 의 버전을 읽지 못했습니다 (axe --version). AXe 2.x 를 설치해 주세요: brew install cameroncooke/axe/axe`);
  }
  throw new Error(
    `${first.path} 는 AXe 2.x 가 아닙니다 (버전 ${first.version}). ` +
      'brew upgrade cameroncooke/axe/axe 로 올리거나, 다른 프로그램의 axe(@axe-core/cli 등)라면 QEPLOY_AXE 로 AXe 경로를 지정하세요.'
  );
}

export class Axe {
  /**
   * @param {string} path the axe executable
   * @param {{run?: Function, spawnImpl?: Function}} [o] tests replace both
   */
  constructor(path, { run = runTool, spawnImpl = spawn } = {}) {
    Object.assign(this, { path, run, spawnImpl });
  }

  /** @param {{udid: string, fps: number, scale: number}} options */
  video(options) {
    return new AxeVideo({ axePath: this.path, spawnImpl: this.spawnImpl, ...options });
  }

  commands(udid) {
    return new AxeCommands({ axePath: this.path, udid, run: this.run });
  }
}

/**
 * `axe --output json <command> --udid <UDID> …`, one process each, one at a time.
 *
 * Arguments go as an argv array (no shell), so typed text needs no quoting. `--udid` is put right
 * after the command name, ahead of the command's own arguments: `type` ends its options with `--`
 * (so text starting with `-` is not read as an option, measured), and anything after `--` would be
 * taken as text.
 */
export class AxeCommands {
  constructor({ axePath, udid, run = runTool, timeoutMs = COMMAND_TIMEOUT_MS }) {
    Object.assign(this, { axePath, udid, run, timeoutMs });
    this.chain = Promise.resolve();
    this.stopped = false;
  }

  /**
   * Runs one command once every earlier one has finished.
   *
   * @param {string[]} args the command and its arguments, without `--udid`, e.g. `['tap', '-x', '10', '-y', '20']`
   * @returns {Promise<object>} the result envelope; `didError` true is still a result
   * @throws {Error} stopped, AXe could not be run, or it printed no envelope
   */
  request(args) {
    const run = this.chain.then(() => this.exchange(args));
    this.chain = run.catch(() => {});
    return run;
  }

  async exchange([command, ...rest]) {
    if (this.stopped) throw new Error('AXe 입력을 끝냈습니다.');
    let stdout;
    try {
      stdout = await this.run(this.axePath, ['--output', 'json', command, '--udid', this.udid, ...rest], { timeout: this.timeoutMs });
    } catch (e) {
      // A failed command exits nonzero but still prints its envelope; that is an answer, not a crash.
      const reply = parseAxeJson(e.stdout);
      if (reply) return reply;
      throw e;
    }
    const reply = parseAxeJson(stdout);
    if (!reply) throw new Error(`AXe ${command} 의 출력을 읽지 못했습니다.`);
    return reply;
  }

  /** Later requests are refused; one already running finishes (5 s at most). */
  stop() {
    this.stopped = true;
  }
}

/**
 * `axe stream-video --format raw`: the simulator screen as JPEGs, at most `fps` a second.
 *
 * Spawned in its own process group (`detached`). Ctrl+C in the terminal goes to the whole foreground
 * group; were stream-video in it, it would quit on the same keypress and the share would end as "the
 * simulator went away" rather than as the owner's Ctrl+C. It still ends with the CLI: on its next
 * write to the closed pipe.
 */
export class AxeVideo {
  constructor({ axePath, udid, fps, scale, quality = JPEG_QUALITY, spawnImpl = spawn }) {
    Object.assign(this, { axePath, udid, fps, scale, quality, spawnImpl });
    this.child = null;
    this.stopped = false;
  }

  args() {
    return [
      'stream-video',
      '--udid',
      this.udid,
      '--format',
      'raw',
      '--fps',
      String(this.fps),
      '--scale',
      String(this.scale),
      '--quality',
      String(this.quality),
    ];
  }

  /**
   * @param {object} h
   * @param {(jpeg: Buffer) => void} h.onFrame
   * @param {(error: Error) => void} h.onClose the stream ended without stop()
   */
  async start({ onFrame, onClose }) {
    const child = this.spawnImpl(this.axePath, this.args(), { stdio: ['ignore', 'pipe', 'pipe'], detached: true });
    this.child = child;

    // The end of stderr says why it stopped. Its once-a-second frame counts say nothing about that.
    let tail = '';
    child.stderr.setEncoding('utf8');
    child.stderr.on('data', (text) => {
      tail = (tail + text).slice(-2000);
    });
    const describeEnd = () => {
      const last = tail
        .split(/\r?\n/)
        .map((l) => l.trim())
        .filter((l) => l && !/^(Captured|Streamed) \d+ frames/.test(l))
        .slice(-2)
        .join(' / ');
      return `AXe 화면 전송(stream-video)이 끝났습니다${last ? `: ${last}` : '.'}`;
    };

    let closedWith = null;
    const demuxer = new RawJpegDemuxer(onFrame);
    child.stdout.on('data', (chunk) => {
      if (closedWith) return;
      try {
        demuxer.push(chunk);
      } catch (e) {
        closedWith = e;
        child.kill('SIGTERM');
      }
    });
    // A spawn failure (ENOENT) arrives as 'error'; without a listener it would crash the CLI.
    child.on('error', (e) => {
      closedWith ??= e;
    });
    child.on('close', () => {
      if (this.stopped) return;
      this.stopped = true;
      onClose(closedWith ?? new Error(describeEnd()));
    });
  }

  async stop() {
    if (this.stopped) return;
    this.stopped = true;
    this.child?.kill('SIGTERM');
  }
}
