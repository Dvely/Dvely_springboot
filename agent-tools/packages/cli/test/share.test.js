import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { PassThrough } from 'node:stream';
import {
  HEADER_BYTES,
  KIND,
  MAX_PAYLOAD_BYTES,
  decodeFrame,
  encodeFrame,
  encodeJsonFrame,
  parseJsonPayload,
} from '../src/share/protocol.js';
import { MODIFIER, createPointerState, mapInput, virtualKeyCode } from '../src/share/input.js';
import {
  ackDelay,
  backoffDelay,
  checkTunnelUrl,
  describeOrigin,
  historyEntry,
  isAllowedNavigation,
  parseTargetUrl,
  parseTtl,
  parseViewport,
} from '../src/share/policy.js';
import { chromeArgs, chromeCandidates, findChrome, parseDevToolsUrl } from '../src/share/chrome.js';
import { askControl } from '../src/share/ask.js';
import { UsageError } from '../src/confirm.js';

describe('tunnel frame codec (§4.1)', () => {
  it('lays out kind, a big-endian stream id, then the payload', () => {
    const frame = encodeFrame(KIND.SCREEN_FRAME, Buffer.from([0xff, 0xd8]), 0x01020304);
    assert.deepEqual([...frame], [0x52, 0x01, 0x02, 0x03, 0x04, 0xff, 0xd8]);
  });

  it('round-trips a JSON frame on stream 0', () => {
    const hello = { cliVersion: '0.1.4', kind: 'WEB', target: 'http://localhost:5173' };
    const decoded = decodeFrame(encodeJsonFrame(KIND.HELLO, hello));
    assert.equal(decoded.kind, KIND.HELLO);
    assert.equal(decoded.streamId, 0);
    assert.deepEqual(parseJsonPayload(decoded.payload), hello);
  });

  it('carries UTF-8 text intact', () => {
    const decoded = decodeFrame(encodeJsonFrame(KIND.CONTROL_REQUEST, { viewerId: 'v1', name: '민지' }));
    assert.equal(parseJsonPayload(decoded.payload).name, '민지');
  });

  it('round-trips binary bytes and the largest stream id', () => {
    const jpeg = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0xff, 0xd9]);
    const decoded = decodeFrame(encodeFrame(KIND.SCREEN_FRAME, jpeg, 0xffffffff));
    assert.equal(decoded.streamId, 0xffffffff);
    assert.deepEqual(decoded.payload, jpeg);
  });

  it('allows an empty payload', () => {
    const frame = encodeFrame(KIND.WELCOME);
    assert.equal(frame.length, HEADER_BYTES);
    assert.equal(decodeFrame(frame).payload.length, 0);
  });

  it('accepts exactly 2 MiB and refuses one byte more, both ways', () => {
    assert.equal(encodeFrame(KIND.SCREEN_FRAME, Buffer.alloc(MAX_PAYLOAD_BYTES)).length, HEADER_BYTES + MAX_PAYLOAD_BYTES);
    assert.throws(() => encodeFrame(KIND.SCREEN_FRAME, Buffer.alloc(MAX_PAYLOAD_BYTES + 1)), RangeError);
    assert.throws(() => decodeFrame(Buffer.alloc(HEADER_BYTES + MAX_PAYLOAD_BYTES + 1)), RangeError);
  });

  it('refuses a frame shorter than its header', () => {
    assert.throws(() => decodeFrame(Buffer.from([0x42, 0, 0, 0])), RangeError);
    assert.throws(() => decodeFrame(Buffer.alloc(0)), RangeError);
  });

  it('decodes an unknown kind instead of failing, so a newer server does not break this CLI', () => {
    const decoded = decodeFrame(Buffer.from([0x99, 0, 0, 0, 0, 1, 2]));
    assert.equal(decoded.kind, 0x99);
    assert.deepEqual([...decoded.payload], [1, 2]);
  });

  it('refuses a kind or stream id that does not fit its field', () => {
    assert.throws(() => encodeFrame(256, ''), RangeError);
    assert.throws(() => encodeFrame(-1, ''), RangeError);
    assert.throws(() => encodeFrame(KIND.HELLO, '', 2 ** 32), RangeError);
    assert.throws(() => encodeFrame(KIND.HELLO, '', -1), RangeError);
  });

  it('decodes what ws may hand over: fragments and ArrayBuffers', () => {
    const frame = encodeJsonFrame(KIND.PRESENCE, { viewers: 2, controller: null });
    assert.equal(decodeFrame([frame.subarray(0, 3), frame.subarray(3)]).kind, KIND.PRESENCE);
    const ab = frame.buffer.slice(frame.byteOffset, frame.byteOffset + frame.byteLength);
    assert.equal(parseJsonPayload(decodeFrame(ab).payload).viewers, 2);
  });

  it('reads a non-JSON payload as null rather than throwing', () => {
    assert.equal(parseJsonPayload(Buffer.from('not json')), null);
  });
});

