/**
 * Pinning on a shared Android emulator (§14.3, #682 PR-2): which view is at the viewer's point,
 * from `uiautomator dump`; which Activity is in front; a crop and the screen, from `screencap`.
 *
 * The dump reports a view's accessibility class: a `FancyButton extends Button` shows up as
 * `android.widget.Button` (measured, API 35). So the app's own names come mostly from the Activity,
 * resource ids and strings; a custom class is named only when it reports itself.
 *
 * All of it is read-only: an accessibility snapshot, `dumpsys`, a screenshot. Nothing is tapped.
 */
import { MAX_CROP_BYTES, MAX_SCREEN_BYTES, LIMIT, clip, r4, unresolved } from '../pin.js';
import { jpegUnder, parseScreencap } from '../jpeg.js';
import { findSourceHints } from '../source-hints.js';

const CROP_MARGIN = 24;
const CROP_MAX_SIDE = 800;
const SCREEN_MAX_SIDE = 1280;
/** Around a point with no view: enough to recognise the place, in device pixels. */
const SPOT = { width: 480, height: 320 };

/** Classes that are the platform's, not the app's: not worth naming as components. */
const PLATFORM_CLASS = /^(android|androidx|com\.android|com\.google\.android\.material|java|kotlin)\./;

const ENTITIES = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };
const decode = (s) =>
  s.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (m, e) => {
    if (e[0] === '#') {
      const code = e[1] === 'x' || e[1] === 'X' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10);
      return Number.isFinite(code) && code > 0 && code < 0x110000 ? String.fromCodePoint(code) : '';
    }
    return ENTITIES[e] ?? m;
  });

/**
 * `uiautomator dump` XML → nodes in document order, each with its parent.
 *
 * @returns {{cls: string, text: string, desc: string, rid: string, pkg: string, clickable: boolean,
 *   password: boolean, bounds: {l: number, t: number, r: number, b: number}|null, parent: object|null}[]}
 */
export function parseUiDump(xml) {
  const nodes = [];
  const stack = [];
  const re = /<node\b([^>]*?)(\/?)>|<\/node>/g;
  let m;
  while ((m = re.exec(String(xml ?? '')))) {
    if (m[0] === '</node>') {
      stack.pop();
      continue;
    }
    const attrs = {};
    for (const a of m[1].matchAll(/([\w-]+)="([^"]*)"/g)) attrs[a[1]] = decode(a[2]);
    const b = /^\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]$/.exec(attrs.bounds ?? '');
    const node = {
      cls: attrs.class ?? '',
      text: attrs.text ?? '',
      desc: attrs['content-desc'] ?? '',
      rid: attrs['resource-id'] ?? '',
      pkg: attrs.package ?? '',
      clickable: attrs.clickable === 'true' || attrs['long-clickable'] === 'true',
      password: attrs.password === 'true',
      bounds: b ? { l: +b[1], t: +b[2], r: +b[3], b: +b[4] } : null,
      parent: stack.at(-1) ?? null,
    };
    nodes.push(node);
    if (m[2] !== '/') stack.push(node);
  }
  return nodes;
}

const area = (n) => (n.bounds.r - n.bounds.l) * (n.bounds.b - n.bounds.t);
/** A platform container's id (android:id/content) does not make it something a person points at. */
const named = (n) => Boolean(n.clickable || n.text || n.desc || (n.rid && !n.rid.startsWith('android:')));
const shortClass = (cls) => cls.split('.').pop() || cls;
const shortId = (rid) => (rid.includes(':id/') ? rid.slice(rid.indexOf(':id/') + 4) : rid);

/**
 * The view a person meant by pressing (px, py): the smallest one there that can be pressed or says
 * something. A label inside a pressable container (Compose, custom buttons) means the container,
 * named by the label.
 *
 * @returns {{node: object, text: string}|null}
 */
export function pickNode(nodes, px, py) {
  const hits = nodes.filter(
    (n) => n.bounds && n.bounds.r > n.bounds.l && n.bounds.b > n.bounds.t && px >= n.bounds.l && px < n.bounds.r && py >= n.bounds.t && py < n.bounds.b
  );
  if (!hits.length) return null;
  const smallest = (list) => list.reduce((best, n) => (!best || area(n) <= area(best) ? n : best), null);
  let node = smallest(hits.filter(named)) ?? smallest(hits);
  let text = labelOf(node);
  if (!node.clickable) {
    for (let a = node.parent, i = 0; a && i < 3; a = a.parent, i++) {
      if (a.clickable && hits.includes(a)) {
        text = labelOf(a) || text;
        node = a;
        break;
      }
    }
  }
  return { node, text };
}

/** What the view says. A password field's text is never read. */
function labelOf(n) {
  if (n.password) return n.desc || '';
  return n.text || n.desc || '';
}

/**
 * The description of the view at the point (§14.3), without images.
 *
 * @param {{width: number, height: number}} size the screen in device pixels (the dump's coordinates)
 */
