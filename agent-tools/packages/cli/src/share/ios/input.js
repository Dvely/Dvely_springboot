/**
 * Viewer input (§4.3) → AXe commands (§13.2). Pure: the session supplies the screen size in points
 * and the time, and runs what comes back.
 *
 * AXe has no "finger down now, move later" command a remote pointer could drive live, so a touch is
 * collected from down to up and replayed as one command when it ends:
 *
 *   down … up, within 10 pt, under 0.5 s   → tap -x X -y Y
 *   down … up, within 10 pt, 0.5 s or more → tap -x X -y Y --hold S
 *   down, moves, up                        → draw x1,y1 … xn,yn --duration S   (≤40 points, as long as it took)
 *
 * What comes back is a list of items, not commands, so the session can merge what piles up while
 * AXe is busy (about 0.3 s a command): typed text into one `type`, trackpad scroll steps into one
 * swipe. axeCommands() turns an item into the commands to run, each an argv array.
 *
 *   {kind: 'command', args}               a command as it is, e.g. ['key', '40']
 *   {kind: 'text', text, mode}            keys for `type`, and the keyboard mode they need (below)
 *   {kind: 'swipe', x, y, dx, dy, width, height}   a scroll: the finger moves against (dx, dy)
 *   {kind: 'notice', notice}              nothing to send; tell the owner once ('edge', 'non-ascii', 'hangul', 'paste')
 *
 * Text goes in as key presses through the simulator's hardware keyboard, which types in whatever
 * input mode the simulator is in: in Korean mode `type -- hello` comes out as ㅗ디ㅣㅐ. So each run of
 * text carries the mode it needs, and the session switches before typing it (session.js):
 *   - `en`: ASCII letters
 *   - `ko`: Hangul, as its 2-set keys (hangul.js); only when the simulator has a Korean keyboard
 *   - `any`: digits, space and ASCII punctuation, the same keys in both modes
 * Because the text decides the mode, a viewer's own Ctrl+Space is not passed on (and Cmd+Space would
 * open Spotlight, outside the app).
 */
import { hangulKeys } from './hangul.js';

/** USB HID keyboard usage IDs, which AXe's `key` and `key-combo` take. */
export const HID = Object.freeze({
  A: 4, // … Z = 29
  V: 25,
  ONE: 30, // … 9 = 38
  ZERO: 39,
  ENTER: 40,
  ESCAPE: 41,
  BACKSPACE: 42,
  TAB: 43,
  HOME: 74,
  PAGE_UP: 75,
  DELETE: 76,
  END: 77,
  PAGE_DOWN: 78,
  RIGHT: 79,
  LEFT: 80,
  DOWN: 81,
  UP: 82,
  SPACE: 44,
  CAPS_LOCK: 57,
  LEFT_CTRL: 224,
  LEFT_SHIFT: 225,
  LEFT_ALT: 226, // Option
  LEFT_GUI: 227, // Cmd
});

const NAMED_KEYS = Object.freeze({
  Enter: HID.ENTER,
  Escape: HID.ESCAPE,
  Backspace: HID.BACKSPACE,
  Tab: HID.TAB,
  Delete: HID.DELETE,
  Home: HID.HOME,
  End: HID.END,
  PageUp: HID.PAGE_UP,
  PageDown: HID.PAGE_DOWN,
  ArrowRight: HID.RIGHT,
  ArrowLeft: HID.LEFT,
  ArrowDown: HID.DOWN,
  ArrowUp: HID.UP,
});

/** The viewer's modifier bits (../input.js MODIFIER): ALT 1, CTRL 2, META 4, SHIFT 8. */
const MOD = Object.freeze({ ALT: 1, CTRL: 2, META: 4, SHIFT: 8 });

/** A touch that stays this close to where it started is a tap, as on a real screen. */
export const TAP_SLOP_POINTS = 10;
export const LONG_PRESS_MS = 500;
export const MAX_DRAW_POINTS = 40;
/**
 * §13.2: movement starting in the top or bottom 3% is dropped. Those swipes leave the app (home,
 * app switcher, Notification Centre, Control Centre); taps there stay inside it and pass.
 */
