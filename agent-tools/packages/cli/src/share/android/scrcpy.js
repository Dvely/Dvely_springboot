/**
 * Runs the scrcpy server on the emulator and talks to it over an `adb forward` tunnel
 * (docs/device-preview-share-design.md §12.1). Only the server is used — it is the user's installed
 * scrcpy (Apache-2.0), so its version is the one this client announces.
 */
import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { connect as netConnect } from 'node:net';
import { delimiter, dirname, join } from 'node:path';
import { randomInt } from 'node:crypto';
import {
  CODEC_H264,
  FrameAssembler,
  VideoDemuxer,
  encodeResetVideo,
  isSupportedVersion,
  parseScrcpyVersion,
} from './scrcpy-protocol.js';

export const NO_SCRCPY_MESSAGE =
  'scrcpy 를 찾지 못했습니다. `brew install scrcpy`(macOS) 또는 배포판 패키지로 scrcpy 4.x·5.x 를 설치해 주세요. ' +
  '다른 위치라면 QEPLOY_SCRCPY_SERVER(scrcpy-server 파일)와 QEPLOY_SCRCPY_VERSION 을 지정합니다.';

const REMOTE_PATH = '/data/local/tmp/qeploy-scrcpy-server.jar';
const CONNECT_TIMEOUT_MS = 10000;
const CONNECT_RETRY_MS = 100;

const SERVER_CANDIDATES = [
  '/opt/homebrew/share/scrcpy/scrcpy-server',
  '/usr/local/share/scrcpy/scrcpy-server',
  '/usr/share/scrcpy/scrcpy-server',
  '/snap/scrcpy/current/usr/share/scrcpy/scrcpy-server',
];

