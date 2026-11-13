import assert from 'node:assert/strict';
import { afterEach, describe, it } from 'node:test';
import { once } from 'node:events';
import { WebSocketServer } from 'ws';
import { KIND, decodeFrame, encodeJsonFrame, parseJsonPayload } from '../src/share/protocol.js';
import { codecString, nalUnits } from '../src/share/android/h264.js';
import {
  FrameAssembler,
  VideoDemuxer,
  encodeKeycode,
  encodeResetVideo,
  encodeScroll,
  encodeSetClipboard,
  encodeText,
  encodeTouch,
  isSupportedVersion,
  parseScrcpyVersion,
  screenFramePayload,
} from '../src/share/android/scrcpy-protocol.js';
import { KEYCODE, META, createTouchState, mapAndroidInput } from '../src/share/android/input.js';
import { findAdb, parseDevices, parseForegroundPackage, parseResolvedComponent } from '../src/share/android/adb.js';
import { findScrcpy } from '../src/share/android/scrcpy.js';
import { AndroidShareSession } from '../src/share/android/session.js';
import { run, EXIT } from '../src/index.js';

const cleanups = [];
afterEach(async () => {
  while (cleanups.length) await cleanups.pop()();
});

async function until(predicate, ms = 3000) {
  const end = Date.now() + ms;
  while (!predicate()) {
    if (Date.now() > end) throw new Error('timed out waiting for a condition');
    await new Promise((r) => setTimeout(r, 5));
  }
}

// What the emulator really sent (scrcpy 5.0.1, API 35): the head of the config packet.
const SPS = Buffer.from('000000016742c029', 'hex');
const PPS = Buffer.from('0000000168ce3c80', 'hex');
const CONFIG = Buffer.concat([SPS, Buffer.from([0x8d, 0x8d, 0x40]), PPS]);
const IDR = Buffer.from('0000000165888400', 'hex');
const DELTA = Buffer.from('00000001419a2244', 'hex');

/** A scrcpy media packet header: [0 | config | key | pts][size]. */
function mediaPacket({ config = false, key = false, pts = 1000n, data }) {
  const header = Buffer.alloc(12);
  let flags = config ? 1n << 62n : pts;
  if (key && !config) flags |= 1n << 61n;
  header.writeBigUInt64BE(flags, 0);
  header.writeUInt32BE(data.length, 8);
  return Buffer.concat([header, data]);
}

function sessionPacket(width, height) {
  const p = Buffer.alloc(12);
  p.writeUInt32BE(0x80000000, 0);
  p.writeUInt32BE(width, 4);
  p.writeUInt32BE(height, 8);
  return p;
}

describe('H.264 helpers', () => {
  it('finds NAL units behind three- and four-byte start codes', () => {
    const units = nalUnits(Buffer.concat([SPS, Buffer.from([0, 0, 1, 0x68, 0xce]), IDR]));
    assert.deepEqual(
      units.map((u) => u.type),
      [7, 8, 5]
    );
  });

  it('turns the SPS into the WebCodecs codec string', () => {
    assert.equal(codecString(CONFIG), 'avc1.42C029');
    assert.equal(codecString(IDR), null);
  });
});

