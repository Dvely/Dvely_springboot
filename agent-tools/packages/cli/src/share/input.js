/**
 * Viewer input (§4.3) → Chrome DevTools Protocol calls.
 *
 * Pure: takes the parsed INPUT payload and the current screen size, returns the CDP commands to
 * send. Anything malformed maps to nothing — the input came from a stranger's browser through the
 * server, so it is checked here rather than trusted.
 */

/** CDP's modifier bitmask, which the INPUT payload uses as-is. */
export const MODIFIER = Object.freeze({ ALT: 1, CTRL: 2, META: 4, SHIFT: 8 });
const ALL_MODIFIERS = MODIFIER.ALT | MODIFIER.CTRL | MODIFIER.META | MODIFIER.SHIFT;

/** CDP `buttons`: which buttons are held, as a bitmask. */
const BUTTON_BIT = Object.freeze({ left: 1, right: 2, middle: 4, back: 8, forward: 16 });
const BUTTON_BY_PRIORITY = ['left', 'right', 'middle', 'back', 'forward'];

const POINTER_TYPE = Object.freeze({ down: 'mousePressed', move: 'mouseMoved', up: 'mouseReleased' });

const MAX_TEXT_LENGTH = 10_000;
const MAX_WHEEL_DELTA = 10_000;
const MAX_KEY_LENGTH = 64;

/** Held-button state across events, so a move while the button is down is a drag. */
export function createPointerState() {
  return { buttons: 0 };
}

/**
 * @param {object} input one §4.3 object
 * @param {{width: number, height: number}} screen CSS pixels of the shared page
 * @param {{buttons: number}} [state] from createPointerState(); updated in place
 * @param {{platform?: string}} [options] the platform Chrome runs on (editing shortcuts differ on macOS)
 * @returns {{commands: {method: string, params: object}[], nav: null|'back'|'forward'|'reload'}}
 */
export function mapInput(input, screen, state = createPointerState(), { platform = process.platform } = {}) {
  const none = { commands: [], nav: null };
  if (!input || typeof input !== 'object') return none;

  switch (input.type) {
    case 'pointer':
      return { commands: pointer(input, screen, state), nav: null };
    case 'wheel':
      return { commands: wheel(input, screen), nav: null };
    case 'key':
      return { commands: key(input, platform), nav: null };
    case 'text':
      return { commands: text(input), nav: null };
    case 'nav':
      return ['back', 'forward', 'reload'].includes(input.action) ? { commands: [], nav: input.action } : none;
    default:
      return none;
  }
}

/** A 0..1 ratio → a CSS pixel inside the screen, or null when either side is unusable. */
export function toPixel(ratio, size) {
  if (typeof ratio !== 'number' || !Number.isFinite(ratio)) return null;
  if (typeof size !== 'number' || !Number.isFinite(size) || size <= 0) return null;
  return Math.min(Math.max(ratio, 0), 1) * size;
}

function point(input, screen) {
  const x = toPixel(input.x, screen?.width);
  const y = toPixel(input.y, screen?.height);
  return x === null || y === null ? null : { x, y };
}

function modifiersOf(input) {
  return Number.isInteger(input.modifiers) ? input.modifiers & ALL_MODIFIERS : 0;
}

function pointer(input, screen, state) {
  const type = POINTER_TYPE[input.action];
  const at = point(input, screen);
  if (!type || !at) return [];

  let button;
  if (input.action === 'move') {
    // CDP reads a move with a held button as a drag; with 'none' it is a hover. The viewer says
    // which it is (button "none" for a hover) and is believed over our own record, so a down or
    // up the server dropped (rate limit, control changing hands mid-drag) cannot leave a button
    // stuck. Without that field the record decides.
    if (input.button === 'none') {
      state.buttons = 0;
      button = 'none';
    } else if (Object.hasOwn(BUTTON_BIT, input.button)) {
      state.buttons |= BUTTON_BIT[input.button];
      button = input.button;
    } else {
      button = BUTTON_BY_PRIORITY.find((b) => state.buttons & BUTTON_BIT[b]) ?? 'none';
    }
  } else {
    button = Object.hasOwn(BUTTON_BIT, input.button) ? input.button : 'left';
    if (input.action === 'down') state.buttons |= BUTTON_BIT[button];
    else state.buttons &= ~BUTTON_BIT[button];
  }

  const clickCount =
    input.action === 'move'
      ? 0
      : Number.isInteger(input.clickCount) && input.clickCount >= 1
        ? Math.min(input.clickCount, 3)
        : 1;

  return [
    {
      method: 'Input.dispatchMouseEvent',
      params: { type, x: at.x, y: at.y, button, buttons: state.buttons, clickCount, modifiers: modifiersOf(input) },
    },
  ];
}

