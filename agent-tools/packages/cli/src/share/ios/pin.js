/**
 * Pinning on a shared iOS simulator (§14.3, #682 PR-3): which element is at the viewer's point (or
 * inside a dragged rectangle, §15.4), from AXe `describe-ui` (the accessibility tree, in points); a
 * crop from `axe screenshot` (a PNG in pixels); the screen as the viewer last saw it (the latest
 * stream-video frame).
 *
 * The tree has accessibility roles, not view types: a SwiftUI `SaveButton` view comes out as a
 * `Button` (measured, AXe 2.0.4, iOS 26.5), a UIKit subclass likewise. So the app's own names come
 * from accessibilityIdentifier (AXUniqueId) and labels, and `components` stays empty (§14.3 table).
 *
 * Read-only: one tree and one screenshot. Nothing is tapped.
 */
import { LIMIT, MAX_CROP_BYTES, MAX_SCREEN_BYTES, clip, probePoint, r4, screenResult, unresolved } from '../pin.js';
import { jpegUnder } from '../jpeg.js';
import { parsePng } from '../png.js';
import { findSourceHints } from '../source-hints.js';
import { buildLadder, buildRegion, nearAround, scopeIdsOf } from '../scope.js';

/** Around the element, in points (×3 on a 3x screen: about the web's 16 CSS px at DPR 2). */
const CROP_MARGIN_PT = 12;
const CROP_MAX_SIDE = 800;
const SCREEN_MAX_SIDE = 1280;
/** Around a point with no element: enough to recognise the place, in points. */
const SPOT_PT = { width: 240, height: 160 };
const MAX_TREE_NODES = 20000;

/** What a person presses, switches or types into, by AXe's `type` or the AX role. */
const INTERACTIVE_TYPES = new Set([
  'Button', 'Link', 'TextField', 'SecureTextField', 'TextArea', 'TextView', 'SearchField', 'CheckBox', 'Switch',
  'RadioButton', 'PopUpButton', 'MenuButton', 'MenuItem', 'Cell', 'Tab', 'Slider', 'Stepper', 'Incrementor',
  'ComboBox', 'SegmentedControl',
]);
const INTERACTIVE_ROLES = new Set([
  'AXButton', 'AXLink', 'AXTextField', 'AXTextArea', 'AXSearchField', 'AXCheckBox', 'AXSwitch', 'AXRadioButton',
  'AXPopUpButton', 'AXMenuButton', 'AXMenuItem', 'AXCell', 'AXSlider', 'AXIncrementor', 'AXComboBox',
  'AXSegmentedControl',
]);

const text = (v) => (typeof v === 'string' && v.trim() ? v.trim() : '');

/** `frame` {x, y, width, height}, or the `AXFrame` string `{{x, y}, {w, h}}` when that is all there is. */
function frameOf(node) {
  const f = node.frame;
  if (f && typeof f === 'object') {
    const box = { x: Number(f.x), y: Number(f.y), w: Number(f.width), h: Number(f.height) };
    if (Object.values(box).every(Number.isFinite)) return box;
  }
  const m = /^\{\{(-?[\d.]+),\s*(-?[\d.]+)\},\s*\{(-?[\d.]+),\s*(-?[\d.]+)\}\}$/.exec(text(node.AXFrame));
  return m ? { x: +m[1], y: +m[2], w: +m[3], h: +m[4] } : null;
}

/**
 * `describe-ui` (the envelope, or the bare array) → elements in tree order, each with its parent.
 * In AXe 2.0.4 the result is an array of roots; the app's root is
 * `{type: "Application", AXLabel: "Acme", pid, frame, children: […]}`.
 *
 * @returns {{type: string, role: string, subrole: string, label: string, id: string, title: string,
 *   secure: boolean, app: boolean, pid: number|null, frame: {x: number, y: number, w: number, h: number}|null,
 *   parent: object|null}[]}
 */
