import assert from 'node:assert/strict';
import { afterEach, describe, it } from 'node:test';
import { EventEmitter, once } from 'node:events';
import { PassThrough } from 'node:stream';
import { WebSocketServer } from 'ws';
import { KIND, decodeFrame, encodeJsonFrame, parseJsonPayload } from '../src/share/protocol.js';
import {
  RawJpegDemuxer,
  activeScreenSize,
  foregroundApp,
  keyboardMode,
  isSupportedAxeVersion,
  jpegSize,
  parseAxeJson,
  parseAxeVersion,
} from '../src/share/ios/axe-protocol.js';
import { Axe, AxeCommands, AxeVideo, NO_AXE_MESSAGE, findAxe } from '../src/share/ios/axe.js';
import {
  HID,
  MAX_TYPE_CHARS,
  axeCommands,
  createGestureState,
  letterOrDigitHid,
  mapIosInput,
  mergeItem,
} from '../src/share/ios/input.js';
import { hangulKeys } from '../src/share/ios/hangul.js';
import {
  Simctl,
  appNamesOf,
  findAppByName,
  hasKoreanKeyboard,
  parseAppList,
  parseAppleLanguages,
  parseBootedSimulators,
  parseLaunchPid,
  parsePasteboardSync,
} from '../src/share/ios/simctl.js';
import { CAPS_LOCK, HIGH_WATER_BYTES, IosShareSession, PREVIOUS_INPUT_SOURCE } from '../src/share/ios/session.js';
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

const tick = () => new Promise((r) => setImmediate(r));

/** A JPEG as far as anything here reads one: SOI, an APP0, a baseline SOF0, then the scan. */
function fakeJpeg(width, height, fill = 0, { sof = 0xc0 } = {}) {
  const app0 = Buffer.from([0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00]);
  const frame = Buffer.alloc(19);
  frame[0] = 0xff;
  frame[1] = sof;
  frame.writeUInt16BE(17, 2);
  frame[4] = 8;
  frame.writeUInt16BE(height, 5);
  frame.writeUInt16BE(width, 7);
  frame[9] = 3;
  return Buffer.concat([Buffer.from([0xff, 0xd8]), app0, frame, Buffer.from([0xff, 0xda, 0x00, 0x02, fill, 0xff, 0xd9])]);
}

/** stream-video --format raw: [u32 BE length][JPEG] … */
function rawStream(frames) {
  return Buffer.concat(
    frames.flatMap((jpeg) => {
      const length = Buffer.alloc(4);
      length.writeUInt32BE(jpeg.length, 0);
      return [length, jpeg];
    })
  );
}

const SCREEN = { width: 402, height: 874 };
const SAFARI_PID = 34990;
const SPRINGBOARD_PID = 33424;

/** `axe --output json displays` as AXe 2.0.4 printed it for the test simulator. */
const DISPLAYS_REPLY =
  '{"command":"displays","data":{"result":{"activeScreen":{"height":874,"id":1,"orientation":"portrait","role":"main","rotation":0,"scale":3,"state":"unknown","uuid":"5E1F0B6A-26D5-4C0E-9A7B-3D2C1F0E9A26","width":402},"name":"Qeploy Share Test","otherScreens":[],"udid":"04444F2A-5969-4C97-8FDC-2B3FDD5C7B22"}},"didError":false,"nextSteps":[],"schema":"axe.output.command-result","schemaVersion":"1"}';

function displaysReply({ width, height }) {
  const reply = JSON.parse(DISPLAYS_REPLY);
  Object.assign(reply.data.result.activeScreen, { width, height });
  return reply;
}

/**
 * `axe --output json describe-ui` as AXe 2.0.4 prints it, the root's fields as measured, the tree cut
 * short. On the home screen the root is SpringBoard's: label " ", its own pid.
 */
function describeReply(label, pid = SAFARI_PID, keyboard = null) {
  const element = (type, extra = {}) => ({
    AXFrame: '{{0, 0}, {402, 874}}',
    AXLabel: null,
    AXUniqueId: null,
    AXValue: null,
    content_required: false,
    custom_actions: [],
    enabled: true,
    frame: { height: 874, width: 402, x: 0, y: 0 },
    help: null,
    role: type === 'Application' ? 'AXApplication' : 'AXGroup',
    role_description: type.toLowerCase(),
    subrole: null,
    title: null,
    traits: [],
    type,
    children: [],
    ...extra,
  });
  return {
    command: 'describe-ui',
    data: { result: [element('Application', { AXLabel: label, pid, children: [element('Group'), element('Group'), ...keyboardKeys(keyboard)] })] },
    didError: false,
    nextSteps: [],
    schema: 'axe.output.command-result',
    schemaVersion: '1',
  };
}

/** A few keys of the on-screen keyboard as describe-ui lists it below the screen (measured labels). */
function keyboardKeys(keyboard) {
  const labels = { ko: ['ㅂ', 'ㅈ', 'ㅁ', 'ㄴ', '다음 키보드'], en: ['q', 'w', 'a', 's', 'Next keyboard'], emoji: ['😂', '❤️', '다음 키보드'] }[keyboard] ?? [];
  return labels.map((AXLabel, i) => ({ type: 'Button', role: 'AXButton', AXLabel, frame: { x: 5 + 40 * i, y: 959, width: 39, height: 54 }, children: [] }));
}

const okReply = (command) => ({ command, data: { messages: [], result: { outcome: 'completed' } }, didError: false, nextSteps: [] });

describe('AXe stream-video raw format', () => {
  it('splits length-prefixed JPEGs however the pipe chunks them', () => {
    const jpegs = [fakeJpeg(201, 437, 1), fakeJpeg(201, 437, 2), fakeJpeg(437, 201, 3)];
    const stream = rawStream(jpegs);
    for (const step of [1, 3, 7, 40, stream.length]) {
      const frames = [];
      const demuxer = new RawJpegDemuxer((f) => frames.push(Buffer.from(f)));
      for (let i = 0; i < stream.length; i += step) demuxer.push(stream.subarray(i, i + step));
      assert.deepEqual(frames, jpegs, `step ${step}`);
    }
  });

  it('skips empty frames and refuses a length that cannot be a frame', () => {
    const frames = [];
    const demuxer = new RawJpegDemuxer((f) => frames.push(f), { maxFrameBytes: 1000 });
    demuxer.push(Buffer.concat([Buffer.alloc(4), rawStream([fakeJpeg(1, 1)])]));
    assert.equal(frames.length, 1);
    const huge = Buffer.alloc(4);
    huge.writeUInt32BE(1001, 0);
    assert.throws(() => demuxer.push(huge), /형식에 맞지 않습니다/);
  });

  it('reads the size from the SOF segment', () => {
    assert.deepEqual(jpegSize(fakeJpeg(603, 1311)), { width: 603, height: 1311 });
    assert.deepEqual(jpegSize(fakeJpeg(1311, 603, 0, { sof: 0xc2 })), { width: 1311, height: 603 }, 'progressive');
    // A fill byte before a marker is allowed by the format.
    const jpeg = fakeJpeg(10, 20);
    const filled = Buffer.concat([jpeg.subarray(0, 20), Buffer.from([0xff]), jpeg.subarray(20)]);
    assert.deepEqual(jpegSize(filled), { width: 10, height: 20 });
    assert.equal(jpegSize(Buffer.from('not a jpeg')), null);
    assert.equal(jpegSize(Buffer.from([0xff, 0xd8, 0xff, 0xda, 0x00, 0x02, 0xff, 0xd9])), null, 'scan before any SOF');
    assert.equal(jpegSize(fakeJpeg(10, 20).subarray(0, 25)), null, 'cut short');
  });
});