function wheel(input, screen) {
  const at = point(input, screen);
  if (!at) return [];
  const delta = (v) =>
    typeof v === 'number' && Number.isFinite(v) ? Math.min(Math.max(v, -MAX_WHEEL_DELTA), MAX_WHEEL_DELTA) : 0;
  return [
    {
      method: 'Input.dispatchMouseEvent',
      params: {
        type: 'mouseWheel',
        x: at.x,
        y: at.y,
        deltaX: delta(input.dx),
        deltaY: delta(input.dy),
        modifiers: modifiersOf(input),
      },
    },
  ];
}

/**
 * Keys an IME or a dead key reports while composing. The composed result arrives separately as a
 * `text` input (compositionend), so passing these through would type stray characters.
 */
const COMPOSING_KEYS = new Set(['Process', 'Unidentified', 'Dead']);

/**
 * Paste reads the clipboard of the machine Chrome runs on — the owner's. A remote controller
 * pressing Ctrl+V in a text field would put whatever the owner last copied onto everyone's screen.
 */
function isPaste(keyName, modifiers) {
  if ((modifiers & (MODIFIER.CTRL | MODIFIER.META)) && keyName.toLowerCase() === 'v') return true;
  return keyName === 'Insert' && (modifiers & MODIFIER.SHIFT) !== 0;
}

/**
 * Chrome on macOS runs editing shortcuts in the browser process, which synthetic key events skip;
 * CDP takes them as `commands` instead. Elsewhere the renderer handles the shortcut itself, and
 * sending the command too would run it twice (undo twice).
 */
function macEditingCommands(keyName, modifiers) {
  if (!(modifiers & (MODIFIER.CTRL | MODIFIER.META)) || modifiers & MODIFIER.ALT) return [];
  const k = keyName.toLowerCase();
  if (k === 'a') return ['selectAll'];
  if (k === 'z') return [modifiers & MODIFIER.SHIFT ? 'redo' : 'undo'];
  if (k === 'y') return ['redo'];
  return [];
}

function key(input, platform) {
  if (input.action !== 'down' && input.action !== 'up') return [];
  const keyName = input.key;
  if (typeof keyName !== 'string' || keyName.length === 0 || keyName.length > MAX_KEY_LENGTH) return [];
  if (COMPOSING_KEYS.has(keyName)) return [];
  const code = typeof input.code === 'string' && input.code.length <= MAX_KEY_LENGTH ? input.code : '';
  const modifiers = modifiersOf(input);
  if (isPaste(keyName, modifiers)) return [];

  const vk = virtualKeyCode(keyName, code);
  const params = {
    key: keyName,
    code,
    modifiers,
    windowsVirtualKeyCode: vk,
    nativeVirtualKeyCode: vk,
    ...(code.startsWith('Numpad') ? { location: 3 } : {}),
  };

  if (input.action === 'up') {
    return [{ method: 'Input.dispatchKeyEvent', params: { type: 'keyUp', ...params } }];
  }

  const typed = textFor(keyName, modifiers);
  const commands = platform === 'darwin' ? macEditingCommands(keyName, modifiers) : [];
  return [
    {
      method: 'Input.dispatchKeyEvent',
      params: {
        // A keyDown carrying text types it; one without is a "raw" press (arrows, Backspace) —
        // the same split Puppeteer uses, since a keyDown with no text behaves inconsistently.
        type: typed ? 'keyDown' : 'rawKeyDown',
        ...params,
        ...(typed ? { text: typed, unmodifiedText: typed } : {}),
        ...(commands.length ? { commands } : {}),
      },
    },
  ];
}