describe('scrcpy video stream', () => {
  it('splits codec id, session and media packets, however the bytes are chunked', () => {
    const stream = Buffer.concat([
      Buffer.from('68323634', 'hex'),
      sessionPacket(576, 1280),
      mediaPacket({ config: true, data: CONFIG }),
      mediaPacket({ key: true, data: IDR }),
      mediaPacket({ data: DELTA }),
    ]);
    for (const step of [1, 3, 7, stream.length]) {
      const events = [];
      const demuxer = new VideoDemuxer({
        onCodec: (id) => events.push(['codec', id]),
        onSession: (size) => events.push(['session', size]),
        onPacket: (p) => events.push(['packet', p.config, p.key, p.data.toString('hex')]),
      });
      for (let i = 0; i < stream.length; i += step) demuxer.push(stream.subarray(i, i + step));
      assert.deepEqual(events, [
        ['codec', 0x68323634],
        ['session', { width: 576, height: 1280 }],
        ['packet', true, false, CONFIG.toString('hex')],
        ['packet', false, true, IDR.toString('hex')],
        ['packet', false, false, DELTA.toString('hex')],
      ]);
    }
  });

  it('puts the config in front of every key frame, so any key frame can start a decoder', () => {
    const frames = [];
    const codecs = [];
    const asm = new FrameAssembler((f) => frames.push(f), (c) => codecs.push(c));
    asm.packet({ config: false, key: false, data: DELTA }); // before any config: undecodable, dropped
    asm.packet({ config: true, key: false, data: CONFIG });
    asm.packet({ config: false, key: true, data: IDR });
    asm.packet({ config: false, key: false, data: DELTA });
    asm.packet({ config: false, key: true, data: IDR }); // a periodic key frame, no config before it
    assert.deepEqual(codecs, ['avc1.42C029']);
    assert.deepEqual(
      frames.map((f) => [f.key, f.data.toString('hex')]),
      [
        [true, Buffer.concat([CONFIG, IDR]).toString('hex')],
        [false, DELTA.toString('hex')],
        [true, Buffer.concat([CONFIG, IDR]).toString('hex')],
      ]
    );
    assert.deepEqual([...screenFramePayload({ key: true, data: IDR }).subarray(0, 2)], [1, 0]);
    assert.equal(screenFramePayload({ key: false, data: DELTA })[0], 0);
  });

  it('does not repeat an SPS the key frame already carries', () => {
    const frames = [];
    const asm = new FrameAssembler((f) => frames.push(f), () => {});
    asm.packet({ config: true, key: false, data: CONFIG });
    const inline = Buffer.concat([CONFIG, IDR]);
    asm.packet({ config: false, key: true, data: inline });
    assert.deepEqual(frames[0].data, inline);
  });
});

describe('scrcpy control messages', () => {
  it('encodes a finger touch the way ControlMessageReader reads it', () => {
    const m = encodeTouch({ action: 0, x: 288, y: 640, width: 576, height: 1280 });
    assert.equal(m.length, 32);
    assert.equal(m[0], 2); // INJECT_TOUCH_EVENT
    assert.equal(m[1], 0); // ACTION_DOWN
    assert.equal(m.readBigInt64BE(2), -2n); // POINTER_ID_GENERIC_FINGER
    assert.equal(m.readInt32BE(10), 288);
    assert.equal(m.readInt32BE(14), 640);
    assert.equal(m.readUInt16BE(18), 576);
    assert.equal(m.readUInt16BE(20), 1280);
    assert.equal(m.readUInt16BE(22), 0xffff); // pressure 1.0
    assert.equal(m.readUInt32BE(24), 0);
    assert.equal(m.readUInt32BE(28), 0);
    assert.equal(encodeTouch({ action: 1, x: 1, y: 1, width: 576, height: 1280 }).readUInt16BE(22), 0);
  });

  it('encodes scroll in notches as i16 fixed point over ±16', () => {
    const m = encodeScroll({ x: 10, y: 20, width: 576, height: 1280, hscroll: 0, vscroll: 1 });
    assert.equal(m.length, 21);
    assert.equal(m[0], 3);
    assert.equal(m.readInt16BE(13), 0);
    assert.equal(m.readInt16BE(15), 0x8000 / 16);
    assert.equal(encodeScroll({ x: 0, y: 0, width: 1, height: 1, hscroll: -16, vscroll: 16 }).readInt16BE(13), -0x8000);
    assert.equal(encodeScroll({ x: 0, y: 0, width: 1, height: 1, hscroll: -16, vscroll: 16 }).readInt16BE(15), 0x7fff);
  });

  it('encodes keycodes, text in ≤300-byte pieces, the clipboard and a video reset', () => {
    const k = encodeKeycode({ action: 1, keycode: 66, metaState: META.CTRL });
    assert.deepEqual([k.length, k[0], k[1], k.readUInt32BE(2), k.readUInt32BE(6), k.readUInt32BE(10)], [14, 0, 1, 66, 0, 0x3000]);

    const pieces = encodeText('a'.repeat(299) + '한');
    assert.equal(pieces.length, 2, 'a 3-byte character does not straddle the 300-byte limit');
    assert.equal(pieces[0].readUInt32BE(1), 299);
    assert.equal(pieces[1].subarray(5).toString('utf8'), '한');

    const c = encodeSetClipboard('안녕');
    assert.equal(c[0], 9);
    assert.equal(c.readBigUInt64BE(1), 0n);
    assert.equal(c[9], 1);
    assert.equal(c.readUInt32BE(10), Buffer.byteLength('안녕'));
    assert.equal(c.subarray(14).toString('utf8'), '안녕');

    assert.deepEqual([...encodeResetVideo()], [17]);
  });
});