describe('input mapping (§4.3)', () => {
  const screen = { width: 390, height: 844 };

  it('turns ratios into CSS pixels of the current screen', () => {
    const { commands } = mapInput(
      { type: 'pointer', action: 'down', x: 0.5, y: 0.25, button: 'left', clickCount: 1 },
      screen
    );
    assert.deepEqual(commands, [
      {
        method: 'Input.dispatchMouseEvent',
        params: { type: 'mousePressed', x: 195, y: 211, button: 'left', buttons: 1, clickCount: 1, modifiers: 0 },
      },
    ]);
  });

  it('tracks the held button so a move between down and up is a drag', () => {
    const state = createPointerState();
    mapInput({ type: 'pointer', action: 'down', x: 0.1, y: 0.1, button: 'left' }, screen, state);
    const drag = mapInput({ type: 'pointer', action: 'move', x: 0.2, y: 0.2 }, screen, state).commands[0].params;
    assert.equal(drag.type, 'mouseMoved');
    assert.equal(drag.button, 'left');
    assert.equal(drag.buttons, 1);
    assert.equal(drag.clickCount, 0);

    const up = mapInput({ type: 'pointer', action: 'up', x: 0.2, y: 0.2, button: 'left' }, screen, state).commands[0].params;
    assert.equal(up.type, 'mouseReleased');
    assert.equal(up.buttons, 0);
    const hover = mapInput({ type: 'pointer', action: 'move', x: 0.3, y: 0.3 }, screen, state).commands[0].params;
    assert.equal(hover.button, 'none');
  });

  it('takes the viewer at its word on a move: "none" is a hover, a named button is a drag', () => {
    // The shapes the viewer sends (agreed 2026-11-12).
    const state = createPointerState();
    mapInput({ type: 'pointer', action: 'down', x: 0.1, y: 0.1, button: 'left', clickCount: 1 }, screen, state);
    // The matching up never arrived (dropped on the way); the viewer's next hover must not drag.
    const hover = mapInput(
      { type: 'pointer', action: 'move', x: 0.2, y: 0.2, button: 'none', clickCount: 0 },
      screen,
      state
    ).commands[0].params;
    assert.equal(hover.button, 'none');
    assert.equal(hover.buttons, 0);
    assert.equal(hover.clickCount, 0);

    // A drag whose down was dropped still drags.
    const fresh = createPointerState();
    const drag = mapInput({ type: 'pointer', action: 'move', x: 0.3, y: 0.3, button: 'left', clickCount: 0 }, screen, fresh)
      .commands[0].params;
    assert.equal(drag.button, 'left');
    assert.equal(drag.buttons, 1);
  });

  it('scrolls by the CSS pixels a one-finger drag sends', () => {
    const p = mapInput({ type: 'wheel', x: 0.5, y: 0.5, dx: -12.5, dy: 37.25 }, screen).commands[0].params;
    assert.equal(p.deltaX, -12.5);
    assert.equal(p.deltaY, 37.25);
  });

  it('clamps ratios outside 0..1 to the screen edge', () => {
    const p = mapInput({ type: 'pointer', action: 'move', x: 1.7, y: -3 }, screen).commands[0].params;
    assert.equal(p.x, 390);
    assert.equal(p.y, 0);
  });

  it('ignores coordinates that are not numbers, and a screen with no size yet', () => {
    assert.deepEqual(mapInput({ type: 'pointer', action: 'down', x: '0.5', y: 0.5 }, screen).commands, []);
    assert.deepEqual(mapInput({ type: 'pointer', action: 'down', x: NaN, y: 0.5 }, screen).commands, []);
    assert.deepEqual(mapInput({ type: 'pointer', action: 'down', x: 0.5, y: 0.5 }, { width: 0, height: 0 }).commands, []);
    assert.deepEqual(mapInput({ type: 'pointer', action: 'hover', x: 0.5, y: 0.5 }, screen).commands, []);
  });

  it('scrolls with mouseWheel at the pointer', () => {
    const p = mapInput({ type: 'wheel', x: 0.5, y: 0.5, dx: 0, dy: 120 }, screen).commands[0].params;
    assert.deepEqual(p, { type: 'mouseWheel', x: 195, y: 422, deltaX: 0, deltaY: 120, modifiers: 0 });
  });

  it('types Enter as a keyDown with "\\r" so forms submit, and releases with keyUp', () => {
    const down = mapInput({ type: 'key', action: 'down', key: 'Enter', code: 'Enter', modifiers: 0 }, screen).commands[0];
    assert.equal(down.method, 'Input.dispatchKeyEvent');
    assert.equal(down.params.type, 'keyDown');
    assert.equal(down.params.text, '\r');
    assert.equal(down.params.windowsVirtualKeyCode, 13);
    const up = mapInput({ type: 'key', action: 'up', key: 'Enter', code: 'Enter', modifiers: 0 }, screen).commands[0];
    assert.equal(up.params.type, 'keyUp');
    assert.equal(up.params.text, undefined);
  });

  it('sends a printable key with its text and a non-printable one as rawKeyDown', () => {
    const a = mapInput({ type: 'key', action: 'down', key: 'A', code: 'KeyA', modifiers: MODIFIER.SHIFT }, screen)
      .commands[0].params;
    assert.equal(a.type, 'keyDown');
    assert.equal(a.text, 'A');
    assert.equal(a.windowsVirtualKeyCode, 65);
    assert.equal(a.modifiers, 8);

    const left = mapInput({ type: 'key', action: 'down', key: 'ArrowLeft', code: 'ArrowLeft' }, screen).commands[0].params;
    assert.equal(left.type, 'rawKeyDown');
    assert.equal(left.text, undefined);
    assert.equal(left.windowsVirtualKeyCode, 37);
  });

  it('uses the CDP modifier bitmask (Alt=1, Ctrl=2, Meta=4, Shift=8) and masks anything above it', () => {
    assert.deepEqual(MODIFIER, { ALT: 1, CTRL: 2, META: 4, SHIFT: 8 });
    const all = mapInput({ type: 'key', action: 'up', key: 'a', code: 'KeyA', modifiers: 15 }, screen).commands[0];
    assert.equal(all.params.modifiers, 15);
    const junk = mapInput({ type: 'key', action: 'up', key: 'a', code: 'KeyA', modifiers: 0xff }, screen).commands[0];
    assert.equal(junk.params.modifiers, 15);
    const click = mapInput({ type: 'pointer', action: 'down', x: 0, y: 0, modifiers: MODIFIER.CTRL }, screen);
    assert.equal(click.commands[0].params.modifiers, 2);
  });

  it('types nothing for a Ctrl/Meta chord and adds macOS editing commands only on macOS', () => {
    const chord = { type: 'key', action: 'down', key: 'a', code: 'KeyA', modifiers: MODIFIER.CTRL };
    const linux = mapInput(chord, screen, createPointerState(), { platform: 'linux' }).commands[0].params;
    assert.equal(linux.type, 'rawKeyDown');
    assert.equal(linux.text, undefined);
    assert.equal(linux.commands, undefined, 'elsewhere the renderer runs it; sending it too would run it twice');
    const mac = mapInput({ ...chord, modifiers: MODIFIER.META }, screen, createPointerState(), { platform: 'darwin' });
    assert.deepEqual(mac.commands[0].params.commands, ['selectAll']);
  });

  it("drops paste, which would read the owner's clipboard onto everyone's screen", () => {
    for (const input of [
      { type: 'key', action: 'down', key: 'v', code: 'KeyV', modifiers: MODIFIER.CTRL },
      { type: 'key', action: 'down', key: 'v', code: 'KeyV', modifiers: MODIFIER.META },
      { type: 'key', action: 'down', key: 'V', code: 'KeyV', modifiers: MODIFIER.CTRL | MODIFIER.SHIFT },
      { type: 'key', action: 'down', key: 'Insert', code: 'Insert', modifiers: MODIFIER.SHIFT },
    ]) {
      assert.deepEqual(mapInput(input, screen).commands, [], JSON.stringify(input));
    }
  });

  it('inserts composed Korean text with Input.insertText', () => {
    assert.deepEqual(mapInput({ type: 'text', text: '안녕하세요' }, screen), {
      commands: [{ method: 'Input.insertText', params: { text: '안녕하세요' } }],
      nav: null,
    });
  });

  it('ignores the keys an IME reports while composing', () => {
    assert.deepEqual(mapInput({ type: 'key', action: 'down', key: 'Process', code: 'KeyG' }, screen).commands, []);
  });

  it('passes back, forward and reload through as navigation only', () => {
    assert.deepEqual(mapInput({ type: 'nav', action: 'back' }, screen), { commands: [], nav: 'back' });
    assert.deepEqual(mapInput({ type: 'nav', action: 'reload' }, screen), { commands: [], nav: 'reload' });
    assert.deepEqual(mapInput({ type: 'nav', action: 'goto', url: 'file:///etc/passwd' }, screen), { commands: [], nav: null });
  });

  it('maps nothing for unknown or malformed input', () => {
    for (const bad of [null, 'click', { type: 'eval', code: '1' }, { type: 'text', text: '' }, { type: 'key', action: 'down' }]) {
      assert.deepEqual(mapInput(bad, screen), { commands: [], nav: null });
    }
  });

  it('knows the virtual key codes apps still read from keyCode', () => {
    assert.equal(virtualKeyCode('Backspace', 'Backspace'), 8);
    assert.equal(virtualKeyCode('Escape', 'Escape'), 27);
    assert.equal(virtualKeyCode('1', 'Digit1'), 49);
    assert.equal(virtualKeyCode('F5', 'F5'), 116);
    assert.equal(virtualKeyCode('ㅎ', 'KeyG'), 71);
    assert.equal(virtualKeyCode('z'), 90);
    assert.equal(virtualKeyCode('🙂'), 0);
  });
});