describe('AXe JSON output', () => {
  it('reads the result envelope, failures included', () => {
    assert.equal(parseAxeJson(`${DISPLAYS_REPLY}\n`).command, 'displays');
    const failed = parseAxeJson('{"command":"tap","data":{},"didError":true,"error":{"code":"targetNotFound","message":"No simulator has UDID …"}}');
    assert.equal(failed.error.code, 'targetNotFound');
    assert.equal(parseAxeJson('Error: Unknown option'), null);
    assert.equal(parseAxeJson('[1,2]'), null);
    assert.equal(parseAxeJson('{"data":{}}'), null);
    assert.equal(parseAxeJson(undefined), null);
  });

  it('takes the screen size in points from displays', () => {
    assert.deepEqual(activeScreenSize(parseAxeJson(DISPLAYS_REPLY)), { width: 402, height: 874 });
    assert.equal(activeScreenSize({ didError: true, data: {} }), null);
    assert.equal(activeScreenSize(null), null);
  });

  it('takes the app in front, its name and pid, from the Application element of describe-ui', () => {
    assert.deepEqual(foregroundApp(describeReply('Safari')), { label: 'Safari', pid: SAFARI_PID });
    assert.deepEqual(foregroundApp(describeReply(' My App ', 4242)), { label: 'My App', pid: 4242 });
    // The home screen, as measured after `axe button home`: a blank label and SpringBoard's pid.
    assert.deepEqual(foregroundApp(describeReply(' ', SPRINGBOARD_PID)), { label: null, pid: SPRINGBOARD_PID });
    // Without --output json, the bare array.
    assert.equal(foregroundApp(describeReply('Settings').data.result).label, 'Settings');
    // Below a wrapper, breadth first; no pid is null.
    assert.deepEqual(foregroundApp([{ type: 'Window', children: [{ type: 'Application', AXLabel: 'Maps' }] }]), { label: 'Maps', pid: null });
    assert.equal(foregroundApp({ didError: false, data: { result: [] } }), null);
    assert.equal(foregroundApp('nonsense'), null);
  });

  it('tells the keyboard in use from the keys describe-ui lists', () => {
    assert.equal(keyboardMode(describeReply('Safari', SAFARI_PID, 'ko')), 'ko');
    assert.equal(keyboardMode(describeReply('Safari', SAFARI_PID, 'en')), 'en');
    assert.equal(keyboardMode(describeReply('Safari', SAFARI_PID, 'emoji')), 'emoji');
    assert.equal(keyboardMode(describeReply('Safari')), null, 'no field has focus');
    // Shifted keys: ㅂ becomes ㅃ and q becomes Q, but ㅁ and a stay.
    assert.equal(keyboardMode([{ type: 'Button', AXLabel: 'ㅃ' }, { type: 'Button', AXLabel: 'ㅁ' }]), 'ko');
    assert.equal(keyboardMode([{ type: 'Button', AXLabel: 'Q' }, { type: 'Button', AXLabel: 'A' }]), 'en');
  });

  it('wants AXe 2.x', () => {
    assert.equal(parseAxeVersion('v2.0.4\n'), '2.0.4');
    assert.equal(parseAxeVersion('2.3.1\n'), '2.3.1');
    assert.equal(parseAxeVersion('AXe version 2.0'), '2.0');
    assert.equal(parseAxeVersion(''), null);
    assert.ok(isSupportedAxeVersion('2.3.1') && !isSupportedAxeVersion('1.6.0') && !isSupportedAxeVersion('4.10.2'));
  });
});

