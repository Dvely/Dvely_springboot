/**
 * The scope ladder (docs/device-preview-share-design.md §15.3) and the dragged area (§15.4) for
 * platforms whose screen arrives as a node tree on this machine: Android (`uiautomator dump`) and
 * iOS (AXe `describe-ui`).
 *
 * The web works it out inside the page (pin-web.js `inspectPoint`), because the DOM never leaves
 * the page; the rules are the same:
 *   - narrowest first, the element the person pressed (or the area they dragged); the screen last,
 *     always
 *   - only ancestors that say something are steps (the platform decides which, `describe`)
 *   - two steps whose boxes differ by at most 10% of area are one step, named by the more telling
 *     of them (a component name over an id over a bare type), so widening never stops twice in place
 *   - a box of 80% of the screen or more is the screen step already
 *   - a box smaller than the step below it is no step: widening never narrows
 *   - six steps at most
 *
 * A dragged area adds what is inside it (`members`), the smallest container holding it, and — when
 * nothing named is inside — the nearest named thing on each side (`near`), so an agent can tell
 * where "here" is: "under the plan title, above the footer".
 *
 * Pure: nodes in, plain objects out. The platforms test their trees against it with fixtures.
 */
import { LIMIT, clip, r4 } from './pin.js';

/** The screen step: whatever the device, the whole of it. */
export const SCREEN_STEP = Object.freeze({ kind: 'screen', type: 'screen', bounds: Object.freeze({ x: 0, y: 0, w: 1, h: 1 }) });

/** §15.4 */
export const MEMBER_INSIDE = 0.6;
export const REGION_COVER = 0.9;
export const NEAR_DIRS = Object.freeze(['up', 'down', 'left', 'right']);

/** Boxes are screen ratios; anything closer than this is touching (pixel rounding, ratio maths). */
const EPS = 1e-6;

const areaOf = (b) => b.w * b.h;
const alike = (a, b) => Math.abs(a - b) <= 0.1 * Math.max(a, b);
const usable = (b) => Boolean(b) && b.w > 0 && b.h > 0;
const ratioBox = (b) => ({ x: r4(b.x), y: r4(b.y), w: r4(b.w), h: r4(b.h) });

/** The area two boxes share. */
export function overlapArea(a, b) {
  const w = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x);
  const h = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y);
  return w > 0 && h > 0 ? w * h : 0;
}

/**
 * @param {object} o
 * @param {{type: string, text?: string, id?: string, bounds?: object}} o.element the element as
 *   already described (§14.3); its bounds are viewport ratios
 * @param {object} o.node the platform node the element came from; `node.parent` walks outward
 * @param {(node: object) => ({x: number, y: number, w: number, h: number}|null)} o.boundsOf a node's
 *   box as screen ratios clipped to the screen, or null when it is not on screen
 * @param {(node: object) => ({kind: 'group'|'component', type: string, text?: string, id?: string,
 *   component?: string}|null)} o.describe what a node is as a step, or null when it says nothing
 * @param {string|null} [o.component] the element's own component name, if the platform knows one
 * @param {number} [o.max] steps at most, the screen included
 * @param {object[]} [o.nodes] the whole tree, flat; with `sameShape`, each step says how many look the same (§15.3 peers)
 * @param {(a: object, b: object) => boolean} [o.sameShape] whether two siblings are the same thing (the platform's rule)
 * @returns {object[]} the steps, `{kind, type, text?, id?, component?, bounds, peers?}`, screen last
 */