describe('navigation guard (§8)', () => {
  const origin = 'http://localhost:5173';

  it('lets the shared origin through, at any path', () => {
    assert.equal(isAllowedNavigation(origin, 'http://localhost:5173/'), true);
    assert.equal(isAllowedNavigation(origin, 'http://localhost:5173/about?tab=1#x'), true);
    assert.equal(isAllowedNavigation(origin, 'about:blank'), true);
  });

  it('blocks other origins, other localhost ports and other schemes', () => {
    for (const url of [
      'https://example.com/',
      'http://localhost:3000/', // the owner's other dev service
      'http://127.0.0.1:5173/', // same port, different origin
      'https://localhost:5173/',
      'http://192.168.0.1/',
      'file:///etc/passwd',
      'data:text/html,hi',
      'javascript:alert(1)',
      'chrome://settings',
      'not a url',
      undefined,
    ]) {
      assert.equal(isAllowedNavigation(origin, url), false, String(url));
    }
  });

  it('names only the origin in messages, since a query can hold a token', () => {
    assert.equal(describeOrigin('https://evil.test/cb?token=secret'), 'https://evil.test');
    assert.equal(describeOrigin('file:///etc/passwd'), 'file://');
  });

  it('finds the history entry for back and forward, and nothing past either end', () => {
    const history = { currentIndex: 1, entries: [{ id: 1, url: 'about:blank' }, { id: 2, url: 'a' }, { id: 3, url: 'b' }] };
    assert.equal(historyEntry(history, -1).id, 1);
    assert.equal(historyEntry(history, 1).id, 3);
    assert.equal(historyEntry({ ...history, currentIndex: 0 }, -1), null);
    assert.equal(historyEntry({ ...history, currentIndex: 2 }, 1), null);
    assert.equal(historyEntry(undefined, -1), null);
  });
});