describe('viewer input → AXe (§13.2)', () => {
  const at = (x, y, action, extra = {}) => ({ type: 'pointer', action, x, y, button: 'left', ...extra });
  const lines = (items) => items.flatMap(axeCommands).map((args) => args.join(' '));

  /** Plays pointer events, `t` being each one's time in ms. */
  function gesture(events) {
    const state = createGestureState();
    return events.flatMap(([x, y, action, t]) => mapIosInput(at(x, y, action), SCREEN, state, t));
  }

  it('turns a quick touch that stays put into a tap', () => {
    assert.deepEqual(lines(gesture([[0.5, 0.5, 'down', 0], [0.505, 0.5, 'move', 50], [0.505, 0.5, 'up', 120]])), ['tap -x 201 -y 437']);
    // Points stay on the screen: AXe refuses a point off it.
    assert.deepEqual(lines(gesture([[1, 1, 'down', 0], [1, 1, 'up', 10]])), ['tap -x 401 -y 873']);
  });

  it('turns a touch held in place into a long press, replayed as long as it lasted (5 s at most)', () => {
    assert.deepEqual(lines(gesture([[0.5, 0.5, 'down', 1000], [0.5, 0.5, 'up', 1800]])), ['tap -x 201 -y 437 --hold 0.8']);
    assert.deepEqual(lines(gesture([[0.5, 0.5, 'down', 0], [0.5, 0.5, 'up', 9000]])), ['tap -x 201 -y 437 --hold 5']);
  });

  it('turns a touch that moved into a draw through at most 40 of its points, over the time it took', () => {
    const [draw] = lines(gesture([[0.5, 0.8, 'down', 0], [0.5, 0.6, 'move', 200], [0.5, 0.4, 'move', 400], [0.5, 0.4, 'up', 600]]));
    assert.equal(draw, 'draw 201,699 201,524 201,350 --duration 0.6');

    const events = [[0.1, 0.5, 'down', 0]];
    for (let i = 1; i <= 200; i++) events.push([0.1 + i * 0.004, 0.5, 'move', i * 5]);
    events.push([0.9, 0.5, 'up', 1005]);
    const [long] = lines(gesture(events));
    const points = long.split(' ').slice(1, -2);
    assert.equal(points.length, 40);
    assert.equal(points[0], '40,437');
    assert.equal(points.at(-1), '362,437');
    assert.match(long, /--duration 1$/);

    assert.match(lines(gesture([[0.1, 0.5, 'down', 0], [0.9, 0.5, 'up', 20]]))[0], /--duration 0\.1$/, 'a flick');
    assert.match(lines(gesture([[0.1, 0.5, 'down', 0], [0.9, 0.5, 'up', 60000]]))[0], /--duration 5$/);
  });

  it('drops movement that starts at the top or bottom edge, and lets taps there through', () => {
    assert.deepEqual(gesture([[0.5, 0.99, 'down', 0], [0.5, 0.6, 'up', 300]]), [{ kind: 'notice', notice: 'edge' }]);
    assert.deepEqual(gesture([[0.5, 0.01, 'down', 0], [0.5, 0.3, 'up', 300]]), [{ kind: 'notice', notice: 'edge' }]);
    assert.deepEqual(lines(gesture([[0.5, 0.99, 'down', 0], [0.5, 0.99, 'up', 100]])), ['tap -x 201 -y 865']);
    // Ending near an edge is fine: only the start leaves the app.
    assert.equal(lines(gesture([[0.5, 0.5, 'down', 0], [0.5, 0.995, 'up', 300]])).length, 1);
  });

  it('touches with the left button only, plays a touch whose up was lost, and needs the screen size', () => {
    const state = createGestureState();
    assert.deepEqual(mapIosInput(at(0.5, 0.5, 'down', { button: 'right' }), SCREEN, state, 0), []);
    assert.deepEqual(mapIosInput(at(0.5, 0.5, 'up'), SCREEN, state, 10), []);
    mapIosInput(at(0.25, 0.25, 'down'), SCREEN, state, 0);
    assert.deepEqual(lines(mapIosInput(at(0.5, 0.5, 'down'), SCREEN, state, 100)), ['tap -x 101 -y 219']);
    assert.deepEqual(mapIosInput(at(0.5, 0.5, 'down'), null, createGestureState(), 0), []);
    assert.deepEqual(mapIosInput(at('x', 0.5, 'down'), SCREEN, createGestureState(), 0), []);
  });

  it('turns wheel steps into swipes against the scroll, adding up the small ones', () => {
    const state = createGestureState();
    const wheel = (dy, t, y = 0.5) => mapIosInput({ type: 'wheel', x: 0.5, y, dx: 0, dy }, SCREEN, state, t);
    assert.deepEqual(lines(wheel(120, 0)), ['swipe --start-x 201 --start-y 437 --end-x 201 --end-y 317 --duration 0.3 --delta 15']);
    assert.deepEqual(lines(wheel(-120, 1000)), ['swipe --start-x 201 --start-y 437 --end-x 201 --end-y 557 --duration 0.3 --delta 15']);
    // A trackpad: a few pixels at a time, held back until they add up.
    assert.deepEqual(wheel(4, 2000), []);
    assert.deepEqual(wheel(4, 2016), []);
    assert.equal(wheel(4, 2032).length, 1);
    // A pause starts over: the leftover of the last scroll is not added to the next.
    assert.deepEqual(wheel(8, 3000), []);
    assert.deepEqual(wheel(8, 4000), []);
    // At most 60% of the screen per swipe, and never past its edge.
    assert.match(lines(wheel(5000, 5000))[0], /--end-y 0 /);
    assert.deepEqual(wheel(120, 6000, 0.99), [{ kind: 'notice', notice: 'edge' }]);
  });

  it('merges waiting text for the same keyboard mode into one type, and waiting scrolls into one swipe', () => {
    const text = { kind: 'text', text: 'ab', mode: 'en' };
    assert.ok(mergeItem(text, { kind: 'text', text: 'c', mode: 'en' }));
    assert.ok(mergeItem(text, { kind: 'text', text: ' 1', mode: 'any' }));
    assert.equal(text.text, 'abc 1');
    assert.equal(mergeItem(text, { kind: 'text', text: 'dkssud', mode: 'ko' }), false, 'another mode starts a new run');
    const digits = { kind: 'text', text: '12 ', mode: 'any' };
    assert.ok(mergeItem(digits, { kind: 'text', text: 'rk', mode: 'ko' }));
    assert.deepEqual(digits, { kind: 'text', text: '12 rk', mode: 'ko' }, 'digits take the mode of what follows');
    const swipe = { kind: 'swipe', x: 201, y: 437, dx: 0, dy: 40, width: 402, height: 874 };
    assert.ok(mergeItem(swipe, { kind: 'swipe', x: 9, y: 9, dx: 0, dy: 60, width: 402, height: 874 }));
    assert.deepEqual(axeCommands(swipe), [
      ['swipe', '--start-x', '201', '--start-y', '437', '--end-x', '201', '--end-y', '337', '--duration', '0.3', '--delta', '13'],
    ]);
    assert.equal(mergeItem({ kind: 'command', args: ['key', '40'] }, { kind: 'command', args: ['key', '40'] }), false);
    assert.equal(mergeItem(undefined, text), false);
  });

  const key = (name, modifiers = 0, action = 'down') => mapIosInput({ type: 'key', action, key: name, code: '', modifiers }, SCREEN, createGestureState(), 0);

  it('sends named keys by HID code, on the way down only', () => {
    assert.deepEqual(lines(key('Enter')), ['key 40']);
    assert.deepEqual(lines(key('Backspace')), ['key 42']);
    assert.deepEqual(lines(key('Tab')), ['key 43']);
    assert.deepEqual(lines(key('Escape')), ['key 41']);
    assert.deepEqual(['ArrowRight', 'ArrowLeft', 'ArrowDown', 'ArrowUp'].flatMap((k) => lines(key(k))), ['key 79', 'key 80', 'key 81', 'key 82']);
    assert.deepEqual(['Delete', 'Home', 'End', 'PageUp', 'PageDown'].flatMap((k) => lines(key(k))), ['key 76', 'key 74', 'key 77', 'key 75', 'key 78']);
    assert.deepEqual(key('Enter', 0, 'up'), []);
    assert.deepEqual(key('Shift', 8), []);
    assert.deepEqual(key('a').flatMap(axeCommands), [['type', '--', 'a']]);
    assert.deepEqual(key('A', 8).flatMap(axeCommands), [['type', '--', 'A']]);
  });

  it('sends Cmd or Ctrl with a letter or digit as a Cmd combo, Shift and Option held too, but never Cmd+V', () => {
    assert.deepEqual(lines(key('a', 4)), [`key-combo --modifiers ${HID.LEFT_GUI} --key 4`]);
    assert.deepEqual(lines(key('z', 2)), ['key-combo --modifiers 227 --key 29']);
    assert.deepEqual(lines(key('1', 4)), ['key-combo --modifiers 227 --key 30']);
    assert.deepEqual(lines(key('0', 4)), ['key-combo --modifiers 227 --key 39']);
    assert.deepEqual(lines(key('Z', 4 | 8)), ['key-combo --modifiers 227,225 --key 29'], 'Cmd+Shift+Z stays redo');
    assert.deepEqual(lines(key('a', 4 | 1)), ['key-combo --modifiers 227,226 --key 4']);
    assert.deepEqual(key('v', 4), [{ kind: 'notice', notice: 'paste' }]);
    assert.deepEqual(key('V', 2), [{ kind: 'notice', notice: 'paste' }]);
    assert.deepEqual(key('V', 4 | 8 | 1), [{ kind: 'notice', notice: 'paste' }], 'paste and match style pastes too');
    assert.deepEqual(key('/', 4), []);
    assert.deepEqual([letterOrDigitHid('a'), letterOrDigitHid('Z'), letterOrDigitHid('9'), letterOrDigitHid('-')], [4, 29, 38, null]);
  });

  it('types ASCII text, line breaks and tabs as keys, and holds back anything else', () => {
    const text = (value) => mapIosInput({ type: 'text', text: value }, SCREEN, createGestureState(), 0);
    const commands = (value) => text(value).flatMap(axeCommands);
    // argv, no shell: quotes and dashes go as they are; `--` keeps a leading dash from being an option.
    assert.deepEqual(commands(`it's "ok" $HOME`), [['type', '--', `it's "ok" $HOME`]]);
    assert.deepEqual(commands('-v'), [['type', '--', '-v']]);
    assert.deepEqual(commands('a\r\nb\tc\n'), [['type', '--', 'a'], ['key', '40'], ['type', '--', 'b'], ['key', '43'], ['type', '--', 'c'], ['key', '40']]);
    // Without a Korean keyboard (the simulator's language is not Korean), Hangul has nowhere to go.
    assert.deepEqual(text('안녕하세요'), [{ kind: 'notice', notice: 'hangul' }]);
    assert.deepEqual(key('한'), [{ kind: 'notice', notice: 'hangul' }]);
    assert.deepEqual(text('café'), [{ kind: 'notice', notice: 'non-ascii' }], 'dropped whole, as AXe would refuse it');
    assert.deepEqual(text('ok 👍'), [{ kind: 'notice', notice: 'non-ascii' }]);
    const long = commands('x'.repeat(MAX_TYPE_CHARS + 10));
    assert.equal(long.length, 2);
    assert.deepEqual(long[1], ['type', '--', 'xxxxxxxxxx']);
  });

  it('has no navigation', () => {
    assert.deepEqual(mapIosInput({ type: 'nav', action: 'back' }, SCREEN, createGestureState(), 0), []);
    assert.deepEqual(mapIosInput({ type: 'nope' }, SCREEN, createGestureState(), 0), []);
    assert.deepEqual(mapIosInput(null, SCREEN, createGestureState(), 0), []);
  });
});

describe('Korean input (2-set keys, keyboard modes)', () => {
  const keys = (value) => [...value].map((ch) => hangulKeys(ch) ?? ch).join('');
  const ko = (value, options = { hangul: true }) => mapIosInput({ type: 'text', text: value }, SCREEN, createGestureState(), 0, options);
  const runs = (items) => items.map((i) => (i.kind === 'text' ? `${i.mode}:${i.text}` : i.kind === 'command' ? i.args.join(' ') : i.kind));

  it('turns Hangul into the keys of the 2-set layout', () => {
    assert.equal(keys('에뮬레이터 화면 공유'), 'dpabffpdlxj ghkaus rhddb');
    assert.equal(keys('꿨'), 'RnjT', 'ㄲ and ㅆ shifted, ㅝ as two keys');
    assert.equal(keys('꿧'), 'Rnjt');
    assert.equal(keys('닭'), 'ekfr', 'a final cluster as two keys');
    assert.equal(keys('얘'), 'dO');
    assert.equal(keys('없'), 'djqt');
    assert.equal(keys('쀍'), 'Qnpfr');
    assert.equal(keys('ㅋㅋㅘㄳ'), 'zzhkrt', 'jamo on their own');
    assert.equal(hangulKeys('ㆍ'), null, 'archaic jamo have no key');
    assert.equal(hangulKeys('a'), null);
  });

  it('splits text into runs by the keyboard mode each needs; digits, space and punctuation fit either', () => {
    assert.deepEqual(runs(ko('에뮬레이터 화면 공유')), ['ko:dpabffpdlxj ghkaus rhddb']);
    assert.deepEqual(runs(ko('iOS 공유 demo!')), ['en:iOS ', 'ko:rhddb ', 'en:demo!']);
    assert.deepEqual(runs(ko('2026년 11월')), ['ko:2026sus 11dnjf']);
    assert.deepEqual(runs(ko('123 ...')), ['any:123 ...']);
    assert.deepEqual(runs(ko('가\n나')), ['ko:rk', 'key 40', 'ko:sk']);
    assert.deepEqual(ko('한글 é'), [{ kind: 'notice', notice: 'non-ascii' }]);
  });

  it('does not pass on Ctrl+Space or Cmd+Space: the text picks the mode, and Cmd+Space is Spotlight', () => {
    const key = (modifiers) =>
      mapIosInput({ type: 'key', action: 'down', key: ' ', code: 'Space', modifiers }, SCREEN, createGestureState(), 0, { hangul: true });
    assert.deepEqual(key(2), []);
    assert.deepEqual(key(4), []);
    assert.deepEqual(CAPS_LOCK, ['key', '57']);
    assert.deepEqual(PREVIOUS_INPUT_SOURCE, ['key-combo', '--modifiers', '224', '--key', '44']);
  });
});