/** The character a key press types, if any. Ctrl/Meta chords type nothing — they are shortcuts. */
function textFor(keyName, modifiers) {
  if (modifiers & (MODIFIER.CTRL | MODIFIER.META)) return null;
  if (keyName === 'Enter') return '\r';
  const chars = [...keyName];
  if (chars.length !== 1) return null;
  const cp = keyName.codePointAt(0);
  return cp >= 0x20 && cp !== 0x7f ? keyName : null;
}

function text(input) {
  if (typeof input.text !== 'string' || input.text.length === 0) return [];
  return [{ method: 'Input.insertText', params: { text: input.text.slice(0, MAX_TEXT_LENGTH) } }];
}

const CODE_VK = Object.freeze({
  Backspace: 8, Tab: 9, Enter: 13, NumpadEnter: 13, ShiftLeft: 16, ShiftRight: 16,
  ControlLeft: 17, ControlRight: 17, AltLeft: 18, AltRight: 18, Pause: 19, CapsLock: 20,
  Escape: 27, Space: 32, PageUp: 33, PageDown: 34, End: 35, Home: 36,
  ArrowLeft: 37, ArrowUp: 38, ArrowRight: 39, ArrowDown: 40, Insert: 45, Delete: 46,
  MetaLeft: 91, MetaRight: 92, ContextMenu: 93,
  NumpadMultiply: 106, NumpadAdd: 107, NumpadSubtract: 109, NumpadDecimal: 110, NumpadDivide: 111,
  Semicolon: 186, Equal: 187, Comma: 188, Minus: 189, Period: 190, Slash: 191, Backquote: 192,
  BracketLeft: 219, Backslash: 220, BracketRight: 221, Quote: 222, IntlBackslash: 226,
  Lang1: 21, Lang2: 25, // 한/영, 한자
});

const KEY_VK = Object.freeze({
  Backspace: 8, Tab: 9, Enter: 13, Shift: 16, Control: 17, Alt: 18, Pause: 19, CapsLock: 20,
  Escape: 27, ' ': 32, PageUp: 33, PageDown: 34, End: 35, Home: 36,
  ArrowLeft: 37, ArrowUp: 38, ArrowRight: 39, ArrowDown: 40, Insert: 45, Delete: 46,
  Meta: 91, ContextMenu: 93, HangulMode: 21, HanjaMode: 25,
  ';': 186, '=': 187, ',': 188, '-': 189, '.': 190, '/': 191, '`': 192,
  '[': 219, '\\': 220, ']': 221, "'": 222,
});

/**
 * Windows virtual key code for the common keys. Chrome uses it for keydown's `keyCode`, which a
 * lot of app code still reads; 0 means "unknown" and is what CDP assumes when it is left out.
 */
export function virtualKeyCode(keyName, code = '') {
  if (Object.hasOwn(CODE_VK, code)) return CODE_VK[code];
  let m = /^Key([A-Z])$/.exec(code);
  if (m) return m[1].charCodeAt(0);
  m = /^Digit([0-9])$/.exec(code);
  if (m) return 48 + Number(m[1]);
  m = /^Numpad([0-9])$/.exec(code);
  if (m) return 96 + Number(m[1]);
  m = /^F([1-9]|1[0-9]|2[0-4])$/.exec(code) ?? /^F([1-9]|1[0-9]|2[0-4])$/.exec(keyName);
  if (m) return 111 + Number(m[1]);

  if (typeof keyName !== 'string') return 0;
  if (Object.hasOwn(KEY_VK, keyName)) return KEY_VK[keyName];
  if (/^[a-zA-Z]$/.test(keyName)) return keyName.toUpperCase().charCodeAt(0);
  if (/^[0-9]$/.test(keyName)) return keyName.charCodeAt(0);
  return 0;
}
