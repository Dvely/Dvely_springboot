/**
 * Viewer input (§4.3) → scrcpy control messages (§12.4). Pure: the result is what to write to the
 * control socket, in order — encoded messages, and `{paste: text}` for text the keyboard map cannot
 * type, which the session sends through the clipboard (it has to pace those, see session.js).
 */
import { KEY_ACTION, MOTION, encodeKeycode, encodeScroll, encodeText, encodeTouch } from './scrcpy-protocol.js';

/** android.view.KeyEvent codes. */
export const KEYCODE = Object.freeze({
  BACK: 4,
  DPAD_UP: 19,
  DPAD_DOWN: 20,
  DPAD_LEFT: 21,
  DPAD_RIGHT: 22,
  TAB: 61,
  SPACE: 62,
  ENTER: 66,
  DEL: 67,
  PAGE_UP: 92,
  PAGE_DOWN: 93,
  ESCAPE: 111,
  FORWARD_DEL: 112,
  MOVE_HOME: 122,
  MOVE_END: 123,
  A: 29,
  ZERO: 7,
});

const NAMED_KEYS = Object.freeze({
  Enter: KEYCODE.ENTER,
  Backspace: KEYCODE.DEL,
  Delete: KEYCODE.FORWARD_DEL,
  Tab: KEYCODE.TAB,
  Escape: KEYCODE.ESCAPE,
  ArrowUp: KEYCODE.DPAD_UP,
  ArrowDown: KEYCODE.DPAD_DOWN,
  ArrowLeft: KEYCODE.DPAD_LEFT,
  ArrowRight: KEYCODE.DPAD_RIGHT,
  Home: KEYCODE.MOVE_HOME,
  End: KEYCODE.MOVE_END,
  PageUp: KEYCODE.PAGE_UP,
  PageDown: KEYCODE.PAGE_DOWN,
});

/** android.view.KeyEvent META_* (the generic bit with the left-hand one, as a real keyboard reports). */
export const META = Object.freeze({
  SHIFT: 0x1 | 0x40,
  ALT: 0x2 | 0x10,
  CTRL: 0x1000 | 0x2000,
});

/** The viewer's modifier bits (input.js MODIFIER): ALT 1, CTRL 2, META 4, SHIFT 8. */
const VIEWER_MOD = Object.freeze({ ALT: 1, CTRL: 2, META: 4, SHIFT: 8 });

const MAX_TEXT_LENGTH = 10_000;
const MAX_WHEEL_DELTA = 10_000;
/** A browser wheel notch is 100–120 px; scrcpy counts notches. */
const WHEEL_NOTCH = 120;

export function createTouchState() {
  return { down: false };
}

/**
 * @param {object} input one §4.3 input
 * @param {{width: number, height: number}} screen the current video size
 * @param {{down: boolean}} state whether a finger is on the screen
 * @returns {(Buffer|{paste: string})[]}
 */
export function mapAndroidInput(input, screen, state = createTouchState()) {
  if (!input || typeof input !== 'object') return [];
  switch (input.type) {
    case 'pointer':
      return pointer(input, screen, state);
    case 'wheel':
      return wheel(input, screen);
    case 'key':
      return key(input);
    case 'text':
      return text(input.text);
    case 'nav':
      return input.action === 'back' ? press(KEYCODE.BACK) : [];
    default:
      return [];
  }
}

function ratio(v) {
  const n = Number(v);
  return Number.isFinite(n) ? Math.min(1, Math.max(0, n)) : null;
}

function position(input, screen) {
  const rx = ratio(input.x);
  const ry = ratio(input.y);
  if (rx === null || ry === null || !(screen.width > 0) || !(screen.height > 0)) return null;
  return {
    x: Math.min(screen.width - 1, Math.round(rx * screen.width)),
    y: Math.min(screen.height - 1, Math.round(ry * screen.height)),
    width: screen.width,
    height: screen.height,
  };
}