describe('viewer input → scrcpy (§12.4)', () => {
  const screen = { width: 576, height: 1280 };
  const kinds = (messages) => messages.map((m) => m[0]);

  it('makes the left button a finger: down, moves while down, up', () => {
    const st = createTouchState();
    assert.deepEqual(mapAndroidInput({ type: 'pointer', action: 'move', x: 0.5, y: 0.5, button: 'none' }, screen, st), []);
    const [down] = mapAndroidInput({ type: 'pointer', action: 'down', x: 0.5, y: 0.25, button: 'left' }, screen, st);
    assert.deepEqual([down[1], down.readInt32BE(10), down.readInt32BE(14)], [0, 288, 320]);
    const [move] = mapAndroidInput({ type: 'pointer', action: 'move', x: 0.5, y: 0.5, button: 'left' }, screen, st);
    assert.equal(move[1], 2);
    const [up] = mapAndroidInput({ type: 'pointer', action: 'up', x: 1, y: 1, button: 'left' }, screen, st);
    assert.deepEqual([up[1], up.readInt32BE(10), up.readInt32BE(14)], [1, 575, 1279]);
    assert.deepEqual(mapAndroidInput({ type: 'pointer', action: 'up', x: 1, y: 1 }, screen, st), []);
    assert.deepEqual(mapAndroidInput({ type: 'pointer', action: 'down', x: 0.5, y: 0.5, button: 'right' }, screen, st), []);
    assert.deepEqual(mapAndroidInput({ type: 'pointer', action: 'down', x: 'x', y: 0.5 }, screen, st), []);
    assert.deepEqual(mapAndroidInput({ type: 'pointer', action: 'down', x: 0.5, y: 0.5 }, { width: 0, height: 0 }, st), []);
  });

  it('turns a browser wheel into scrcpy notches, down being negative', () => {
    const [m] = mapAndroidInput({ type: 'wheel', x: 0.5, y: 0.5, dx: 0, dy: 120 }, screen);
    assert.equal(m.readInt16BE(15), -0x8000 / 16);
    assert.deepEqual(mapAndroidInput({ type: 'wheel', x: 0.5, y: 0.5, dx: 0, dy: 0 }, screen), []);
  });

  it('sends named keys as keycodes, typed characters as text, and shortcuts with Ctrl', () => {
    const [enter] = mapAndroidInput({ type: 'key', action: 'down', key: 'Enter', code: 'Enter', modifiers: 0 }, screen);
    assert.deepEqual([enter[0], enter.readUInt32BE(2)], [0, KEYCODE.ENTER]);
    const [shiftLeft] = mapAndroidInput({ type: 'key', action: 'down', key: 'ArrowLeft', modifiers: 8 }, screen);
    assert.equal(shiftLeft.readUInt32BE(10), META.SHIFT);

    const typed = mapAndroidInput({ type: 'key', action: 'down', key: 'a', code: 'KeyA', modifiers: 0 }, screen);
    assert.deepEqual(kinds(typed), [1]);
    assert.equal(typed[0].subarray(5).toString(), 'a');
    assert.deepEqual(mapAndroidInput({ type: 'key', action: 'up', key: 'a', modifiers: 0 }, screen), []);

    // Cmd+A on a Mac is Ctrl+A on Android.
    const [selectAll] = mapAndroidInput({ type: 'key', action: 'down', key: 'a', code: 'KeyA', modifiers: 4 }, screen);
    assert.deepEqual([selectAll[0], selectAll.readUInt32BE(2), selectAll.readUInt32BE(10)], [0, KEYCODE.A, META.CTRL]);

    assert.deepEqual(mapAndroidInput({ type: 'key', action: 'down', key: 'Shift', modifiers: 8 }, screen), []);
  });

  it('types ASCII and pastes what the keyboard map cannot type', () => {
    assert.deepEqual(kinds(mapAndroidInput({ type: 'text', text: 'hello world' }, screen)), [1]);
    assert.deepEqual(mapAndroidInput({ type: 'text', text: '안녕하세요' }, screen), [{ paste: '안녕하세요' }]);
    assert.deepEqual(mapAndroidInput({ type: 'key', action: 'down', key: '한', modifiers: 0 }, screen), [{ paste: '한' }]);
  });

  it('has back, and nothing else, for navigation', () => {
    const back = mapAndroidInput({ type: 'nav', action: 'back' }, screen);
    assert.deepEqual(
      back.map((m) => [m[0], m[1], m.readUInt32BE(2)]),
      [
        [0, 0, KEYCODE.BACK],
        [0, 1, KEYCODE.BACK],
      ]
    );
    assert.deepEqual(mapAndroidInput({ type: 'nav', action: 'reload' }, screen), []);
    assert.deepEqual(mapAndroidInput({ type: 'nope' }, screen), []);
  });
});