export function parseAxTree(reply) {
  const root = reply && typeof reply === 'object' && !Array.isArray(reply) && 'data' in reply ? reply.data?.result : reply;
  const nodes = [];
  const stack = [];
  // Pushed in reverse so the first child is read first: tree order, as describe-ui prints it.
  const push = (list, parent) => {
    for (let i = list.length - 1; i >= 0; i--) {
      const raw = list[i];
      if (raw && typeof raw === 'object' && !Array.isArray(raw)) stack.push([raw, parent]);
    }
  };
  push(Array.isArray(root) ? root : [root], null);
  while (stack.length && nodes.length < MAX_TREE_NODES) {
    const [raw, parent] = stack.pop();
    const type = text(raw.type);
    const role = text(raw.role);
    const subrole = text(raw.subrole);
    const node = {
      type,
      role,
      subrole,
      label: text(raw.AXLabel),
      id: text(raw.AXUniqueId),
      title: text(raw.title),
      // Its value is the typed password (shown as dots); it is never read, nor is any field's.
      secure: subrole === 'AXSecureTextField' || role === 'AXSecureTextField' || type === 'SecureTextField',
      app: type === 'Application' || role === 'AXApplication',
      pid: Number.isInteger(raw.pid) && raw.pid > 0 ? raw.pid : null,
      frame: frameOf(raw),
      parent,
    };
    nodes.push(node);
    if (Array.isArray(raw.children)) push(raw.children, node);
  }
  return nodes;
}

const area = (n) => n.frame.w * n.frame.h;
const interactive = (n) => INTERACTIVE_TYPES.has(n.type) || INTERACTIVE_ROLES.has(n.role) || n.subrole === 'AXSwitch';
/** The system scroll indicator, an AXSlider over the right edge of every list (measured). */
const scrollBar = (n) => (n.type === 'Slider' || n.role === 'AXSlider') && /scroll ?bar|스크롤/i.test(n.label);
const named = (n) => Boolean(n.label || n.id || interactive(n));
/** What the element says: its label. A field's value (what was typed) is never sent, as on the web. */
const labelOf = (n) => n.label || n.title || '';

/**
 * The element a person meant by pressing (px, py), in points: the smallest one there that can be
 * pressed or says something. A label inside a control (a switch row's text, a cell's text), or a
 * control with no name of its own (a switch's knob), means the named control around it.
 *
 * @returns {{node: object, text: string}|null}
 */
export function pickElement(nodes, px, py) {
  const hits = nodes.filter(
    (n) => n.frame && !n.app && !scrollBar(n) && n.frame.w > 0 && n.frame.h > 0 &&
      px >= n.frame.x && px < n.frame.x + n.frame.w && py >= n.frame.y && py < n.frame.y + n.frame.h
  );
  if (!hits.length) return null;
  const smallest = (list) => list.reduce((best, n) => (!best || area(n) <= area(best) ? n : best), null);
  let node = smallest(hits.filter(named)) ?? smallest(hits);
  let label = labelOf(node);
  if (!interactive(node) || (!node.label && !node.id)) {
    for (let a = node.parent, i = 0; a && i < 3; a = a.parent, i++) {
      if (interactive(a) && hits.includes(a) && (a.label || a.id || !interactive(node))) {
        label = labelOf(a) || label;
        node = a;
        break;
      }
    }
  }
  return { node, text: label };
}

/**
 * The screen's title: a heading in the top fifth (a navigation bar's title, large or inline). A
 * section header lower down is not one.
 */
export function screenTitle(nodes, points) {
  const heading = nodes.find(
    (n) => (n.type === 'Heading' || n.role === 'AXHeading') && n.label && n.frame && n.frame.y + n.frame.h / 2 < points.height * 0.2
  );
  return heading ? clip(heading.label, 100) : null;
}

/**
 * Which screen this is: the shared app's bundle ID while it is in front, the app's name, the
 * navigation title. Undefined when none of them is known.
 *
 * @param {{width: number, height: number}|null} points the screen in points; without it there is no title
 */
export function describeIosScreen(nodes, points, shared = {}) {
  const app = nodes.find((n) => n.app) ?? null;
  const screen = {};
  const front = shared.bundleId && (shared.appPid == null || app?.pid == null || app.pid === shared.appPid);
  if (front) screen.bundleId = shared.bundleId;
  if (app?.label) screen.appName = clip(app.label, 100);
  const title = points ? screenTitle(nodes, points) : null;
  if (title) screen.title = title;
  const label = screen.bundleId ?? screen.appName;
  return label ? { label, ...screen } : Object.keys(screen).length ? screen : undefined;
}