/** The left button (or a finger) is a finger on the screen; other buttons and hovering do nothing. */
function pointer(input, screen, state) {
  const at = position(input, screen);
  if (!at) return [];
  const button = input.button ?? 'left';
  switch (input.action) {
    case 'down':
      if (button !== 'left') return [];
      if (state.down) {
        // A down without the up before it (a lost pointerup): lift first, so Android sees one finger.
        state.down = true;
        return [encodeTouch({ action: MOTION.UP, ...at }), encodeTouch({ action: MOTION.DOWN, ...at })];
      }
      state.down = true;
      return [encodeTouch({ action: MOTION.DOWN, ...at })];
    case 'move':
      return state.down ? [encodeTouch({ action: MOTION.MOVE, ...at })] : [];
    case 'up':
      if (!state.down) return [];
      state.down = false;
      return [encodeTouch({ action: MOTION.UP, ...at })];
    default:
      return [];
  }
}

function wheel(input, screen) {
  const at = position(input, screen);
  if (!at) return [];
  const delta = (v) => {
    const n = Number(v);
    return Number.isFinite(n) ? Math.min(MAX_WHEEL_DELTA, Math.max(-MAX_WHEEL_DELTA, n)) : 0;
  };
  const clampNotches = (v) => Math.min(16, Math.max(-16, v));
  // Browser: dy > 0 scrolls down. Android/scrcpy: vscroll > 0 scrolls up; hscroll > 0 scrolls right.
  const hscroll = clampNotches(delta(input.dx) / WHEEL_NOTCH);
  const vscroll = clampNotches(-delta(input.dy) / WHEEL_NOTCH);
  if (!hscroll && !vscroll) return [];
  return [encodeScroll({ ...at, hscroll, vscroll })];
}

function metaStateOf(modifiers) {
  const m = Number(modifiers) | 0;
  let meta = 0;
  if (m & VIEWER_MOD.SHIFT) meta |= META.SHIFT;
  if (m & VIEWER_MOD.ALT) meta |= META.ALT;
  // Cmd on a Mac means what Ctrl means on Android (select all, copy, undo).
  if (m & (VIEWER_MOD.CTRL | VIEWER_MOD.META)) meta |= META.CTRL;
  return meta;
}

function key(input) {
  const name = typeof input.key === 'string' ? input.key : '';
  const action = input.action === 'up' ? KEY_ACTION.UP : input.action === 'down' ? KEY_ACTION.DOWN : null;
  if (action === null || !name) return [];
  const modifiers = Number(input.modifiers) | 0;
  const metaState = metaStateOf(modifiers);

  if (Object.hasOwn(NAMED_KEYS, name)) {
    return [encodeKeycode({ action, keycode: NAMED_KEYS[name], metaState })];
  }
  if ([...name].length !== 1) return []; // Shift, Control, F1… on their own do nothing here

  const shortcut = modifiers & (VIEWER_MOD.CTRL | VIEWER_MOD.META);
  if (shortcut) {
    const keycode = letterOrDigitKeycode(name);
    return keycode === null ? [] : [encodeKeycode({ action, keycode, metaState })];
  }
  // A typed character: inserted once, on the way down (the keyboard map decides the key presses).
  return action === KEY_ACTION.DOWN ? text(name) : [];
}

function letterOrDigitKeycode(ch) {
  const c = ch.toLowerCase();
  if (c >= 'a' && c <= 'z') return KEYCODE.A + (c.charCodeAt(0) - 97);
  if (c >= '0' && c <= '9') return KEYCODE.ZERO + (c.charCodeAt(0) - 48);
  return null;
}

/** ASCII goes in as key presses; anything else (Korean, emoji) through the clipboard and PASTE. */
function text(raw) {
  if (typeof raw !== 'string' || raw.length === 0) return [];
  const value = raw.slice(0, MAX_TEXT_LENGTH);
  // eslint-disable-next-line no-control-regex
  if (/^[\x20-\x7e\n\t]*$/.test(value)) return encodeText(value);
  return [{ paste: value }];
}

function press(keycode) {
  return [encodeKeycode({ action: KEY_ACTION.DOWN, keycode }), encodeKeycode({ action: KEY_ACTION.UP, keycode })];
}