describe('adb and scrcpy discovery', () => {
  it('parses adb devices, the foreground activity and a resolved component', () => {
    assert.deepEqual(parseDevices('List of devices attached\nemulator-5554\tdevice\nR58M\tunauthorized\n\n'), [
      { serial: 'emulator-5554', state: 'device' },
      { serial: 'R58M', state: 'unauthorized' },
    ]);
    assert.equal(
      parseForegroundPackage(
        '      topResumedActivity=ActivityRecord{1622d02 u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity t7}'
      ),
      'com.google.android.apps.nexuslauncher'
    );
    assert.equal(parseForegroundPackage(''), null);
    assert.equal(
      parseResolvedComponent('priority=0 preferredOrder=0 match=0x108000\ncom.android.settings/.Settings\n'),
      'com.android.settings/.Settings'
    );
    assert.equal(parseResolvedComponent('No activity found'), null);
  });

  it('finds adb in the SDK before PATH', () => {
    const seen = new Set(['/sdk/platform-tools/adb', '/usr/bin/adb']);
    const exists = (p) => seen.has(p);
    assert.equal(findAdb({ env: { ANDROID_HOME: '/sdk', PATH: '/usr/bin' }, platform: 'linux', home: '/h', exists }), '/sdk/platform-tools/adb');
    assert.equal(findAdb({ env: { PATH: '/usr/bin' }, platform: 'linux', home: '/h', exists }), '/usr/bin/adb');
    assert.equal(findAdb({ env: {}, platform: 'linux', home: '/h', exists }), null);
  });

  it('finds the scrcpy server next to the installed scrcpy and takes its version', () => {
    const exists = (p) => p === '/opt/x/bin/scrcpy' || p === '/opt/x/share/scrcpy/scrcpy-server';
    assert.deepEqual(findScrcpy({ env: { PATH: '/opt/x/bin' }, exists, versionOf: () => 'scrcpy 5.0.1 <https://…>' }), {
      serverPath: '/opt/x/share/scrcpy/scrcpy-server',
      version: '5.0.1',
    });
    assert.throws(() => findScrcpy({ env: { PATH: '/nowhere' }, exists: () => false }), /brew install scrcpy/);
    assert.throws(() => findScrcpy({ env: { PATH: '/opt/x/bin' }, exists, versionOf: () => 'scrcpy 2.7' }), /2\.7 는 지원하지 않습니다/);
    assert.equal(parseScrcpyVersion('scrcpy 4.0 <https://github.com/Genymobile/scrcpy>'), '4.0');
    assert.ok(isSupportedVersion('5.0.1') && isSupportedVersion('4.0') && !isSupportedVersion('3.3.4'));
  });
});