export const EDGE_RATIO = 0.03;
const MIN_DRAW_SECONDS = 0.1;
const MAX_DRAW_SECONDS = 5;
/** Long presses longer than this replay as this: no other input runs meanwhile. */
const MAX_HOLD_SECONDS = 5;
const SWIPE_SECONDS = 0.3;
/** Shorter scrolls are held back and added up: a tiny swipe can land as a tap. */
const MIN_SWIPE_POINTS = 12;
/** One swipe moves at most this share of the screen, so it starts and ends on it. */
const MAX_SWIPE_FRACTION = 0.6;
/** Scroll steps further apart than this start a new scroll (and drop the remainder of the last). */
const WHEEL_IDLE_MS = 400;
/** Points kept while a finger is down; past it the path is thinned to half. */
const MAX_TOUCH_POINTS = 1000;
/** Characters per `type`: the most AXe documents for its other input routes, kept here too. */
export const MAX_TYPE_CHARS = 256;
const MAX_TEXT_LENGTH = 10_000;
const MAX_WHEEL_DELTA = 10_000;

export function createGestureState() {
  return { touch: null, wheel: null };
}

/**
 * @param {object} input one §4.3 input
 * @param {{width: number, height: number}|null} screen the simulator screen in points
 * @param {{touch: object|null, wheel: object|null}} state from createGestureState(); updated in place
 * @param {number} now milliseconds, for how long a touch lasted
 * @param {{hangul?: boolean}} [keyboard] whether the simulator has a Korean keyboard to type Hangul with
 * @returns {object[]} items, see the module comment
 */
export function mapIosInput(input, screen, state, now, { hangul = false } = {}) {
  if (!input || typeof input !== 'object') return [];
  switch (input.type) {
    case 'pointer':
      return pointer(input, screen, state, now);
    case 'wheel':
      return wheel(input, screen, state, now);
    case 'key':
      return key(input, hangul);
    case 'text':
      return text(input.text, hangul);
    default:
      // `nav` included: iOS has no browser history. The app's own back button is on the screen.
      return [];
  }
}

const command = (...args) => ({ kind: 'command', args: args.map(String) });
const notice = (name) => ({ kind: 'notice', notice: name });
const clamp = (v, min, max) => Math.min(max, Math.max(min, v));
const seconds = (s) => String(Math.round(s * 100) / 100);

function ratio(v) {
  const n = Number(v);
  return Number.isFinite(n) ? clamp(n, 0, 1) : null;
}

/** A viewer ratio → a whole point on the screen (AXe refuses points off it). */
function toPoint(input, screen) {
  const rx = ratio(input.x);
  const ry = ratio(input.y);
  if (rx === null || ry === null || !(screen?.width > 0) || !(screen?.height > 0)) return null;
  return {
    x: clamp(Math.round(rx * screen.width), 0, Math.floor(screen.width) - 1),
    y: clamp(Math.round(ry * screen.height), 0, Math.floor(screen.height) - 1),
    edge: ry < EDGE_RATIO || ry > 1 - EDGE_RATIO,
  };
}

function pointer(input, screen, state, now) {
  const at = toPoint(input, screen);
  if (!at) return [];
  switch (input.action) {
    case 'down': {
      // Only the left button (or a finger) touches; the others and hovering do nothing.
      if ((input.button ?? 'left') !== 'left') return [];
      // A down without the up before it (a lost pointerup): play what was collected first.
      const before = state.touch ? finishTouch(state, now) : [];
      state.touch = { start: at, points: [at], startedAt: now };
      return before;
    }
    case 'move':
      if (state.touch) addPoint(state.touch, at);
      return [];
    case 'up':
      if (!state.touch) return [];
      addPoint(state.touch, at);
      return finishTouch(state, now);
    default:
      return [];
  }
}

function addPoint(touch, at) {
  const last = touch.points.at(-1);
  if (last.x === at.x && last.y === at.y) return;
  touch.points.push(at);
  if (touch.points.length >= MAX_TOUCH_POINTS) touch.points = thin(touch.points, MAX_TOUCH_POINTS / 2);
}

/** At most `max` points, evenly picked, the first and last always kept, no point twice in a row. */
function thin(points, max) {
  const picked =
    points.length <= max ? points : Array.from({ length: max }, (_, i) => points[Math.round((i * (points.length - 1)) / (max - 1))]);
  return picked.filter((p, i) => i === 0 || p.x !== picked[i - 1].x || p.y !== picked[i - 1].y);
}