/**
 * The description of the element at the point (§14.3), without images. A point on the background
 * says NO_ELEMENT with the nearest named element on each side (`near`, §15.4).
 *
 * @param {{width: number, height: number}} points the screen in points (the tree's coordinates)
 * @param {{bundleId?: string|null, appPid?: number|null}} [shared] the shared app; its bundle ID is
 *   named only while that app is the one in front
 */
export function describeIosPoint(nodes, probe, points, shared = {}) {
  const result = pointResult(nodes, probe, points, shared);
  if (!result.ok) result.near = nearAround({ rect: { x: probe.x, y: probe.y, w: 0, h: 0 }, nodes, boundsOf: boundsIn(points), memberOf: memberOfIn(nodes) });
  return result;
}

function pointResult(nodes, probe, points, shared) {
  const where = describeIosScreen(nodes, points, shared);
  const picked = pickElement(nodes, probe.x * points.width, probe.y * points.height);
  // Only a full-screen container that says nothing there: the background.
  if (!picked || (!named(picked.node) && area(picked.node) >= 0.8 * points.width * points.height)) {
    return unresolved(probe, 'NO_ELEMENT', where);
  }
  const { node, text: said } = picked;
  const f = node.frame;
  const element = { type: typeOf(node) };
  const shown = clip(said, LIMIT.text);
  if (shown) element.text = shown;
  const id = clip(node.id, 100);
  if (id) element.id = id;
  const x0 = Math.max(f.x, 0);
  const y0 = Math.max(f.y, 0);
  element.bounds = {
    x: r4(x0 / points.width),
    y: r4(y0 / points.height),
    w: r4((Math.min(f.x + f.w, points.width) - x0) / points.width),
    h: r4((Math.min(f.y + f.h, points.height) - y0) / points.height),
  };

  const ancestors = [];
  for (let a = node.parent; a && ancestors.length < LIMIT.ancestors; a = a.parent) {
    if (a.app || !(a.id || a.label)) continue;
    const entry = { type: clip(a.type || a.role.replace(/^AX/, ''), 40) ?? 'Element' };
    if (a.id) entry.id = clip(a.id, 100);
    if (a.label) entry.text = clip(a.label, 80);
    ancestors.push(entry);
  }
  return {
    probeId: probe.probeId,
    ok: true,
    ...(where ? { screen: where } : {}),
    point: { x: r4(probe.x), y: r4(probe.y) },
    element,
    ancestors,
    components: [],
    scopes: iosLadder(node, element, points),
    defaultScope: 0,
  };
}

/** The element's kind as the result names it; a secure field says so whatever AXe calls it. */
const typeOf = (n) => clip(n.secure ? 'SecureTextField' : n.type || n.role.replace(/^AX/, ''), 40) ?? 'Element';

/**
 * The description of a dragged rectangle (§15.4), without images: the element at its centre exactly
 * as a tap there would say it (`element`, left out on the background), the elements inside it
 * (`members`), the containers holding it (`scopes`, the rectangle first), and around an empty one
 * the nearest elements (`near`). All from the one tree.
 *
 * @param {{probeId: string, rect: {x: number, y: number, w: number, h: number}}} probe
 */
export function describeIosRegion(nodes, probe, points, shared = {}) {
  const centre = { probeId: probe.probeId, ...probePoint(probe) };
  const at = pointResult(nodes, centre, points, shared);
  const where = describeIosScreen(nodes, points, shared);
  const region = buildRegion({ rect: probe.rect, nodes, boundsOf: boundsIn(points), describe: describeStep, memberOf: memberOfIn(nodes) });
  return {
    probeId: probe.probeId,
    ok: true,
    ...(where ? { screen: where } : {}),
    point: { x: r4(centre.x), y: r4(centre.y) },
    ...(at.ok ? { element: at.element } : {}),
    ancestors: at.ok ? at.ancestors : [],
    components: [],
    scopes: region.scopes,
    defaultScope: 0,
    members: region.members,
    empty: region.empty,
    ...(region.near ? { near: region.near } : {}),
  };
}

const LIST_TYPE = /^(Table|CollectionView|List|Outline)$/;