export function describeAndroidPoint(nodes, probe, size, top) {
  const screen = top ? { label: `${top.package}/${top.activity}`, package: top.package, activity: top.activity } : undefined;
  const px = probe.x * size.width;
  const py = probe.y * size.height;
  const picked = pickNode(nodes, px, py);
  // Only the window itself there (a decor view, a full-screen layout that says nothing): background.
  if (!picked || (!named(picked.node) && area(picked.node) >= 0.8 * size.width * size.height)) {
    return unresolved(probe, 'NO_ELEMENT', screen);
  }
  const { node, text } = picked;
  const b = node.bounds;
  const element = { type: clip(shortClass(node.cls), 40) ?? 'View' };
  const label = clip(text, LIMIT.text);
  if (label) element.text = label;
  const id = clip(shortId(node.rid), 100);
  if (id) element.id = id;
  element.bounds = {
    x: r4(b.l / size.width),
    y: r4(b.t / size.height),
    w: r4((Math.min(b.r, size.width) - Math.max(b.l, 0)) / size.width),
    h: r4((Math.min(b.b, size.height) - Math.max(b.t, 0)) / size.height),
  };

  const ancestors = [];
  const components = [];
  const seen = new Set();
  const component = (cls) => {
    if (!cls || PLATFORM_CLASS.test(cls)) return;
    const name = shortClass(cls);
    if (/^[A-Z][A-Za-z0-9_$]{1,80}$/.test(name) && !seen.has(name)) {
      seen.add(name);
      components.push({ name });
    }
  };
  component(node.cls);
  for (let a = node.parent; a; a = a.parent) {
    component(a.cls);
    // The platform's own containers (android:id/content, decor views) say nothing about the app.
    const appId = a.rid && !a.rid.startsWith('android:') ? a.rid : '';
    if (ancestors.length < LIMIT.ancestors && (appId || a.desc)) {
      const entry = { type: clip(shortClass(a.cls), 40) ?? 'View' };
      if (appId) entry.id = clip(shortId(appId), 100);
      if (a.desc) entry.text = clip(a.desc, 80);
      ancestors.push(entry);
    }
  }
  // The Activity is the outermost thing the app wrote that is on screen.
  if (top?.activity) {
    const activity = top.activity.split('.').pop();
    if (/^[A-Z][A-Za-z0-9_$]{1,80}$/.test(activity) && !seen.has(activity)) components.push({ name: activity });
  }
  return {
    probeId: probe.probeId,
    ok: true,
    ...(screen ? { screen } : {}),
    point: { x: r4(probe.x), y: r4(probe.y) },
    element,
    ancestors,
    components: components.slice(0, LIMIT.components),
  };
}

/**
 * Reads the point on the emulator: the dump and the screenshot in parallel.
 *
 * @param {object} o
 * @param {{uiDump: Function, screencapRaw: Function, foregroundActivity: Function}} o.adb
 * @param {{probeId: string, x: number, y: number}} o.probe
 * @param {string|null} o.project
 * @returns {Promise<{result: object, crop: Buffer|null, screen: Buffer|null}>}
 */
export async function resolveAndroidPin({ adb, probe, project, findHints = findSourceHints }) {
  const [xml, raw, top] = await Promise.all([
    adb.uiDump().catch(() => null),
    adb.screencapRaw().catch(() => null),
    adb.foregroundActivity().catch(() => null),
  ]);
  const shot = raw ? parseScreencap(raw) : null;
  const nodes = xml ? parseUiDump(xml) : [];
  const size = shot ? { width: shot.width, height: shot.height } : rootSize(nodes);
  const screen = shot ? jpegUnder(shot, { x: 0, y: 0, width: shot.width, height: shot.height }, { maxSide: SCREEN_MAX_SIDE, maxBytes: MAX_SCREEN_BYTES, quality: 70 }) : null;
  if (!size) return { result: unresolved(probe, xml ? 'NO_ELEMENT' : 'UNSUPPORTED'), crop: null, screen };
  if (!nodes.length) {
    // An app the accessibility tree does not see into (games, Flutter without semantics): the point
    // and the picture still go (§14.1).
    return { result: unresolved(probe, 'UNSUPPORTED', top ? screenOf(top) : undefined), crop: shot ? spot(shot, probe) : null, screen };
  }

  const result = describeAndroidPoint(nodes, probe, size, top);
  if (result.ok) {
    result.sourceHints = await findHints({ project, components: result.components, element: result.element }).catch(() => []);
  }
  let crop = null;
  if (shot) {
    const b = result.element?.bounds;
    crop = b
      ? jpegUnder(
          shot,
          {
            x: b.x * size.width - CROP_MARGIN,
            y: b.y * size.height - CROP_MARGIN,
            width: b.w * size.width + CROP_MARGIN * 2,
            height: b.h * size.height + CROP_MARGIN * 2,
          },
          { maxSide: CROP_MAX_SIDE, maxBytes: MAX_CROP_BYTES, quality: 80 }
        )
      : spot(shot, probe);
  }
  return { result, crop, screen };
}

const screenOf = (top) => ({ label: `${top.package}/${top.activity}`, package: top.package, activity: top.activity });

function spot(shot, probe) {
  const width = Math.min(SPOT.width, shot.width);
  const height = Math.min(SPOT.height, shot.height);
  const x = Math.min(Math.max(probe.x * shot.width - width / 2, 0), shot.width - width);
  const y = Math.min(Math.max(probe.y * shot.height - height / 2, 0), shot.height - height);
  return jpegUnder(shot, { x, y, width, height }, { maxSide: CROP_MAX_SIDE, maxBytes: MAX_CROP_BYTES, quality: 80 });
}

/** Without a screenshot, the screen is as big as the outermost window in the dump. */
function rootSize(nodes) {
  let width = 0;
  let height = 0;
  for (const n of nodes) {
    if (!n.bounds) continue;
    width = Math.max(width, n.bounds.r);
    height = Math.max(height, n.bounds.b);
  }
  return width > 0 && height > 0 ? { width, height } : null;
}