export function buildLadder({ element, node, boundsOf, describe, component = null, max = LIMIT.scopes, nodes = null, sameShape = null }) {
  const first = { kind: 'element', type: clip(element.type, 40) ?? 'element' };
  const said = clip(element.text, LIMIT.scopeText);
  if (said) first.text = said;
  const id = clip(element.id, 100);
  if (id) first.id = id;
  if (component) first.component = component;
  if (element.bounds) first.bounds = element.bounds;
  const steps = [first];
  const nodesOf = new Map([[first, [node]]]);
  climb(steps, node?.parent, { boundsOf, describe, max, nodesOf });
  if (Array.isArray(nodes) && typeof sameShape === 'function') {
    for (const step of steps) {
      // The step's own node first, then the same-size ones merged into it: in a list the row, not the label in it, has look-alikes
      for (const n of nodesOf.get(step) ?? []) {
        const peers = peersOf({ node: n, nodes, boundsOf, sameShape });
        if (peers) {
          step.peers = peers;
          break;
        }
      }
    }
  }
  steps.push(screenStep());
  return steps;
}

/**
 * §15.3: how many siblings look the same as `node` (itself included), and the boxes of those on screen
 * (20 at most). One alone is not "the same thing, all of them".
 */
export function peersOf({ node, nodes, boundsOf, sameShape, max = 20 }) {
  const parent = node?.parent;
  if (!parent) return null;
  let count = 0;
  const bounds = [];
  for (const n of nodes) {
    if (n.parent !== parent || !sameShape(n, node)) continue;
    count++;
    const b = bounds.length < max ? boundsOf(n) : null;
    if (usable(b)) bounds.push(ratioBox(b));
  }
  return count >= 2 ? { count, bounds } : null;
}

/**
 * Adds the steps from `from` outward. The region step (§15.4) is the person's own rectangle: nothing
 * merges into it and nothing is measured against it, so the container that holds it is always a
 * step of its own, even one nearly as big.
 */
function climb(steps, from, { boundsOf, describe, max, nodesOf = null }) {
  for (let a = from, i = 0; a && i < 40; a = a.parent, i++) {
    const what = describe(a);
    if (!what) continue;
    const bounds = boundsOf(a);
    if (!usable(bounds)) continue;
    const area = areaOf(bounds);
    if (area >= 0.8) continue; // most of the screen: the screen step says the same
    const step = { kind: what.kind, type: clip(what.type, 40) ?? 'group' };
    const text = clip(what.text, LIMIT.scopeText);
    if (text) step.text = text;
    const stepId = clip(what.id, 100);
    if (stepId) step.id = stepId;
    if (what.component) step.component = clip(what.component, 100);
    step.bounds = ratioBox(bounds);

    const last = steps[steps.length - 1];
    const measured = last.bounds && last.kind !== 'region';
    if (measured && alike(area, areaOf(last.bounds))) {
      nodesOf?.get(last)?.push(a);
      merge(last, step);
      continue;
    }
    // Widening never gets smaller. SwiftUI reports a section's identifier on a per-row box that can
    // be smaller than the control in it (measured: a 338×28 box around a 370×52 switch row).
    if (measured && area < areaOf(last.bounds)) continue;
    if (steps.length >= max - 1) break;
    nodesOf?.set(step, [a]);
    steps.push(step);
  }
}

const screenStep = () => ({ ...SCREEN_STEP, bounds: { ...SCREEN_STEP.bounds } });

/**
 * Folds `step` into `kept` (the same box): a component name moves in and makes it a component step;
 * the element step otherwise stays what `element` says. A group without an id takes the outer one's.
 */
function merge(kept, step) {
  if (step.component && !kept.component) {
    kept.component = step.component;
    if (kept.kind === 'group') kept.kind = 'component';
  }
  if (kept.kind === 'element') return;
  if (step.id && !kept.id) kept.id = step.id;
  if (step.text && !kept.text) kept.text = step.text;
}

/** The wider steps' ids with their step numbers, for the source search (§15.3). */
export function scopeIdsOf(scopes) {
  return (Array.isArray(scopes) ? scopes : [])
    .map((step, scope) => ({ id: step?.id, scope }))
    .filter((s) => s.scope > 0 && typeof s.id === 'string' && s.id);
}