function finishTouch(state, now) {
  const touch = state.touch;
  state.touch = null;
  const elapsed = Math.max(0, now - touch.startedAt) / 1000;
  const { x, y, edge } = touch.start;
  const moved = touch.points.some((p) => Math.hypot(p.x - x, p.y - y) > TAP_SLOP_POINTS);
  if (!moved) {
    if (elapsed * 1000 < LONG_PRESS_MS) return [command('tap', '-x', x, '-y', y)];
    return [command('tap', '-x', x, '-y', y, '--hold', seconds(Math.min(elapsed, MAX_HOLD_SECONDS)))];
  }
  if (edge) return [notice('edge')];
  const path = thin(touch.points, MAX_DRAW_POINTS);
  if (path.length < 2) return [command('tap', '-x', x, '-y', y)]; // draw needs two points; thinning kept one
  const duration = clamp(elapsed, MIN_DRAW_SECONDS, MAX_DRAW_SECONDS);
  return [command('draw', ...path.map((p) => `${p.x},${p.y}`), '--duration', seconds(duration))];
}

function wheel(input, screen, state, now) {
  const at = toPoint(input, screen);
  if (!at) return [];
  const delta = (v) => {
    const n = Number(v);
    return Number.isFinite(n) ? clamp(n, -MAX_WHEEL_DELTA, MAX_WHEEL_DELTA) : 0;
  };
  const dx = delta(input.dx);
  const dy = delta(input.dy);
  if (!dx && !dy) return [];
  if (!state.wheel || now - state.wheel.at > WHEEL_IDLE_MS) state.wheel = { ...at, dx: 0, dy: 0, at: now };
  const acc = state.wheel;
  acc.dx += dx;
  acc.dy += dy;
  acc.at = now;
  if (Math.abs(acc.dx) < MIN_SWIPE_POINTS && Math.abs(acc.dy) < MIN_SWIPE_POINTS) return [];
  state.wheel = null;
  if (acc.edge) return [notice('edge')];
  // Browser wheel pixels are taken as points: the viewer shows the screen at roughly its point size.
  return [{ kind: 'swipe', x: acc.x, y: acc.y, dx: acc.dx, dy: acc.dy, width: screen.width, height: screen.height }];
}

function key(input, hangul) {
  // AXe's `key` presses and releases in one command, so the down is the press and the up is nothing.
  if (input.action !== 'down') return [];
  const name = typeof input.key === 'string' ? input.key : '';
  if (Object.hasOwn(NAMED_KEYS, name)) return [command('key', NAMED_KEYS[name])];
  if ([...name].length !== 1) return []; // Shift, Control, F1… on their own do nothing here

  const modifiers = Number(input.modifiers) | 0;
  if (modifiers & (MOD.CTRL | MOD.META)) {
    const hid = letterOrDigitHid(name);
    if (hid === null) return [];
    // §13.4: Cmd+V pastes the simulator pasteboard, which Simulator may sync with the owner's Mac.
    // With Shift or Option too it still pastes.
    if (hid === HID.V) return [notice('paste')];
    // Ctrl from a Windows viewer means what Cmd means on iOS. Shift and Option are held as well,
    // so Cmd+Shift+Z stays redo rather than becoming undo.
    const held = [HID.LEFT_GUI];
    if (modifiers & MOD.SHIFT) held.push(HID.LEFT_SHIFT);
    if (modifiers & MOD.ALT) held.push(HID.LEFT_ALT);
    return [command('key-combo', '--modifiers', held.join(','), '--key', hid)];
  }
  return text(name, hangul);
}

/** a…z → 4…29, 1…9 → 30…38, 0 → 39 */
export function letterOrDigitHid(ch) {
  const c = String(ch).toLowerCase();
  if (c.length !== 1) return null;
  if (c >= 'a' && c <= 'z') return HID.A + (c.charCodeAt(0) - 97);
  if (c >= '1' && c <= '9') return HID.ONE + (c.charCodeAt(0) - 49);
  if (c === '0') return HID.ZERO;
  return null;
}