/** An element's frame as screen ratios, clipped to the screen; null when none of it is on screen. */
const boundsIn = (points) => (n) => {
  if (!n.frame) return null;
  const x0 = Math.max(n.frame.x, 0);
  const y0 = Math.max(n.frame.y, 0);
  const x1 = Math.min(n.frame.x + n.frame.w, points.width);
  const y1 = Math.min(n.frame.y + n.frame.h, points.height);
  return x1 > x0 && y1 > y0 ? { x: x0 / points.width, y: y0 / points.height, w: (x1 - x0) / points.width, h: (y1 - y0) / points.height } : null;
};

/**
 * The scope ladder (§15.3) from the accessibility tree: the element, then the containers around it
 * that have an accessibilityIdentifier or a label, cells and lists, then the screen. SwiftUI gives
 * most wrapper groups neither, so its ladders are short until the app names a container
 * (`.accessibilityElement(children: .contain)` with an identifier).
 */
function iosLadder(node, element, points) {
  return buildLadder({ element, node, boundsOf: boundsIn(points), describe: describeStep });
}

function describeStep(n) {
  if (n.app || n.secure) return null;
  const cell = n.type === 'Cell' || n.role === 'AXCell';
  if (!n.id && !n.label && !cell && !LIST_TYPE.test(n.type)) return null;
  return { kind: 'group', type: n.type || n.role.replace(/^AX/, '') || 'Group', text: n.label || null, id: n.id || null };
}

/**
 * An element as a member of an area or a neighbour (§15.4): what pickElement calls named — a label,
 * an identifier, or something to press or type into — never the app root or the scroll indicator.
 * A control with no label of its own (a cell) is named by the first label inside it, as a tap on it
 * would be. Labels only: a field's value (what was typed, a password's dots) is never read.
 */
function memberOfIn(nodes) {
  const inner = new Map();
  for (const n of nodes) {
    if (!n.label) continue;
    for (let a = n.parent, i = 0; a && i < 3; a = a.parent, i++) {
      if (interactive(a) && !inner.has(a)) inner.set(a, n.label);
    }
  }
  return (n) => {
    if (n.app || scrollBar(n) || !named(n)) return null;
    const own = labelOf(n);
    return { type: typeOf(n), text: own || (interactive(n) ? inner.get(n) : '') || null, id: n.id || null, interactive: interactive(n) };
  };
}

/**
 * Reads the point (or the dragged rectangle, §15.4) on the simulator: the tree and a screenshot side
 * by side (0.47 s together, measured on an iPhone 17 Pro simulator).
 *
 * @param {object} o
 * @param {{request: Function, screenshot?: Function}} o.commands AxeCommands
 * @param {{probeId: string, x: number, y: number}|{probeId: string, screen: true}|
 *   {probeId: string, rect: object}} o.probe a point, the whole screen (resolveIosScreen) or a
 *   rectangle
 * @param {string|null} o.project
 * @param {string|null} [o.bundleId] the shared app
 * @param {number|null} [o.appPid]
 * @param {{width: number, height: number}|null} [o.points] the screen in points from `displays`, used
 *   when the tree has no app root to tell it
 * @param {Buffer|null} [o.frame] the latest stream-video JPEG: the screen as the viewer saw it
 * @returns {Promise<{result: object, crop: Buffer|null, screen: Buffer|null}>}
 */