describe('simctl', () => {
  it('lists booted iOS simulators only', () => {
    const json = JSON.stringify({
      devices: {
        'com.apple.CoreSimulator.SimRuntime.iOS-26-1': [
          { udid: 'AAAA-1', name: 'iPhone 17', state: 'Booted', isAvailable: true },
          { udid: 'AAAA-2', name: 'iPad Air', state: 'Shutdown' },
        ],
        'com.apple.CoreSimulator.SimRuntime.watchOS-26-1': [{ udid: 'WWWW-1', name: 'Apple Watch', state: 'Booted' }],
      },
    });
    assert.deepEqual(parseBootedSimulators(json), [{ udid: 'AAAA-1', name: 'iPhone 17', runtime: 'iOS 26.1' }]);
    assert.deepEqual(parseBootedSimulators('{"devices":{}}'), []);
  });

  it('reads app names and finds an app by the name on screen', () => {
    const apps = parseAppList({
      'com.apple.mobilesafari': { CFBundleDisplayName: 'Safari', CFBundleName: 'MobileSafari', ApplicationType: 'System' },
      'com.example.safari': { CFBundleName: 'Safari', ApplicationType: 'User' },
      'com.example.app': { CFBundleDisplayName: '', CFBundleName: 'My App', ApplicationType: 'User' },
    });
    assert.deepEqual(apps.get('com.apple.mobilesafari'), { bundleId: 'com.apple.mobilesafari', displayName: 'Safari', name: 'MobileSafari', user: false });
    assert.deepEqual(appNamesOf(apps.get('com.example.app')), ['My App']);
    assert.equal(findAppByName(apps, 'my app ')?.bundleId, 'com.example.app');
    assert.equal(findAppByName(apps, 'Safari')?.bundleId, 'com.example.safari', 'what the user installed wins');
    assert.equal(findAppByName(apps, 'SpringBoard'), null);
    assert.equal(findAppByName(apps, ''), null);
  });

  it('reads the pid simctl launch prints, and the simulator language', () => {
    assert.equal(parseLaunchPid('com.apple.mobilesafari: 34990\n'), 34990);
    assert.equal(parseLaunchPid('An error was encountered processing the command'), null);
    assert.deepEqual(parseAppleLanguages('(\n    "ko-KR"\n)\n'), ['ko-KR']);
    assert.deepEqual(parseAppleLanguages('(\n    "en-US",\n    "ko-KR"\n)'), ['en-US', 'ko-KR']);
    assert.deepEqual(parseAppleLanguages(''), []);
    assert.equal(hasKoreanKeyboard('ko-KR'), true);
    assert.equal(hasKoreanKeyboard('ko'), true);
    assert.equal(hasKoreanKeyboard('en-US'), false);
    assert.equal(hasKoreanKeyboard(null), false);
  });

  it('counts the pasteboard sync as on unless it is explicitly off', () => {
    assert.equal(parsePasteboardSync('1\n'), true);
    assert.equal(parsePasteboardSync('0\n'), false);
    assert.equal(parsePasteboardSync(''), true);
  });

  it('pipes listapps through plutil, launches, and reads the pasteboard setting', async () => {
    const calls = [];
    const outputs = {
      'xcrun simctl listapps': '{ "com.example.app" = { CFBundleDisplayName = "My App"; }; }',
      'plutil -convert json': '{"com.example.app":{"CFBundleDisplayName":"My App"}}',
      'xcrun simctl list': '{"devices":{}}',
      'xcrun simctl launch': 'com.example.app: 4242\n',
      'xcrun simctl spawn': '(\n    "ko-KR"\n)\n',
    };
    const simctl = new Simctl({
      run: async (file, args, options = {}) => {
        calls.push([file, ...args, options.input ?? null]);
        if (file === 'defaults') throw new Error('The domain/default pair does not exist');
        return outputs[[file, ...args.slice(0, 2)].join(' ')] ?? '';
      },
    });
    assert.equal((await simctl.apps('AAAA-1')).get('com.example.app').displayName, 'My App');
    assert.deepEqual(calls[1], ['plutil', '-convert', 'json', '-o', '-', '-', outputs['xcrun simctl listapps']]);
    assert.equal(await simctl.launch('AAAA-1', 'com.example.app'), 4242);
    assert.deepEqual(calls[2], ['xcrun', 'simctl', 'launch', 'AAAA-1', 'com.example.app', null]);
    assert.deepEqual(await simctl.languages('AAAA-1'), ['ko-KR']);
    assert.deepEqual(calls[3], ['xcrun', 'simctl', 'spawn', 'AAAA-1', 'defaults', 'read', '-g', 'AppleLanguages', null]);
    await assert.rejects(simctl.launch('AAAA-1', '-x'), /번들 ID/);
    assert.equal(await simctl.pasteboardSyncOn(), true, 'never set means Simulator’s default, on');
    assert.deepEqual(await simctl.bootedSimulators(), []);
  });
});

describe('AXe discovery', () => {
  const versions = { '/opt/homebrew/bin/axe': '2.3.1', '/usr/local/bin/axe': '2.0.0', '/npm/bin/axe': '4.10.2', '/old/axe': '1.6.0' };
  const versionOf = (p) => versions[p] ?? '';

  it('finds AXe 2.x on PATH, then in Homebrew, and skips another program called axe', () => {
    const exists = (p) => p in versions;
    assert.deepEqual(findAxe({ env: { PATH: '/opt/homebrew/bin' }, exists, versionOf }), { path: '/opt/homebrew/bin/axe', version: '2.3.1' });
    assert.deepEqual(findAxe({ env: { PATH: '/npm/bin' }, exists, versionOf }), { path: '/opt/homebrew/bin/axe', version: '2.3.1' });
    assert.deepEqual(findAxe({ env: { QEPLOY_AXE: '/usr/local/bin/axe', PATH: '/opt/homebrew/bin' }, exists, versionOf }), {
      path: '/usr/local/bin/axe',
      version: '2.0.0',
    });
  });

  it('finds a manual install in ~/.local/bin, and reads the version with its leading v', () => {
    const exists = (p) => p === '/Users/me/.local/bin/axe';
    assert.deepEqual(findAxe({ env: { PATH: '/usr/bin' }, home: '/Users/me', exists, versionOf: () => 'v2.0.4\n' }), {
      path: '/Users/me/.local/bin/axe',
      version: '2.0.4',
    });
  });

  it('says how to install it, and why the one found does not do', () => {
    assert.throws(() => findAxe({ env: { PATH: '/nowhere' }, exists: () => false, versionOf }), (e) => e.message === NO_AXE_MESSAGE);
    assert.match(NO_AXE_MESSAGE, /brew install cameroncooke\/axe\/axe/);
    assert.throws(() => findAxe({ env: { PATH: '/npm/bin' }, exists: (p) => p === '/npm/bin/axe', versionOf }), /AXe 2\.x 가 아닙니다 \(버전 4\.10\.2\).*QEPLOY_AXE/);
    assert.throws(() => findAxe({ env: { QEPLOY_AXE: '/old/axe' }, exists: (p) => p === '/old/axe', versionOf }), /1\.6\.0/);
    assert.throws(() => findAxe({ env: { QEPLOY_AXE: '/gone/axe' }, exists: () => false, versionOf }), /QEPLOY_AXE/);
    assert.throws(() => findAxe({ env: { QEPLOY_AXE: '/x/axe' }, exists: () => true, versionOf: () => '' }), /버전을 읽지 못했습니다/);
  });
});

// ── the AXe processes ────────────────────────────────────────────────────────

function fakeChild() {
  const child = new EventEmitter();
  child.stdout = new PassThrough();
  child.stderr = new PassThrough();
  child.kill = (signal) => {
    child.killedWith = signal;
    setImmediate(() => child.emit('close', null, signal));
    return true;
  };
  return child;
}