// ── the session over a real tunnel socket ────────────────────────────────────

async function tunnelServer() {
  const wss = new WebSocketServer({ host: '127.0.0.1', port: 0 });
  await once(wss, 'listening');
  const arrived = [];
  const waiting = [];
  wss.on('connection', (socket, req) => {
    const frames = [];
    const readers = [];
    socket.on('message', (data) => {
      const frame = decodeFrame(data);
      frame.json = parseJsonPayload(frame.payload);
      if (readers.length) readers.shift()(frame);
      else frames.push(frame);
    });
    const conn = {
      socket,
      req,
      frames,
      next: () => (frames.length ? Promise.resolve(frames.shift()) : new Promise((r) => readers.push(r))),
      send: (kind, value) => socket.send(encodeJsonFrame(kind, value)),
    };
    if (waiting.length) waiting.shift()(conn);
    else arrived.push(conn);
  });
  const server = {
    url: `ws://127.0.0.1:${wss.address().port}/api/v1/device-shares/7/tunnel?v=1`,
    nextConnection: () => (arrived.length ? Promise.resolve(arrived.shift()) : new Promise((r) => waiting.push(r))),
    close: async () => {
      for (const client of wss.clients) client.terminate();
      await new Promise((r) => wss.close(r));
    },
  };
  cleanups.push(server.close);
  return server;
}

/** Stands in for ScrcpyDevice: the test drives the stream, and sees what the session sends back. */
function fakeDevice() {
  const device = {
    handlers: null,
    controls: [],
    resets: 0,
    stopped: false,
    start: async (handlers) => {
      device.handlers = handlers;
    },
    sendControl: (m) => {
      device.controls.push(m);
      return true;
    },
    resetVideo: () => {
      device.resets++;
      return true;
    },
    stop: async () => {
      device.stopped = true;
    },
  };
  return device;
}

function newAndroidSession(overrides = {}) {
  const logs = [];
  const device = fakeDevice();
  const session = new AndroidShareSession({
    device,
    pkg: 'com.example.app',
    targetLabel: 'qeploy_test · com.example.app',
    deviceName: 'qeploy_test',
    tunnelUrl: 'ws://127.0.0.1:1/unused?v=1',
    token: 'qp_test',
    cliVersion: '9.9.9',
    askControl: async () => true,
    log: (text) => logs.push(text),
    pingIntervalMs: 0,
    ...overrides,
  });
  cleanups.push(async () => session.stop('test-cleanup'));
  return { session, device, logs };
}