export async function resolveIosPin({ commands, probe, project, bundleId = null, appPid = null, points = null, frame = null, findHints = findSourceHints }) {
  if (probe.screen) return resolveIosScreen({ commands, probe, bundleId, appPid, points, frame });
  const [reply, png] = await Promise.all([
    commands.request(['describe-ui']).catch(() => null),
    typeof commands.screenshot === 'function' ? commands.screenshot().catch(() => null) : null,
  ]);
  const shot = png ? parsePng(png) : null;
  const nodes = reply && !reply.didError ? parseAxTree(reply) : [];
  const root = nodes.find((n) => n.app && n.frame && n.frame.w > 0 && n.frame.h > 0);
  const size = root ? { width: root.frame.w, height: root.frame.h } : points;
  const screen = usable(frame) ? frame : shot ? screenJpeg(shot) : null;
  // The screenshot in pixels and the tree in points must be the same screen the same way round.
  const k = shot && size ? shot.width / size.width : 0;
  const fits = k > 0 && Math.abs(shot.height / k - size.height) <= 2;
  const cut = (rect) => (fits ? jpegUnder(shot, scaled(rect, k), { maxSide: CROP_MAX_SIDE, maxBytes: MAX_CROP_BYTES, quality: 80 }) : null);
  // A box in screen ratios with the margin an element gets: an element, or the rectangle as drawn.
  const around = (b) => cut(padded({ x: b.x * size.width, y: b.y * size.height, width: b.w * size.width, height: b.h * size.height }, size));

  if (!size || !nodes.length) {
    // No tree to read (AXe failed, or an app with nothing in its accessibility tree): the point and
    // the picture still go (§14.1).
    const crop = size ? (probe.rect ? around(probe.rect) : cut(spot(probe, size))) : null;
    return { result: unresolved(probe, 'UNSUPPORTED'), crop, screen };
  }
  const result = probe.rect ? describeIosRegion(nodes, probe, size, { bundleId, appPid }) : describeIosPoint(nodes, probe, size, { bundleId, appPid });
  if (result.ok) {
    result.sourceHints = await findHints({ project, components: result.components, element: result.element ?? {}, scopeIds: scopeIdsOf(result.scopes) }).catch(() => []);
  }
  const b = result.element?.bounds;
  const crop = probe.rect ? around(probe.rect) : b ? around(b) : cut(spot(probe, size));
  return { result, crop, screen };
}

/**
 * The whole screen (§15.6): one tree read for which screen it is, and the frame the viewer saw. A
 * screenshot is taken only when there is no frame to send. The tree goes through the same line as
 * the inputs, like a point's, so it describes the screen after a tap that came before.
 *
 * @returns {Promise<{result: object, crop: null, screen: Buffer|null}>}
 */
export async function resolveIosScreen({ commands, probe, bundleId = null, appPid = null, points = null, frame = null }) {
  const own = usable(frame);
  const [reply, png] = await Promise.all([
    commands.request(['describe-ui']).catch(() => null),
    !own && typeof commands.screenshot === 'function' ? commands.screenshot().catch(() => null) : null,
  ]);
  const nodes = reply && !reply.didError ? parseAxTree(reply) : [];
  const root = nodes.find((n) => n.app && n.frame && n.frame.w > 0 && n.frame.h > 0);
  const size = root ? { width: root.frame.w, height: root.frame.h } : points;
  const where = nodes.length ? describeIosScreen(nodes, size, { bundleId, appPid }) : undefined;
  const shot = png ? parsePng(png) : null;
  return { result: screenResult(probe, where), crop: null, screen: own ? frame : shot ? screenJpeg(shot) : null };
}

const usable = (frame) => Boolean(frame) && frame.length <= MAX_SCREEN_BYTES;

const screenJpeg = (shot) =>
  jpegUnder(shot, { x: 0, y: 0, width: shot.width, height: shot.height }, { maxSide: SCREEN_MAX_SIDE, maxBytes: MAX_SCREEN_BYTES, quality: 70 });

const scaled = (r, k) => ({ x: r.x * k, y: r.y * k, width: r.width * k, height: r.height * k });

/** The element with a margin, kept on screen. Points. */
function padded(rect, size) {
  const x = Math.max(0, rect.x - CROP_MARGIN_PT);
  const y = Math.max(0, rect.y - CROP_MARGIN_PT);
  const right = Math.min(size.width, rect.x + rect.width + CROP_MARGIN_PT);
  const bottom = Math.min(size.height, rect.y + rect.height + CROP_MARGIN_PT);
  return { x, y, width: right - x, height: bottom - y };
}

/** A fixed-size area centred on the point, kept on screen. Points. */
function spot(probe, size) {
  const width = Math.min(SPOT_PT.width, size.width);
  const height = Math.min(SPOT_PT.height, size.height);
  const x = Math.min(Math.max(probe.x * size.width - width / 2, 0), size.width - width);
  const y = Math.min(Math.max(probe.y * size.height - height / 2, 0), size.height - height);
  return { x, y, width, height };
}