/**
 * What a dragged rectangle (§15.4) takes in, from the whole tree.
 *
 *   - `members`: named things with at least 60% of their box inside the rectangle, in reading order,
 *     twelve at most. A member inside a kept interactive one is part of it (a button's label, a
 *     switch's knob) and is left out; inside a container that only has a name, it stays, because a
 *     container's name on these platforms is its own and does not say what is in it
 *   - `scopes`: the rectangle itself, then the smallest container whose box holds 90% of it, then
 *     outward by the ladder's rules, then the screen. 90% of a rectangle always includes its centre,
 *     so the container is one the centre element sits in
 *   - `near`: only when nothing is inside — the nearest named thing on each side (nearAround)
 *
 * @param {object} o
 * @param {{x: number, y: number, w: number, h: number}} o.rect screen ratios, with some area
 * @param {object[]} o.nodes the whole tree in tree order (a parent before its children), each with
 *   `parent`
 * @param {Function} o.boundsOf as buildLadder
 * @param {Function} o.describe as buildLadder
 * @param {(node: object) => ({type: string, text?: string, id?: string, interactive?: boolean}|null)}
 *   o.memberOf what a node is when it is named, or null when a person would not name it. Never what
 *   was typed into it
 * @param {number} [o.max] steps at most, the region and the screen included
 * @param {Function} [o.sameShape] as buildLadder: the container steps say how many look the same
 * @returns {{scopes: object[], members: object[], empty: boolean, near?: object[]}}
 */
export function buildRegion({ rect, nodes, boundsOf, describe, memberOf, max = LIMIT.scopes, sameShape = null }) {
  const found = namedNodes(nodes, boundsOf, memberOf, (b) => overlapArea(b, rect) >= MEMBER_INSIDE * areaOf(b) - EPS);
  const members = readingOrder(found)
    .slice(0, LIMIT.members)
    .map((m) => itemOf(m));

  const steps = [{ kind: 'region', type: 'region', bounds: ratioBox(rect), members: members.length }];
  const want = REGION_COVER * areaOf(rect) - EPS;
  let container = null;
  let smallest = Infinity;
  // Tree order puts a parent first, so of two boxes the same size the inner one (`<=`) is taken:
  // it is the more particular name, and the outer one merges into it on the way out.
  for (const node of nodes) {
    if (!describe(node)) continue;
    const b = boundsOf(node);
    if (!usable(b)) continue;
    const area = areaOf(b);
    if (area >= 0.8 || overlapArea(b, rect) < want) continue;
    if (area <= smallest) {
      smallest = area;
      container = node;
    }
  }
  const nodesOf = new Map();
  if (container) climb(steps, container, { boundsOf, describe, max, nodesOf });
  if (typeof sameShape === 'function') {
    for (const step of steps) {
      for (const n of nodesOf.get(step) ?? []) {
        const peers = peersOf({ node: n, nodes, boundsOf, sameShape });
        if (peers) {
          step.peers = peers;
          break;
        }
      }
    }
  }
  steps.push(screenStep());

  const out = { scopes: steps, members, empty: members.length === 0 };
  if (out.empty) out.near = nearAround({ rect, nodes, boundsOf, memberOf });
  return out;
}

/**
 * The nearest named thing on each side of `rect` (§15.4): above (its bottom at or above the top),
 * below, left, right, each overlapping the rectangle's span on the other axis. A box of 80% of the
 * screen or more is the background, not a neighbour. A zero-size `rect` is a point: a tap that found
 * nothing gets the same answer.
 *
 * @returns {{dir: string, type: string, text?: string, id?: string, bounds: object, distance: number}[]}
 *   up, down, left, right, those that have one; `distance` is the gap as a screen ratio
 */