describe('android share session (§12)', () => {
  it('announces ANDROID, sends an h264 config before a key frame, and asks for key frames', { timeout: 10000 }, async () => {
    const server = await tunnelServer();
    const { session, device } = newAndroidSession({ tunnelUrl: server.url });
    const done = session.run();
    const conn = await server.nextConnection();

    const hello = await conn.next();
    assert.equal(hello.kind, KIND.HELLO);
    assert.deepEqual(hello.json, { cliVersion: '9.9.9', kind: 'ANDROID', target: 'qeploy_test · com.example.app' });

    // The stream started before the tunnel was up: nothing goes out until WELCOME.
    device.handlers.onSize({ width: 576, height: 1280 });
    device.handlers.onCodec('avc1.42C029');
    device.handlers.onFrame({ key: true, data: Buffer.concat([CONFIG, IDR]) });

    conn.send(KIND.WELCOME, { shareId: 7, expiresAt: '2026-11-12T13:00:00Z' });
    const config = await conn.next();
    assert.equal(config.kind, KIND.SCREEN_CONFIG);
    assert.deepEqual(config.json, {
      format: 'h264',
      codec: 'avc1.42C029',
      width: 576,
      height: 1280,
      url: 'com.example.app',
      title: 'qeploy_test',
    });
    await until(() => device.resets === 1); // the server has no key frame of this stream yet

    device.handlers.onFrame({ key: false, data: DELTA }); // before a key frame: dropped
    device.handlers.onFrame({ key: true, data: Buffer.concat([CONFIG, IDR]) });
    device.handlers.onFrame({ key: false, data: DELTA });
    const key = await conn.next();
    assert.equal(key.kind, KIND.SCREEN_FRAME);
    assert.equal(key.payload[0], 1);
    assert.deepEqual(key.payload.subarray(1), Buffer.concat([CONFIG, IDR]));
    const delta = await conn.next();
    assert.equal(delta.payload[0], 0);
    assert.deepEqual(delta.payload.subarray(1), DELTA);

    // KEYFRAME_REQUEST: one reset now, and one more at most a second later however many come.
    conn.socket.send(Buffer.from([KIND.KEYFRAME_REQUEST, 0, 0, 0, 0, 0x7b, 0x7d]));
    conn.socket.send(Buffer.from([KIND.KEYFRAME_REQUEST, 0, 0, 0, 0, 0x7b, 0x7d]));
    await new Promise((r) => setTimeout(r, 150));
    assert.equal(device.resets, 1, 'still inside the second since the WELCOME reset');
    await until(() => device.resets === 2, 2000);

    conn.send(KIND.INPUT, { type: 'pointer', action: 'down', x: 0.5, y: 0.25, button: 'left' });
    await until(() => device.controls.length === 1);
    assert.equal(device.controls[0][0], 2);
    assert.equal(device.controls[0].readInt32BE(14), 320);

    // A rotation: a new size is announced again, and frames wait for the new session's key frame.
    device.handlers.onSize({ width: 1280, height: 576 });
    const rotated = await conn.next();
    assert.equal(rotated.json.width, 1280);
    device.handlers.onFrame({ key: false, data: DELTA });
    device.handlers.onFrame({ key: true, data: Buffer.concat([CONFIG, IDR]) });
    assert.equal((await conn.next()).payload[0], 1);

    conn.send(KIND.GOODBYE, { reason: 'revoked' });
    assert.deepEqual(await done, { reason: 'revoked' });
    assert.equal(device.stopped, true);
  });

  it('paces clipboard pastes and merges the text that waits, keeping the order of everything', async () => {
    const { session, device } = newAndroidSession();
    session.screen = { width: 576, height: 1280, codec: 'avc1.42C029' };
    for (const ch of '에뮬레이터') session.handleInput({ type: 'key', action: 'down', key: ch, modifiers: 0 });
    session.handleInput({ type: 'text', text: '!' });
    // The first syllable goes at once; the rest wait behind it, merged into one paste.
    assert.equal(device.controls.length, 1);
    assert.equal(device.controls[0].subarray(14).toString(), '에');
    await until(() => device.controls.length === 3, 2000); // two 200 ms gaps, real time
    assert.equal(device.controls[1][0], 9);
    assert.equal(device.controls[1].subarray(14).toString(), '뮬레이터');
    assert.equal(device.controls[2][0], 1, 'the ASCII after it still comes after it');
  });

  it('ends with device-exit when the emulator stream stops', async () => {
    const server = await tunnelServer();
    const { session, device } = newAndroidSession({ tunnelUrl: server.url });
    const done = session.run();
    await server.nextConnection();
    const error = new Error('영상 연결이 끊겼습니다.');
    device.handlers.onClose(error);
    assert.deepEqual(await done, { reason: 'device-exit', error });
  });

  it('brings the shared app back when something else comes to the front (§12.5)', async () => {
    const tops = ['com.example.app', 'com.google.android.permissioncontroller', 'com.google.android.apps.nexuslauncher'];
    const launched = [];
    const adb = {
      foregroundPackage: async () => tops.shift() ?? 'com.example.app',
      launchApp: async (pkg) => launched.push(pkg),
    };
    const server = await tunnelServer();
    const { session, logs } = newAndroidSession({ tunnelUrl: server.url, adb, guardIntervalMs: 10 });
    session.run();
    await until(() => launched.length === 1);
    assert.deepEqual(launched, ['com.example.app']);
    assert.ok(logs.some((l) => l.includes('com.google.android.apps.nexuslauncher') && l.includes('다시 앞으로')));
  });

  it('does not guard with --any-app', async () => {
    let asked = 0;
    const adb = {
      foregroundPackage: async () => {
        asked++;
        return 'com.other';
      },
      launchApp: async () => assert.fail('no guard'),
    };
    const server = await tunnelServer();
    const { session } = newAndroidSession({ tunnelUrl: server.url, adb, guard: false, guardIntervalMs: 10 });
    session.run();
    await new Promise((r) => setTimeout(r, 60));
    assert.equal(asked, 0);
  });
});