describe('reconnect and frame pacing', () => {
  it('backs off 1, 2, 4, 8, 16, then 30 seconds for good', () => {
    assert.deepEqual(
      [0, 1, 2, 3, 4, 5, 6, 20].map(backoffDelay),
      [1000, 2000, 4000, 8000, 16000, 30000, 30000, 30000]
    );
    assert.equal(backoffDelay(-1), 1000);
    assert.equal(backoffDelay(NaN), 1000);
  });

  it('holds the ack while a MiB or more is waiting to go out (§4.4)', () => {
    assert.equal(ackDelay({ now: 10_000, lastAckAt: 0, bufferedAmount: 1024 * 1024 }), 50);
    assert.equal(ackDelay({ now: 10_000, lastAckAt: 0, bufferedAmount: 1024 * 1024 - 1 }), 0);
  });

  it('caps acknowledgements at 15 a second', () => {
    assert.equal(ackDelay({ now: 1000, lastAckAt: -Infinity, bufferedAmount: 0 }), 0);
    assert.equal(ackDelay({ now: 1010, lastAckAt: 1000, bufferedAmount: 0 }), 57); // 1000/15 = 66.7 ms
    assert.equal(ackDelay({ now: 1067, lastAckAt: 1000, bufferedAmount: 0 }), 0);
  });
});