function spawner(child) {
  const spawned = [];
  const spawnImpl = (file, args, options) => {
    spawned.push({ file, args, options });
    return child;
  };
  return { spawned, spawnImpl };
}

describe('AXe stream-video process', () => {
  it('runs stream-video in raw JPEG format and hands over each frame', async () => {
    const child = fakeChild();
    const { spawned, spawnImpl } = spawner(child);
    const video = new AxeVideo({ axePath: '/opt/homebrew/bin/axe', udid: 'AAAA-1', fps: 15, scale: 0.5, spawnImpl });
    const frames = [];
    const closed = [];
    await video.start({ onFrame: (f) => frames.push(Buffer.from(f)), onClose: (e) => closed.push(e) });
    assert.deepEqual(spawned[0].args, ['stream-video', '--udid', 'AAAA-1', '--format', 'raw', '--fps', '15', '--scale', '0.5', '--quality', '70']);
    assert.equal(spawned[0].options.detached, true, 'its own process group: Ctrl+C is the CLI’s to handle');

    const stream = rawStream([fakeJpeg(201, 437, 1), fakeJpeg(201, 437, 2)]);
    child.stdout.write(stream.subarray(0, 30));
    child.stdout.write(stream.subarray(30));
    await until(() => frames.length === 2);
    assert.deepEqual(frames[1], fakeJpeg(201, 437, 2));

    // The status lines AXe 2.0.4 prints, then why it stopped.
    child.stderr.write(
      'Starting screenshot-based video stream from simulator AAAA-1...\nFormat: raw, FPS: 15, Quality: 70, Scale: 0.5\n' +
        'Captured 15 frames (14.4 FPS actual)\nError capturing frame: simulator is shutting down\nCaptured 30 frames (14.2 FPS actual)\n'
    );
    await tick();
    child.emit('close', 1, null);
    assert.equal(closed.length, 1);
    assert.match(closed[0].message, /stream-video.*Scale: 0\.5 \/ Error capturing frame: simulator is shutting down$/);
  });

  it('ends on a stream that is not raw JPEG, and says nothing when stopped on purpose', async () => {
    const bad = fakeChild();
    const closed = [];
    const video = new AxeVideo({ axePath: '/axe', udid: 'AAAA-1', fps: 15, scale: 0.5, spawnImpl: spawner(bad).spawnImpl });
    await video.start({ onFrame: () => {}, onClose: (e) => closed.push(e) });
    bad.stdout.write(Buffer.from('--frame\r\nContent-Type: image/jpeg\r\n'));
    await until(() => closed.length === 1);
    assert.match(closed[0].message, /형식에 맞지 않습니다/);

    const child = fakeChild();
    const quiet = [];
    const stopped = new AxeVideo({ axePath: '/axe', udid: 'AAAA-1', fps: 15, scale: 0.5, spawnImpl: spawner(child).spawnImpl });
    await stopped.start({ onFrame: () => {}, onClose: (e) => quiet.push(e) });
    await stopped.stop();
    await tick();
    assert.equal(child.killedWith, 'SIGTERM');
    assert.deepEqual(quiet, []);
  });
});

describe('AXe commands', () => {
  /** A runTool stand-in: each call waits until the test answers it. */
  function fakeRun() {
    const calls = [];
    const run = (file, args, options) =>
      new Promise((resolve, reject) => {
        calls.push({ file, args, options, resolve, reject });
      });
    return { calls, run };
  }

  it('runs one process per command, --output json first and --udid right after the command, one at a time', async () => {
    const { calls, run } = fakeRun();
    const commands = new Axe('/Users/me/.local/bin/axe', { run }).commands('AAAA-1');
    assert.ok(commands instanceof AxeCommands);

    const tap = commands.request(['tap', '-x', '201', '-y', '437']);
    const type = commands.request(['type', '--', '-v']);
    await tick();
    assert.equal(calls.length, 1, 'the second waits for the first');
    assert.equal(calls[0].file, '/Users/me/.local/bin/axe');
    assert.deepEqual(calls[0].args, ['--output', 'json', 'tap', '--udid', 'AAAA-1', '-x', '201', '-y', '437']);
    calls[0].resolve(`${JSON.stringify(okReply('tap'))}\n`);
    assert.equal((await tap).data.result.outcome, 'completed');

    await until(() => calls.length === 2);
    assert.deepEqual(calls[1].args, ['--output', 'json', 'type', '--udid', 'AAAA-1', '--', '-v'], '--udid ahead of --');
    // A failed command exits nonzero but prints its envelope: that is an answer.
    const failure = new Error('axe --output json type 실패: Error: No simulator has UDID AAAA-1');
    failure.stdout = '{"command":"type","data":{},"didError":true,"error":{"code":"targetNotFound","message":"No simulator has UDID AAAA-1"}}\n';
    calls[1].reject(failure);
    assert.equal((await type).error.code, 'targetNotFound');
  });

  it('fails a command AXe could not run, keeps going, and refuses new ones once stopped', async () => {
    const { calls, run } = fakeRun();
    const commands = new AxeCommands({ axePath: '/axe', udid: 'AAAA-1', run });
    const lost = commands.request(['key', '40']);
    const next = commands.request(['displays']);
    await tick();
    calls[0].reject(Object.assign(new Error('spawn /axe ENOENT'), { stdout: '' }));
    await assert.rejects(lost, /ENOENT/);
    await until(() => calls.length === 2);
    calls[1].resolve('not json');
    await assert.rejects(next, /displays 의 출력을 읽지 못했습니다/);

    commands.stop();
    await assert.rejects(commands.request(['tap', '-x', '1', '-y', '1']), /끝냈습니다/);
    assert.equal(calls.length, 2);
  });
});

// ── the session over a real tunnel socket ────────────────────────────────────