/** The keyboard mode one character needs, with its keys; null when it cannot be typed at all. */
function keysFor(ch) {
  if (/^[A-Za-z]$/.test(ch)) return { mode: 'en', keys: ch };
  if (/^[\x20-\x7e]$/.test(ch)) return { mode: 'any', keys: ch };
  const keys = hangulKeys(ch);
  return keys ? { mode: 'ko', keys } : null;
}

/**
 * Typed text, as runs of keys each needing one keyboard mode; line breaks and tabs as keys.
 *
 * Text with a character that cannot be typed (é, 漢, an emoji, or Hangul when the simulator has no
 * Korean keyboard) is dropped whole: half a sentence is worse than none, and AXe itself refuses the
 * whole of a `type` it cannot send. The pasteboard is never used for it (§13.4).
 */
function text(raw, hangul) {
  if (typeof raw !== 'string' || raw.length === 0) return [];
  const chars = [...raw.slice(0, MAX_TEXT_LENGTH).replace(/\r\n?/g, '\n')];
  const typed = chars.map((ch) => (ch === '\n' || ch === '\t' ? null : keysFor(ch)));
  if (chars.some((ch, i) => ch !== '\n' && ch !== '\t' && !typed[i])) return [notice('non-ascii')];
  if (!hangul && typed.some((t) => t?.mode === 'ko')) return [notice('hangul')];

  const items = [];
  chars.forEach((ch, i) => {
    if (ch === '\n' || ch === '\t') {
      items.push(command('key', ch === '\n' ? HID.ENTER : HID.TAB));
      return;
    }
    const run = { kind: 'text', text: typed[i].keys, mode: typed[i].mode };
    if (!mergeItem(items.at(-1), run)) items.push(run);
  });
  return items;
}

/** Whether two runs can be typed in one go: the same mode, or one of them fits either. */
function sameMode(a, b) {
  return a === b || a === 'any' || b === 'any';
}

/**
 * Folds `next` into `last` when both are text for the same keyboard mode or both are scrolls, so a
 * backlog goes out as one command. Only items not yet sent are merged.
 *
 * @returns {boolean} whether it was merged
 */
export function mergeItem(last, next) {
  if (!last || last.kind !== next.kind) return false;
  if (next.kind === 'text') {
    if (!sameMode(last.mode, next.mode)) return false;
    last.text += next.text;
    if (last.mode === 'any') last.mode = next.mode;
    return true;
  }
  if (next.kind === 'swipe') {
    last.dx += next.dx;
    last.dy += next.dy;
    return true;
  }
  return false;
}

/**
 * The commands for one item, in order, each an argv array without `--udid`.
 *
 * `type` ends its options with `--`: text starting with `-` would otherwise be read as an option
 * (AXe 2.0.4 answers `type -v` with "No input provided").
 *
 * @returns {string[][]}
 */
export function axeCommands(item) {
  switch (item?.kind) {
    case 'command':
      return [item.args];
    case 'text': {
      const commands = [];
      for (let i = 0; i < item.text.length; i += MAX_TYPE_CHARS) commands.push(['type', '--', item.text.slice(i, i + MAX_TYPE_CHARS)]);
      return commands;
    }
    case 'swipe':
      return swipe(item);
    default:
      return [];
  }
}

function swipe({ x, y, dx, dy, width, height }) {
  const maxX = width * MAX_SWIPE_FRACTION;
  const maxY = height * MAX_SWIPE_FRACTION;
  // The finger moves against the scroll: to scroll down the page (dy > 0) it goes up.
  const endX = clamp(Math.round(x - clamp(dx, -maxX, maxX)), 0, Math.floor(width) - 1);
  const endY = clamp(Math.round(y - clamp(dy, -maxY, maxY)), 0, Math.floor(height) - 1);
  if (endX === x && endY === y) return [];
  // About eight touch samples whatever the length. AXe's default is one every 50 pt, and its own
  // scroll code notes that a 92 pt swipe with two samples did not scroll on an iPhone.
  const step = clamp(Math.round(Math.hypot(endX - x, endY - y) / 8), 1, 50);
  return [
    ['swipe', '--start-x', x, '--start-y', y, '--end-x', endX, '--end-y', endY, '--duration', SWIPE_SECONDS, '--delta', step].map(String),
  ];
}