describe('share arguments', () => {
  it('takes an http(s) address and refuses everything else', () => {
    assert.equal(parseTargetUrl('http://localhost:5173').origin, 'http://localhost:5173');
    assert.equal(parseTargetUrl(' https://demo.test/app ').pathname, '/app');
    for (const bad of ['', 'localhost:5173', 'file:///Users/me', 'ftp://x', 'chrome://settings', 'http://u:p@localhost:5173']) {
      assert.throws(() => parseTargetUrl(bad), UsageError, bad);
    }
  });

  it('reads a viewport and treats a narrow one as a phone', () => {
    assert.deepEqual(parseViewport('390x844'), { width: 390, height: 844, mobile: true });
    assert.deepEqual(parseViewport('1280x800'), { width: 1280, height: 800, mobile: false });
    for (const bad of ['390', '390x', 'x844', '10x10', '9999x9999', 'abc']) {
      assert.throws(() => parseViewport(bad), UsageError, bad);
    }
  });

  it('takes a ttl of 1..1440 minutes', () => {
    assert.equal(parseTtl('60'), 60);
    assert.equal(parseTtl('1440'), 1440);
    for (const bad of ['0', '1441', '1.5', '-5', 'abc', '']) {
      assert.throws(() => parseTtl(bad), UsageError, bad);
    }
  });

  it('sends the token only over wss, except to loopback, and pins the protocol version', () => {
    assert.equal(
      checkTunnelUrl('wss://q.test/api/v1/device-shares/7/tunnel'),
      'wss://q.test/api/v1/device-shares/7/tunnel?v=1'
    );
    assert.equal(
      checkTunnelUrl('wss://q.test/api/v1/device-shares/7/tunnel?v=1'),
      'wss://q.test/api/v1/device-shares/7/tunnel?v=1'
    );
    assert.match(checkTunnelUrl('ws://localhost:8080/api/v1/device-shares/7/tunnel?v=1'), /^ws:\/\/localhost:8080/);
    assert.throws(() => checkTunnelUrl('ws://q.test/api/v1/device-shares/7/tunnel'), /wss/);
    assert.throws(() => checkTunnelUrl('https://q.test/x'), /wss/);
    assert.throws(() => checkTunnelUrl('nope'));
  });
});