// ── the command ──────────────────────────────────────────────────────────────

describe('qeploy share android', () => {
  const share = {
    shareId: 9,
    viewUrl: 'http://k3x9.share.localhost:8080/',
    controlUrl: 'http://k3x9.share.localhost:8080/#c=secret',
    tunnelUrl: 'ws://localhost:8080/api/v1/device-shares/9/tunnel?v=1',
    expiresAt: '2026-11-13T13:00:00',
  };

  function fakeClient() {
    const client = {
      token: 'qp_cli',
      created: null,
      revoked: [],
      createDeviceShare: async (body) => {
        client.created = body;
        return share;
      },
      revokeDeviceShare: async (id) => {
        client.revoked.push(id);
        return null;
      },
    };
    return client;
  }

  function fakeAdb({ devices = [{ serial: 'emulator-5554', state: 'device' }], emulator = true, top = 'com.android.settings', installed = true } = {}) {
    const calls = [];
    const adb = {
      calls,
      serial: null,
      withSerial: (serial) => ({ ...adb, serial }),
      devices: async () => devices,
      isEmulator: async () => emulator,
      foregroundPackage: async () => top,
      homePackage: async () => 'com.google.android.apps.nexuslauncher',
      isInstalled: async () => installed,
      deviceName: async () => 'qeploy_test',
      launchApp: async (pkg) => calls.push(['launch', pkg]),
    };
    return adb;
  }

  function fakeRuntime({ adb = fakeAdb(), adbPath = '/sdk/platform-tools/adb', end } = {}) {
    const rt = {
      adb,
      interrupt: null,
      sessionOptions: null,
      deviceOptions: null,
      stoppedDevice: false,
      findAdb: () => adbPath,
      createAdb: () => adb,
      findScrcpy: () => ({ serverPath: '/opt/scrcpy-server', version: '5.0.1' }),
      createScrcpyDevice: (options) => {
        rt.deviceOptions = options;
        return {
          stop: async () => {
            rt.stoppedDevice = true;
          },
        };
      },
      createAndroidSession: (options) => {
        rt.sessionOptions = options;
        let resolve;
        const done = new Promise((r) => {
          resolve = r;
        });
        return {
          run: () => {
            setImmediate(() => end?.(rt));
            return done;
          },
          stop: (reason) => resolve({ reason }),
        };
      },
      askControl: async () => false,
      onInterrupt: (fn) => {
        rt.interrupt = fn;
        return () => {};
      },
      forceExit: () => {},
    };
    return rt;
  }

  async function shareCli(argv, { client = fakeClient(), runtime = fakeRuntime() } = {}) {
    const out = [];
    const err = [];
    const code = await run(argv, {
      env: {},
      createClient: () => client,
      confirm: async () => true,
      out: (t) => out.push(t),
      err: (t) => err.push(t),
      share: runtime,
    });
    return { code, out: out.join('\n'), err: err.join('\n'), client, runtime };
  }

  it('shares the app in front, guarded, and on Ctrl+C revokes the link and stops scrcpy', async () => {
    const runtime = fakeRuntime({ end: (rt) => rt.interrupt() });
    const { code, out, err, client } = await shareCli(['share', 'android', '--max-size', '1024', '--bit-rate', '4M'], { runtime });
    assert.equal(code, EXIT.OK);
    assert.deepEqual(client.created, { kind: 'ANDROID', targetLabel: 'qeploy_test · com.android.settings', ttlMinutes: undefined });
    assert.match(out, /보기 링크/);
    assert.equal(runtime.sessionOptions.pkg, 'com.android.settings');
    assert.equal(runtime.sessionOptions.guard, true);
    assert.equal(runtime.sessionOptions.adb.serial, 'emulator-5554');
    assert.deepEqual([runtime.deviceOptions.maxSize, runtime.deviceOptions.bitRate, runtime.deviceOptions.version], [1024, 4_000_000, '5.0.1']);
    assert.deepEqual(client.revoked, [9]);
    assert.equal(runtime.stoppedDevice, true);
    assert.match(err, /공유를 끝냈습니다\. 링크를 회수했습니다\. 에뮬레이터의 scrcpy 서버를 끝냈습니다\./);
  });

  it('launches the app given with --app first', async () => {
    const adb = fakeAdb({ top: 'com.google.android.apps.nexuslauncher' });
    const runtime = fakeRuntime({ adb, end: (rt) => rt.interrupt() });
    const { code, client } = await shareCli(['share', 'android', '--app', 'com.android.chrome'], { runtime });
    assert.equal(code, EXIT.OK);
    assert.equal(client.created.targetLabel, 'qeploy_test · com.android.chrome');
    assert.deepEqual(adb.calls, [['launch', 'com.android.chrome']]);
  });

  it('refuses before making a link: no adb, no emulator, a real phone, the home screen, a missing app', async () => {
    const cases = [
      [fakeRuntime({ adbPath: null }), [], /adb 를 찾지 못했습니다/],
      [fakeRuntime({ adb: fakeAdb({ devices: [] }) }), [], /에뮬레이터가 없습니다/],
      [
        fakeRuntime({ adb: fakeAdb({ devices: [{ serial: 'emulator-5554', state: 'device' }, { serial: 'emulator-5556', state: 'device' }] }) }),
        [],
        /--serial/,
      ],
      [fakeRuntime({ adb: fakeAdb({ devices: [{ serial: 'R58M', state: 'device' }], emulator: false }) }), ['--serial', 'R58M'], /에뮬레이터가 아닙니다/],
      [fakeRuntime({ adb: fakeAdb({ top: 'com.google.android.apps.nexuslauncher' }) }), [], /홈 화면/],
      [fakeRuntime({ adb: fakeAdb({ installed: false }) }), ['--app', 'com.nope.app'], /설치되어 있지 않습니다/],
    ];
    for (const [runtime, extra, message] of cases) {
      const { code, err, client } = await shareCli(['share', 'android', ...extra], { runtime });
      assert.equal(code, EXIT.ERROR, String(message));
      assert.match(err, message);
      assert.equal(client.created, null, String(message));
    }
  });

  it('shares the whole emulator with --any-app, unguarded and with a warning', async () => {
    const runtime = fakeRuntime({ adb: fakeAdb({ top: 'com.google.android.apps.nexuslauncher' }), end: (rt) => rt.interrupt() });
    const { code, err, client } = await shareCli(['share', 'android', '--any-app'], { runtime });
    assert.equal(code, EXIT.OK);
    assert.equal(client.created.targetLabel, 'qeploy_test · 에뮬레이터 전체');
    assert.equal(runtime.sessionOptions.guard, false);
    assert.equal(runtime.sessionOptions.pkg, null);
    assert.match(err, /10\.0\.2\.2/);
  });

  it('refuses bad arguments as usage errors', async () => {
    for (const argv of [
      ['share', 'android', 'emulator-5554'],
      ['share', 'android', '--app', 'not a package'],
      ['share', 'android', '--max-size', 'big'],
      ['share', 'android', '--bit-rate', '100'],
      ['share', 'android', '--serial', 'a b'],
      ['share', 'ios'],
    ]) {
      const { code, client } = await shareCli(argv);
      assert.equal(code, EXIT.USAGE, argv.join(' '));
      assert.equal(client.created, null, argv.join(' '));
    }
  });
});