async function tunnelServer() {
  const wss = new WebSocketServer({ host: '127.0.0.1', port: 0 });
  await once(wss, 'listening');
  const arrived = [];
  const waiting = [];
  wss.on('connection', (socket) => {
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

/** Stands in for AxeVideo: the test drives the frames. */
function fakeVideo() {
  const video = {
    handlers: null,
    stopped: false,
    start: async (handlers) => {
      video.handlers = handlers;
    },
    stop: async () => {
      video.stopped = true;
    },
  };
  return video;
}

const APP_PID = 4242;

/**
 * Stands in for AxeCommands: records every command as one string; `displays` answers with `screen`,
 * `describe-ui` with the next of `fronts` ([label, pid]; the last one repeats) and the `keyboard` in
 * use; a verb in `fail` fails. The keyboard behaves as measured: Caps Lock swaps ko and en (or, with
 * `capsSwitches` false, turns caps lock on), Ctrl+Space leaves emoji for `beforeEmoji`, and after a
 * switch the next `lag` reads still show the old keyboard. `typed` keeps each text with the keyboard
 * it went into.
 */
function fakeCommands({ fronts = [['My App', APP_PID]], fail = {}, keyboard = null, capsSwitches = true, beforeEmoji = 'ko', lag = 0 } = {}) {
  const commands = {
    sent: [],
    typed: [],
    fronts: [...fronts],
    screen: { ...SCREEN },
    keyboard,
    shown: keyboard, // what describe-ui reports, which can trail `keyboard`
    lagging: 0,
    capsLock: false,
    stopped: false,
    request: async (args) => {
      const line = args.join(' ');
      commands.sent.push(line);
      const [verb] = args;
      if (fail[verb]) return { command: verb, data: {}, didError: true, error: fail[verb] };
      if (verb === 'displays') return displaysReply(commands.screen);
      if (verb === 'describe-ui') {
        const [label, pid] = commands.fronts.length > 1 ? commands.fronts.shift() : commands.fronts[0];
        if (commands.lagging > 0) commands.lagging--;
        else commands.shown = commands.keyboard;
        return describeReply(label, pid, commands.shown);
      }
      if (line === CAPS_LOCK.join(' ')) {
        if (capsSwitches && (commands.keyboard === 'ko' || commands.keyboard === 'en')) {
          commands.keyboard = commands.keyboard === 'ko' ? 'en' : 'ko';
          commands.lagging = lag;
        } else commands.capsLock = !commands.capsLock;
      }
      if (line === PREVIOUS_INPUT_SOURCE.join(' ') && commands.keyboard === 'emoji') commands.keyboard = beforeEmoji;
      if (verb === 'type') commands.typed.push(`${commands.keyboard}:${args.at(-1)}`);
      return okReply(verb);
    },
    stop: () => {
      commands.stopped = true;
    },
  };
  return commands;
}

const inputsOf = (commands) => commands.sent.filter((c) => c !== 'displays' && c !== 'describe-ui');

function newIosSession(overrides = {}) {
  const logs = [];
  const video = overrides.video ?? fakeVideo();
  const commands = overrides.commands ?? fakeCommands();
  const session = new IosShareSession({
    video,
    commands,
    simctl: { launch: async () => APP_PID },
    udid: 'AAAA-1',
    bundleId: 'com.example.app',
    appPid: APP_PID,
    targetLabel: 'iPhone 17 · com.example.app',
    deviceName: 'iPhone 17',
    tunnelUrl: 'ws://127.0.0.1:1/unused?v=1',
    token: 'qp_test',
    cliVersion: '9.9.9',
    askControl: async () => true,
    log: (text) => logs.push(text),
    pingIntervalMs: 0,
    guardIntervalMs: 60_000,
    ...overrides,
  });
  cleanups.push(async () => session.stop('test-cleanup'));
  return { session, video, commands, logs };
}

describe('ios share session (§13)', () => {
  it('announces IOS, sends the size before any frame, again on reconnect, and the inputs in order', { timeout: 10000 }, async () => {
    const server = await tunnelServer();
    const { session, video, commands } = newIosSession({ tunnelUrl: server.url, backoff: () => 20 });
    const done = session.run();
    let conn = await server.nextConnection();

    const hello = await conn.next();
    assert.equal(hello.kind, KIND.HELLO);
    assert.deepEqual(hello.json, { cliVersion: '9.9.9', kind: 'IOS', target: 'iPhone 17 · com.example.app' });
    assert.deepEqual(commands.sent, ['displays'], 'the size in points is read before anything else');
    assert.deepEqual(session.points, SCREEN);

    // Frames before WELCOME are kept, not sent; WELCOME gets the config and the latest of them.
    video.handlers.onFrame(fakeJpeg(201, 437, 1));
    video.handlers.onFrame(fakeJpeg(201, 437, 2));
    conn.send(KIND.WELCOME, { shareId: 7, expiresAt: '2026-11-13T13:00:00Z' });
    const config = await conn.next();
    assert.equal(config.kind, KIND.SCREEN_CONFIG);
    assert.deepEqual(config.json, { format: 'jpeg', width: 201, height: 437, url: 'com.example.app', title: 'iPhone 17' });
    const first = await conn.next();
    assert.equal(first.kind, KIND.SCREEN_FRAME);
    assert.deepEqual(first.payload, fakeJpeg(201, 437, 2), 'the JPEG as it came, nothing in front');

    // A still screen (the same bytes again) is not resent; a change is.
    video.handlers.onFrame(fakeJpeg(201, 437, 2));
    video.handlers.onFrame(fakeJpeg(201, 437, 3));
    assert.deepEqual((await conn.next()).payload, fakeJpeg(201, 437, 3));

    // A rotation: a new config first, and the size in points is read again.
    commands.screen = { width: 874, height: 402 };
    video.handlers.onFrame(fakeJpeg(437, 201, 4));
    const rotated = await conn.next();
    assert.equal(rotated.kind, KIND.SCREEN_CONFIG);
    assert.equal(rotated.json.width, 437);
    assert.equal((await conn.next()).kind, KIND.SCREEN_FRAME);
    await until(() => session.points.width === 874);

    conn.send(KIND.INPUT, { type: 'pointer', action: 'down', x: 0.5, y: 0.5, button: 'left' });
    conn.send(KIND.INPUT, { type: 'pointer', action: 'up', x: 0.5, y: 0.5, button: 'left' });
    conn.send(KIND.INPUT, { type: 'key', action: 'down', key: 'h', code: 'KeyH', modifiers: 0 });
    conn.send(KIND.INPUT, { type: 'text', text: 'i' });
    conn.send(KIND.INPUT, { type: 'key', action: 'down', key: 'Enter', code: 'Enter', modifiers: 0 });
    conn.send(KIND.INPUT, { type: 'nav', action: 'back' });
    await until(() => commands.sent.includes('key 40'));
    const inputs = inputsOf(commands);
    assert.equal(inputs[0], 'tap -x 437 -y 201', 'in points of the rotated screen');
    assert.equal(inputs.at(-1), 'key 40');
    // Typed in order; letters that waited behind the tap may have gone out as one `type`.
    assert.ok(['type -- h|type -- i', 'type -- hi'].includes(inputs.slice(1, -1).join('|')), inputs.join(' / '));

    // Reconnect: the new socket gets the config and the latest frame again.
    conn.socket.terminate();
    conn = await server.nextConnection();
    assert.equal((await conn.next()).kind, KIND.HELLO);
    conn.send(KIND.WELCOME, { shareId: 7, expiresAt: '2026-11-13T13:00:00Z' });
    assert.equal((await conn.next()).json.width, 437);
    assert.deepEqual((await conn.next()).payload, fakeJpeg(437, 201, 4));

    conn.send(KIND.GOODBYE, { reason: 'revoked' });
    assert.deepEqual(await done, { reason: 'revoked' });
    assert.equal(video.stopped, true);
    assert.equal(commands.stopped, true);
  });

  it('skips frames while the tunnel is backed up, and sends the latest once it is not', () => {
    const { session } = newIosSession();
    const sent = [];
    let buffered = 0;
    session.ready = true;
    session.sendRaw = (frame) => {
      sent.push(decodeFrame(frame));
      return true;
    };
    Object.defineProperty(session, 'bufferedAmount', { get: () => buffered });

    session.onJpeg(fakeJpeg(10, 20, 1));
    assert.deepEqual(
      sent.map((f) => f.kind),
      [KIND.SCREEN_CONFIG, KIND.SCREEN_FRAME]
    );
    buffered = HIGH_WATER_BYTES + 1;
    session.onJpeg(fakeJpeg(10, 20, 2));
    assert.equal(sent.length, 2, 'dropped');
    buffered = 0;
    session.onJpeg(fakeJpeg(10, 20, 2));
    assert.equal(sent.length, 3, 'the same picture again goes out: the viewer never got it');
    session.onJpeg(fakeJpeg(10, 20, 2));
    assert.equal(sent.length, 3, 'and not a second time');
    session.onJpeg(Buffer.from('not a jpeg'));
    assert.equal(sent.length, 3);
  });

  it('ends with device-exit when stream-video stops', async () => {
    const server = await tunnelServer();
    const { session, video, commands } = newIosSession({ tunnelUrl: server.url });
    const done = session.run();
    await server.nextConnection();
    const error = new Error('AXe 화면 전송(stream-video)이 끝났습니다.');
    video.handlers.onClose(error);
    assert.deepEqual(await done, { reason: 'device-exit', error });
    assert.equal(commands.stopped, true);
  });

  it('tells the owner once about input it holds back, and once per AXe error code', async () => {
    const commands = fakeCommands({ fail: { tap: { code: 'busy', message: 'Another AXe command holds the device' } } });
    const { session, logs } = newIosSession({ commands });
    session.points = { ...SCREEN };
    session.handleInput({ type: 'text', text: '안녕' });
    session.handleInput({ type: 'text', text: '하세요' });
    session.handleInput({ type: 'key', action: 'down', key: 'v', modifiers: 4 });
    session.handleInput({ type: 'key', action: 'down', key: 'v', modifiers: 4 });
    session.handleInput({ type: 'pointer', action: 'down', x: 0.5, y: 0.99, button: 'left' });
    session.handleInput({ type: 'pointer', action: 'up', x: 0.5, y: 0.5, button: 'left' });
    assert.equal(logs.filter((l) => l.includes('한글')).length, 1);
    assert.equal(logs.filter((l) => l.includes('Cmd+V')).length, 1);
    assert.equal(logs.filter((l) => l.includes('가장자리')).length, 1);
    assert.deepEqual(commands.sent, [], 'nothing of it reached the simulator');

    for (let i = 0; i < 2; i++) {
      session.handleInput({ type: 'pointer', action: 'down', x: 0.5, y: 0.5, button: 'left' });
      session.handleInput({ type: 'pointer', action: 'up', x: 0.5, y: 0.5, button: 'left' });
    }
    await until(() => commands.sent.length === 2);
    await tick();
    assert.equal(logs.filter((l) => l.includes('(busy)')).length, 1);
  });

  /** A Korean simulator session with its keyboard up, as when a field has focus. */
  function koreanSession(options) {
    const commands = fakeCommands(options);
    const { session, logs } = newIosSession({ commands, language: 'ko-KR', inputSourceSettleMs: 0, keyboardReadRetryMs: 0 });
    session.points = { ...SCREEN };
    const idle = () => until(() => !session.pumping && session.outbox.length === 0);
    const type = async (text) => {
      session.handleInput({ type: 'text', text });
      await idle();
    };
    return { session, commands, logs, type };
  }

  it('reads the keyboard before text that needs a mode, switches with Caps Lock, and reads it again', async () => {
    const { session, commands, type } = koreanSession({ keyboard: 'ko' });
    await type('2026 '); // the same in both modes: no read, no switch
    await type('iOS'); // English, mode unknown: read (ko), Caps Lock, read (en)
    await type(' share'); // known to be English: straight in
    await type('공유'); // Hangul: Caps Lock back, checked
    assert.deepEqual(commands.typed, ['ko:2026 ', 'en:iOS', 'en: share', 'ko:rhddb']);
    assert.deepEqual(inputsOf(commands), [
      'type -- 2026 ',
      'key 57',
      'type -- iOS',
      'type --  share',
      'key 57',
      'type -- rhddb',
    ]);
    assert.equal(commands.sent.filter((c) => c === 'describe-ui').length, 4);
    assert.equal(session.keyboardMode, 'ko');
  });

  it('does not switch when the keyboard already reads right', async () => {
    const { commands, type } = koreanSession({ keyboard: 'en' });
    await type('hello');
    assert.deepEqual(commands.typed, ['en:hello']);
    assert.ok(!commands.sent.includes('key 57'));
  });

  it('leaves the emoji keyboard with one Ctrl+Space, where Caps Lock does nothing', async () => {
    const { commands, type } = koreanSession({ keyboard: 'emoji', beforeEmoji: 'ko' });
    await type('hi');
    assert.deepEqual(commands.typed, ['en:hi']);
    assert.deepEqual(inputsOf(commands), ['key-combo --modifiers 224 --key 44', 'key 57', 'type -- hi']);
  });

  it('reads again when the keyboard still shows the old mode just after Caps Lock', async () => {
    const { commands, type } = koreanSession({ keyboard: 'ko', lag: 2 });
    await type('hi');
    assert.deepEqual(commands.typed, ['en:hi']);
    assert.equal(commands.sent.filter((c) => c === 'key 57').length, 1, 'not undone');
  });

  it('presses Caps Lock again and holds the text back when Caps Lock does not switch languages', async () => {
    const { commands, logs, type } = koreanSession({ keyboard: 'ko', capsSwitches: false });
    await type('hello');
    await type('world');
    assert.deepEqual(commands.typed, [], 'hello would have come out as ㅗ디ㅣㅐ');
    assert.equal(commands.capsLock, false, 'caps lock is left as it was');
    assert.equal(logs.filter((l) => l.includes('Caps Lock 언어 전환')).length, 1);
  });

  it('types without switching when no keyboard is up, and never switches on an English simulator', async () => {
    const { commands, type } = koreanSession({ keyboard: null });
    await type('hi');
    assert.deepEqual(commands.typed, ['null:hi'], 'no field has focus: the keys go nowhere, as they would by hand');

    const plain = fakeCommands({ keyboard: 'emoji' });
    const en = newIosSession({ commands: plain, language: 'en-US' });
    en.session.points = { ...SCREEN };
    en.session.handleInput({ type: 'text', text: 'hello' });
    en.session.handleInput({ type: 'text', text: '안녕' });
    await until(() => plain.sent.length === 1);
    await tick();
    assert.deepEqual(plain.sent, ['type -- hello']);
    assert.ok(en.logs.some((l) => l.includes('한국어가 아니어서')));
  });

  it('takes the keyboard mode from the guard’s reads too', async () => {
    const commands = fakeCommands({ keyboard: 'en' });
    const server = await tunnelServer();
    const { session } = newIosSession({ tunnelUrl: server.url, commands, language: 'ko-KR', guardIntervalMs: 10 });
    session.run();
    await until(() => session.keyboardMode === 'en');
  });

  it('reads the screen size when an input comes before any size is known', async () => {
    const { session, commands } = newIosSession();
    session.handleInput({ type: 'pointer', action: 'down', x: 0.5, y: 0.5, button: 'left' });
    await until(() => session.points !== null);
    assert.deepEqual(commands.sent, ['displays']);
  });

  it('brings the shared app back when another process is in front, the blank-labelled home screen included (§13.3)', async () => {
    const launched = [];
    const commands = fakeCommands({
      fronts: [
        ['My App', APP_PID],
        [' ', SPRINGBOARD_PID], // the home screen
        [' ', SPRINGBOARD_PID], // still there: launched again, not told again
        ['Safari', SAFARI_PID],
        ['설정', APP_PID], // a localised name on the app's own pid is the app
        ['My App', 5555], // relaunched by hand: a new pid, until launch reports it
        ['My App', 5555],
      ],
    });
    const server = await tunnelServer();
    const pids = [APP_PID, APP_PID, APP_PID, 5555];
    const { session, logs } = newIosSession({
      tunnelUrl: server.url,
      commands,
      simctl: {
        launch: async (udid, bundleId) => {
          launched.push([udid, bundleId]);
          return pids.shift();
        },
      },
      guardIntervalMs: 10,
    });
    session.run();
    await until(() => commands.fronts.length === 1);
    await new Promise((r) => setTimeout(r, 40));
    assert.equal(launched.length, 4);
    assert.deepEqual(launched[0], ['AAAA-1', 'com.example.app']);
    assert.equal(session.appPid, 5555);
    assert.deepEqual(
      logs.filter((l) => l.includes('다시 앞으로')),
      [
        '공유 중인 앱 밖(홈 화면)으로 나가 com.example.app 를 다시 앞으로 올렸습니다.',
        '공유 중인 앱 밖(Safari)으로 나가 com.example.app 를 다시 앞으로 올렸습니다.',
        '공유 중인 앱 밖(My App)으로 나가 com.example.app 를 다시 앞으로 올렸습니다.',
      ]
    );
  });

  it('learns the app’s pid by launching it when simctl did not print one, and leaves an unreadable screen alone', async () => {
    const launched = [];
    const commands = fakeCommands({ fail: { 'describe-ui': { code: 'readFailed' } } });
    const server = await tunnelServer();
    const { session, logs } = newIosSession({
      tunnelUrl: server.url,
      commands,
      appPid: null,
      simctl: { launch: async () => launched.push(1) && APP_PID },
      guardIntervalMs: 10,
    });
    session.run();
    await until(() => commands.sent.filter((c) => c === 'describe-ui').length >= 3);
    assert.equal(launched.length, 1, 'once, to learn it; a failed read launches nothing');
    assert.equal(session.appPid, APP_PID);
    assert.ok(!logs.some((l) => l.includes('다시 앞으로')));
  });

  it('does not look at the app in front with --any-app', async () => {
    const commands = fakeCommands({ fronts: [[' ', SPRINGBOARD_PID]] });
    const server = await tunnelServer();
    const { session } = newIosSession({ tunnelUrl: server.url, commands, guard: false, guardIntervalMs: 10 });
    session.run();
    await server.nextConnection();
    await new Promise((r) => setTimeout(r, 40));
    assert.deepEqual(commands.sent, ['displays']);
  });
});

// ── the command ──────────────────────────────────────────────────────────────

describe('qeploy share ios', () => {
  const share = {
    shareId: 11,
    viewUrl: 'http://k3x9.share.localhost:8080/',
    controlUrl: 'http://k3x9.share.localhost:8080/#c=secret',
    tunnelUrl: 'ws://localhost:8080/api/v1/device-shares/11/tunnel?v=1',
    expiresAt: '2026-11-13T13:00:00',
  };
  const SIM = { udid: 'A1B2C3D4-0000-4000-8000-000000000001', name: 'iPhone 17', runtime: 'iOS 26.1' };
  const APPS = parseAppList({
    'com.example.app': { CFBundleDisplayName: 'My App', CFBundleName: 'MyApp', ApplicationType: 'User' },
    'com.apple.mobilesafari': { CFBundleDisplayName: 'Safari', CFBundleName: 'MobileSafari', ApplicationType: 'System' },
    'com.apple.springboard': { CFBundleDisplayName: 'SpringBoard', CFBundleName: 'SpringBoard', ApplicationType: 'System' },
  });

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

  function fakeRuntime({
    platform = 'darwin',
    axe = { path: '/opt/homebrew/bin/axe', version: '2.0.4' },
    booted = [SIM],
    label = 'My App',
    describe = null,
    pasteboard = true,
    languages = ['ko-KR', 'en-US'],
    result = null,
    end,
  } = {}) {
    const rt = {
      platform,
      calls: [],
      stopped: [],
      interrupt: null,
      sessionOptions: null,
      videoOptions: null,
      findAxe: () => {
        if (!axe) throw new Error(NO_AXE_MESSAGE);
        return axe;
      },
      createSimctl: () => ({
        bootedSimulators: async () => booted,
        apps: async () => APPS,
        launch: async (udid, bundleId) => {
          rt.calls.push(['launch', udid, bundleId]);
          return 777;
        },
        languages: async () => languages,
        pasteboardSyncOn: async () => pasteboard,
      }),
      createAxe: (path) => ({
        commands: (udid) => ({
          request: async (args) => {
            rt.calls.push([args[0], path, udid]);
            if (describe) return describe();
            return describeReply(label, label === ' ' ? SPRINGBOARD_PID : APP_PID);
          },
          stop: () => rt.stopped.push('commands'),
        }),
        video: (options) => {
          rt.videoOptions = options;
          return { stop: async () => rt.stopped.push('video') };
        },
      }),
      createIosSession: (options) => {
        rt.sessionOptions = options;
        let resolve;
        const done = new Promise((r) => {
          resolve = r;
        });
        return {
          run: () => {
            if (result) return Promise.resolve(result);
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

  it('shares the app in front, guarded, warns about the pasteboard, and on Ctrl+C revokes and stops AXe', async () => {
    const runtime = fakeRuntime({ end: (rt) => rt.interrupt() });
    const { code, out, err, client } = await shareCli(['share', 'ios', '--fps', '10', '--scale', '0.4'], { runtime });
    assert.equal(code, EXIT.OK);
    assert.deepEqual(client.created, { kind: 'IOS', targetLabel: 'iPhone 17 · com.example.app', ttlMinutes: undefined });
    assert.match(out, /보기 링크/);
    assert.match(err, /\[주의\] Simulator 가 내 Mac 클립보드와 자동으로 동기화/);
    assert.match(err, /Automatically Sync Pasteboard/);

    const options = runtime.sessionOptions;
    assert.equal(options.bundleId, 'com.example.app');
    assert.equal(options.guard, true);
    assert.equal(options.udid, SIM.udid);
    assert.equal(options.deviceName, 'iPhone 17');
    assert.equal(options.appPid, APP_PID, 'the pid of the app read off the screen');
    assert.equal(options.language, 'ko-KR');
    assert.deepEqual(runtime.videoOptions, { udid: SIM.udid, fps: 10, scale: 0.4 });
    assert.deepEqual(runtime.calls[0], ['describe-ui', '/opt/homebrew/bin/axe', SIM.udid]);
    assert.ok(!runtime.calls.some(([name]) => name === 'launch'), 'the app is already in front');

    assert.deepEqual(client.revoked, [11]);
    assert.ok(runtime.stopped.includes('video') && runtime.stopped.includes('commands'));
    assert.match(err, /공유를 끝냈습니다\. 링크를 회수했습니다\. AXe 화면 전송을 끝냈습니다\./);
  });

  it('launches the app given with --app first, picks a simulator by --udid, and says nothing of a pasteboard not synced', async () => {
    const other = { udid: 'B1B2C3D4-0000-4000-8000-000000000002', name: 'iPad Air', runtime: 'iOS 26.1' };
    const runtime = fakeRuntime({ booted: [other, SIM], label: ' ', pasteboard: false, languages: [], end: (rt) => rt.interrupt() });
    const { code, err, client } = await shareCli(['share', 'ios', '--app', 'com.apple.mobilesafari', '--udid', SIM.udid.toLowerCase()], { runtime });
    assert.equal(code, EXIT.OK);
    assert.equal(client.created.targetLabel, 'iPhone 17 · com.apple.mobilesafari');
    assert.deepEqual(
      runtime.calls.filter(([name]) => name === 'launch'),
      [['launch', SIM.udid, 'com.apple.mobilesafari']]
    );
    assert.ok(!runtime.calls.some(([name]) => name === 'describe-ui'), 'no need to look at the screen');
    assert.equal(runtime.sessionOptions.appPid, 777, 'from what simctl launch printed');
    assert.equal(runtime.sessionOptions.language, null);
    assert.doesNotMatch(err, /\[주의\]/);
  });

  it('refuses before making a link: not a Mac, no AXe, no simulator, several, an unknown UDID, the home screen, a missing app', async () => {
    const failed = () => ({ command: 'describe-ui', data: {}, didError: true, error: { code: 'notBooted', message: 'Simulator is not booted' } });
    const cases = [
      [fakeRuntime({ platform: 'linux' }), [], /macOS 에서만/],
      [fakeRuntime({ axe: null }), [], /brew install cameroncooke\/axe\/axe/],
      [fakeRuntime({ booted: [] }), [], /실행 중인 iOS 시뮬레이터가 없습니다/],
      [fakeRuntime({ booted: [SIM, { ...SIM, udid: 'B-2', name: 'iPad Air' }] }), [], /여러 대입니다.*iPad Air.*--udid/],
      [fakeRuntime(), ['--udid', '00008110-001A2B3C4D5E6F'], /실제 기기는 공유하지 않습니다/],
      // The home screen: a blank label (as measured), and should it ever read "SpringBoard", that
      // name is listapps' entry for the home screen, still not an app to share.
      [fakeRuntime({ label: ' ' }), [], /홈 화면.*--app/],
      [fakeRuntime({ label: 'SpringBoard' }), [], /홈 화면.*--app/],
      [fakeRuntime({ describe: failed }), [], /읽지 못해.*not booted.*--app/],
      [fakeRuntime(), ['--app', 'com.nope.app'], /설치되어 있지 않습니다/],
    ];
    for (const [runtime, extra, message] of cases) {
      const { code, err, client } = await shareCli(['share', 'ios', ...extra], { runtime });
      assert.equal(code, EXIT.ERROR, String(message));
      assert.match(err, message);
      assert.equal(client.created, null, String(message));
    }
  });

  it('shares the whole simulator with --any-app, unguarded and with a warning', async () => {
    const runtime = fakeRuntime({ label: ' ', end: (rt) => rt.interrupt() });
    const { code, err, client } = await shareCli(['share', 'ios', '--any-app'], { runtime });
    assert.equal(code, EXIT.OK);
    assert.equal(client.created.targetLabel, 'iPhone 17 · 시뮬레이터 전체');
    assert.equal(runtime.sessionOptions.guard, false);
    assert.equal(runtime.sessionOptions.bundleId, null);
    assert.match(err, /--any-app.*localhost/);
  });

  it('words a lost simulator screen for iOS, and exits 1', async () => {
    const runtime = fakeRuntime({ result: { reason: 'device-exit', error: new Error('AXe 화면 전송(stream-video)이 끝났습니다.') } });
    const { code, err, client } = await shareCli(['share', 'ios'], { runtime });
    assert.equal(code, EXIT.ERROR);
    assert.match(err, /시뮬레이터 화면 연결이 끊겨 공유를 끝냅니다\. \(AXe 화면 전송\(stream-video\)이 끝났습니다\.\)/);
    assert.deepEqual(client.revoked, [11]);
  });

  it('refuses bad arguments as usage errors', async () => {
    for (const argv of [
      ['share', 'ios', 'iPhone'],
      ['share', 'ios', '--app', '-x'],
      ['share', 'ios', '--app', 'com example'],
      ['share', 'ios', '--app', 'com.apple.springboard'],
      ['share', 'ios', '--udid', 'a b'],
      ['share', 'ios', '--udid'],
      ['share', 'ios', '--scale', '2'],
      ['share', 'ios', '--scale', 'half'],
      ['share', 'ios', '--fps', '0'],
      ['share', 'ios', '--fps', '1.5'],
      ['share', 'ios', '--fps', '31'],
    ]) {
      const { code, client } = await shareCli(argv);
      assert.equal(code, EXIT.USAGE, argv.join(' '));
      assert.equal(client.created, null, argv.join(' '));
    }
  });
});