describe('finding and starting Chrome', () => {
  it('prefers CHROME_PATH and refuses one that points nowhere', () => {
    assert.equal(findChrome({ env: { CHROME_PATH: '/opt/c/chrome' }, exists: (p) => p === '/opt/c/chrome' }), '/opt/c/chrome');
    assert.throws(() => findChrome({ env: { CHROME_PATH: '/nope' }, exists: () => false }), /CHROME_PATH/);
  });

  it('looks in the usual macOS, Linux and Windows places, Chrome before Chromium before Edge', () => {
    const mac = chromeCandidates({ env: {}, platform: 'darwin', home: '/Users/me' });
    assert.equal(mac[0], '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome');
    assert.ok(mac.includes('/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge'));
    assert.ok(mac.includes('/Users/me/Applications/Chromium.app/Contents/MacOS/Chromium'));

    const linux = findChrome({
      env: { PATH: '/usr/local/bin:/usr/bin' },
      platform: 'linux',
      exists: (p) => p === '/usr/bin/chromium' || p === '/usr/bin/microsoft-edge',
    });
    assert.equal(linux, '/usr/bin/chromium');

    const win = chromeCandidates({ env: { PROGRAMFILES: 'C:\\Program Files', LOCALAPPDATA: 'C:\\Users\\me\\AppData\\Local' }, platform: 'win32', home: 'C:\\Users\\me' });
    assert.equal(win[0], 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe');
    assert.ok(win.some((p) => p.endsWith('msedge.exe')));
  });

  it('returns null when nothing is installed', () => {
    assert.equal(findChrome({ env: { PATH: '/usr/bin' }, platform: 'linux', exists: () => false }), null);
  });

  it('starts with a throwaway profile, a random loopback port, and headless unless asked', () => {
    const args = chromeArgs({ profileDir: '/tmp/qeploy-share-x' });
    for (const flag of [
      '--user-data-dir=/tmp/qeploy-share-x',
      '--remote-debugging-port=0',
      '--headless=new',
      '--no-first-run',
      '--no-default-browser-check',
      '--disable-extensions',
    ]) {
      assert.ok(args.includes(flag), flag);
    }
    assert.ok(!chromeArgs({ profileDir: '/tmp/p', headed: true }).includes('--headless=new'));
  });

  it('reads the DevTools endpoint from what Chrome prints', () => {
    const stderr =
      '[1112/120000.000:WARNING:x.cc(1)] noise\n' +
      'DevTools listening on ws://127.0.0.1:53421/devtools/browser/0b1c-2d3e\n';
    assert.equal(parseDevToolsUrl(stderr), 'ws://127.0.0.1:53421/devtools/browser/0b1c-2d3e');
    assert.equal(parseDevToolsUrl('nothing yet'), null);
  });
});

describe('asking the owner for control (§5)', () => {
  const sink = () => {
    const output = new PassThrough();
    output.resume();
    return output;
  };

  it('says no without asking when nobody is at a terminal', async () => {
    assert.equal(await askControl('허용할까요?', { input: { isTTY: false }, output: sink() }), false);
  });

  it('says no when the time runs out', async () => {
    const input = new PassThrough();
    input.isTTY = true;
    assert.equal(await askControl('허용할까요?', { input, output: sink(), timeoutMs: 30 }), false);
  });

  it('says yes only to y', async () => {
    for (const [answer, expected] of [['y', true], ['yes', true], ['n', false], ['', false]]) {
      const input = new PassThrough();
      input.isTTY = true;
      setTimeout(() => input.write(`${answer}\n`), 5);
      assert.equal(await askControl('허용할까요?', { input, output: sink(), timeoutMs: 2000 }), expected, answer);
    }
  });
});