export function nearAround({ rect, nodes, boundsOf, memberOf }) {
  const best = {};
  for (const c of namedNodes(nodes, boundsOf, memberOf, () => true)) {
    for (const [dir, gap] of sides(c.bounds, rect)) {
      const cur = best[dir];
      // Equally close: the smaller box is the more particular neighbour (a title over its card).
      if (!cur || gap < cur.gap - EPS || (gap <= cur.gap + EPS && areaOf(c.bounds) < areaOf(cur.bounds))) best[dir] = { ...c, gap };
    }
  }
  return NEAR_DIRS.filter((dir) => best[dir]).map((dir) => ({ dir, ...itemOf(best[dir]), distance: r4(Math.max(0, best[dir].gap)) }));
}

/** Which sides of `r` the box `b` is entirely on, with the gap to each. */
function sides(b, r) {
  const out = [];
  const xs = spans(b.x, b.x + b.w, r.x, r.x + r.w);
  const ys = spans(b.y, b.y + b.h, r.y, r.y + r.h);
  if (xs && b.y + b.h <= r.y + EPS) out.push(['up', r.y - (b.y + b.h)]);
  if (xs && b.y >= r.y + r.h - EPS) out.push(['down', b.y - (r.y + r.h)]);
  if (ys && b.x + b.w <= r.x + EPS) out.push(['left', r.x - (b.x + b.w)]);
  if (ys && b.x >= r.x + r.w - EPS) out.push(['right', b.x - (r.x + r.w)]);
  return out;
}

/** Whether [a0, a1] overlaps [b0, b1]; a zero-width [b0, b1] is a line that must cross [a0, a1]. */
function spans(a0, a1, b0, b1) {
  return b1 - b0 > EPS ? a0 < b1 - EPS && a1 > b0 + EPS : a0 <= b0 + EPS && b0 <= a1 + EPS;
}

/**
 * The named nodes on screen that pass `test`, leaving out any inside a kept interactive one (it
 * speaks for them, as pickNode/pickElement say). Tree order, so an outer node is decided first.
 */
function namedNodes(nodes, boundsOf, memberOf, test) {
  const out = [];
  const speaking = new Set();
  for (const node of nodes) {
    const what = memberOf(node);
    if (!what) continue;
    const bounds = boundsOf(node);
    if (!usable(bounds) || areaOf(bounds) >= 0.8 || !test(bounds)) continue;
    if (insideOf(node, speaking)) continue;
    out.push({ node, what, bounds });
    if (what.interactive) speaking.add(node);
  }
  return out;
}

function insideOf(node, set) {
  if (!set.size) return false;
  for (let a = node.parent, i = 0; a && i < 60; a = a.parent, i++) if (set.has(a)) return true;
  return false;
}

/** `{type, text?, id?, bounds}` as the viewer and the prompt read a member or a neighbour. */
function itemOf({ what, bounds }) {
  const item = { type: clip(what.type, 40) ?? 'element' };
  const text = clip(what.text, LIMIT.memberText);
  if (text) item.text = text;
  const id = clip(what.id, 100);
  if (id) item.id = id;
  item.bounds = ratioBox(bounds);
  return item;
}

/**
 * Top to bottom by row, then left to right. Boxes whose vertical centres are within half the smaller
 * of their heights sit on one row (a button and a link beside it, though one is taller).
 *
 * @template {{bounds: {x: number, y: number, w: number, h: number}}} T
 * @param {T[]} list
 * @returns {T[]}
 */
export function readingOrder(list) {
  const cy = (b) => b.y + b.h / 2;
  const sorted = list.slice().sort((a, b) => cy(a.bounds) - cy(b.bounds));
  const rows = [];
  for (const item of sorted) {
    const row = rows[rows.length - 1];
    const c = cy(item.bounds);
    if (row && row.some((o) => Math.abs(cy(o.bounds) - c) <= Math.min(o.bounds.h, item.bounds.h) / 2 + EPS)) row.push(item);
    else rows.push([item]);
  }
  return rows.flatMap((row) => row.sort((a, b) => a.bounds.x - b.bounds.x));
}