function defaultVersionOf(executable) {
  try {
    return execFileSync(executable, ['--version'], { timeout: 5000, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  } catch {
    return '';
  }
}

/**
 * @returns {{serverPath: string, version: string}}
 * @throws {Error} with NO_SCRCPY_MESSAGE, or a version this client does not speak
 */
export function findScrcpy({ env = process.env, exists = existsSync, versionOf = defaultVersionOf } = {}) {
  let scrcpyBin = null;
  for (const dir of String(env.PATH ?? '').split(delimiter).filter(Boolean)) {
    const candidate = join(dir, 'scrcpy');
    if (exists(candidate)) {
      scrcpyBin = candidate;
      break;
    }
  }
  const candidates = [
    env.QEPLOY_SCRCPY_SERVER,
    env.SCRCPY_SERVER_PATH,
    scrcpyBin && join(dirname(dirname(scrcpyBin)), 'share', 'scrcpy', 'scrcpy-server'),
    ...SERVER_CANDIDATES,
  ].filter(Boolean);
  const serverPath = candidates.find((p) => exists(p));
  const version = env.QEPLOY_SCRCPY_VERSION || (scrcpyBin ? parseScrcpyVersion(versionOf(scrcpyBin)) : null);
  if (!serverPath || !version) throw new Error(NO_SCRCPY_MESSAGE);
  if (!isSupportedVersion(version)) {
    throw new Error(`scrcpy ${version} 는 지원하지 않습니다. scrcpy 4.x·5.x 를 설치해 주세요 (5.0.1 에서 확인).`);
  }
  return { serverPath, version };
}

export class ScrcpyDevice {
  /**
   * @param {object} o
   * @param {import('./adb.js').Adb} o.adb with the serial set
   * @param {string} o.serverPath
   * @param {string} o.version
   * @param {number} [o.maxSize]
   * @param {number} [o.bitRate]
   * @param {number} [o.maxFps]
   */
  constructor({ adb, serverPath, version, maxSize = 1280, bitRate = 2_000_000, maxFps = 30, connect = netConnect }) {
    Object.assign(this, { adb, serverPath, version, maxSize, bitRate, maxFps, connect });
    this.scid = randomInt(0, 0x7fffffff).toString(16).padStart(8, '0');
    this.forwardSpec = null;
    this.server = null;
    this.serverOutput = '';
    this.video = null;
    this.control = null;
    this.capturing = false;
    this.stopped = false;
  }

  /**
   * Pushes and starts the server, opens the video and control sockets.
   *
   * @param {object} h
   * @param {(size: {width: number, height: number}) => void} h.onSize a new capture session
   * @param {(codec: string) => void} h.onCodec the WebCodecs codec string
   * @param {(frame: {key: boolean, data: Buffer}) => void} h.onFrame
   * @param {(error?: Error) => void} h.onClose the video stream ended
   */
  async start({ onSize, onCodec, onFrame, onClose }) {
    await this.adb.push(this.serverPath, REMOTE_PATH);
    const port = Number.parseInt((await this.adb.run(['forward', 'tcp:0', `localabstract:scrcpy_${this.scid}`])).trim(), 10);
    if (!Number.isInteger(port) || port <= 0) throw new Error('adb forward 가 포트를 돌려주지 않았습니다.');
    this.forwardSpec = `tcp:${port}`;

    const options = [
      `scid=${this.scid}`,
      'log_level=warn',
      'video_codec=h264',
      'audio=false',
      'control=true',
      'tunnel_forward=true',
      `max_size=${this.maxSize}`,
      `video_bit_rate=${this.bitRate}`,
      `max_fps=${this.maxFps}`,
      'clipboard_autosync=false',
      'send_device_meta=false',
      'cleanup=true',
      'power_off_on_close=false',
      'show_touches=false',
      'stay_awake=false',
    ];
    this.server = this.adb.spawnShell(
      `CLASSPATH=${REMOTE_PATH} app_process / com.genymobile.scrcpy.Server ${this.version} ${options.join(' ')}`
    );
    const keep = (chunk) => {
      this.serverOutput = (this.serverOutput + chunk.toString('utf8')).slice(-2000);
    };
    this.server.stdout.on('data', keep);
    this.server.stderr.on('data', keep);
    let serverExit = null;
    this.server.on('exit', (code) => {
      serverExit = code ?? -1;
    });

    const { socket: video, rest } = await this.openFirstSocket(port, () => serverExit);
    this.video = video;
    this.control = await this.openSocket(port);
    this.control.on('data', () => {}); // device messages (clipboard, acks): not used
    this.control.on('error', () => {});

    const assembler = new FrameAssembler((frame) => {
      this.capturing = true;
      onFrame(frame);
    }, onCodec);
    const demuxer = new VideoDemuxer({
      onCodec: (id) => {
        if (id !== CODEC_H264) throw new Error(`scrcpy 가 H.264 가 아닌 영상(0x${id.toString(16)})을 보냅니다.`);
      },
      onSession: (size) => {
        assembler.reset();
        onSize(size);
      },
      onPacket: (packet) => assembler.packet(packet),
    });
    let closedWith = null;
    const feed = (chunk) => {
      try {
        demuxer.push(chunk);
      } catch (e) {
        closedWith = e;
        video.destroy();
      }
    };
    if (rest.length) feed(rest);
    video.on('data', feed);
    video.resume();
    video.on('error', (e) => {
      closedWith = closedWith ?? e;
    });
    video.on('close', () => {
      if (this.stopped) return;
      onClose(closedWith ?? new Error(this.describeServerEnd('영상 연결이 끊겼습니다')));
    });
  }

  /** The first socket gets one dummy byte once the server really accepted it (scrcpy's forward mode). */
  async openFirstSocket(port, exited) {
    const deadline = Date.now() + CONNECT_TIMEOUT_MS;
    for (;;) {
      if (this.stopped) throw new Error('중단했습니다.');
      if (exited() !== null) throw new Error(this.describeServerEnd('scrcpy 서버가 시작하지 못했습니다'));
      const result = await new Promise((resolve) => {
        const socket = this.connect({ port, host: '127.0.0.1' });
        const fail = () => {
          socket.removeListener('data', onData);
          resolve(null);
        };
        const onData = (chunk) => {
          // Paused until the caller has its own 'data' listener on, or bytes would be lost.
          socket.pause();
          socket.removeListener('error', fail);
          socket.removeListener('close', fail);
          resolve({ socket, rest: chunk.subarray(1) });
        };
        socket.once('data', onData);
        socket.once('error', fail);
        socket.once('close', fail);
      });
      if (result) return result;
      if (Date.now() > deadline) throw new Error(this.describeServerEnd('scrcpy 서버에 연결하지 못했습니다'));
      await new Promise((r) => setTimeout(r, CONNECT_RETRY_MS));
    }
  }

  openSocket(port) {
    return new Promise((resolve, reject) => {
      const socket = this.connect({ port, host: '127.0.0.1' });
      socket.once('connect', () => {
        socket.removeListener('error', reject);
        resolve(socket);
      });
      socket.once('error', reject);
    });
  }

  describeServerEnd(prefix) {
    const last = this.serverOutput
      .split(/\r?\n/)
      .map((l) => l.trim())
      .filter(Boolean)
      .slice(-2)
      .join(' / ');
    return last ? `${prefix}: ${last}` : `${prefix}.`;
  }

  /** @returns {boolean} whether it was written */
  sendControl(message) {
    if (this.stopped || !this.control || this.control.destroyed) return false;
    this.control.write(message);
    return true;
  }

  /**
   * Asks for a fresh key frame. Not before the first frame: scrcpy 5.0.1 has no capture to reset
   * until then and its control thread dies on the message (NullPointerException in
   * Controller.resetVideo, seen when the tunnel came up faster than the capture). The first frame of
   * a capture is a key frame anyway.
   */
  resetVideo() {
    if (!this.capturing) return false;
    return this.sendControl(encodeResetVideo());
  }

  async stop() {
    if (this.stopped) return;
    this.stopped = true;
    this.video?.destroy();
    this.control?.destroy();
    if (this.server && this.server.exitCode === null) this.server.kill('SIGTERM');
    if (this.forwardSpec) await this.adb.removeForward(this.forwardSpec);
  }
}
