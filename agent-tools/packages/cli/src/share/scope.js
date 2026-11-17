/**
 * The scope ladder (docs/device-preview-share-design.md §15.3) for platforms whose screen arrives
 * as a node tree on this machine: Android (`uiautomator dump`) and iOS (AXe `describe-ui`).
 *
 * The web works it out inside the page (pin-web.js `inspectPoint`), because the DOM never leaves
 * the page; the rules are the same:
 *   - narrowest first, the element the person pressed; the screen last, always
 *   - only ancestors that say something are steps (the platform decides which, `describe`)
 *   - two steps whose boxes differ by at most 10% of area are one step, named by the more telling
 *     of them (a component name over an id over a bare type), so widening never stops twice in place
 *   - a box of 80% of the screen or more is the screen step already
 *   - a box smaller than the step below it is no step: widening never narrows
 *   - six steps at most
 *
 * Pure: nodes in, plain objects out. The platforms test their trees against it with fixtures.
 */
import { LIMIT, clip, r4 } from './pin.js';

/** The screen step: whatever the device, the whole of it. */
export const SCREEN_STEP = Object.freeze({ kind: 'screen', type: 'screen', bounds: Object.freeze({ x: 0, y: 0, w: 1, h: 1 }) });

const areaOf = (b) => b.w * b.h;
const near = (a, b) => Math.abs(a - b) <= 0.1 * Math.max(a, b);

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
 * @returns {object[]} the steps, `{kind, type, text?, id?, component?, bounds}`, screen last
 */
export function buildLadder({ element, node, boundsOf, describe, component = null, max = LIMIT.scopes }) {
  const first = { kind: 'element', type: clip(element.type, 40) ?? 'element' };
  const said = clip(element.text, LIMIT.scopeText);
  if (said) first.text = said;
  const id = clip(element.id, 100);
  if (id) first.id = id;
  if (component) first.component = component;
  if (element.bounds) first.bounds = element.bounds;
  const steps = [first];

  for (let a = node?.parent, i = 0; a && i < 40; a = a.parent, i++) {
    const what = describe(a);
    if (!what) continue;
    const bounds = boundsOf(a);
    if (!bounds || !(bounds.w > 0) || !(bounds.h > 0)) continue;
    const area = areaOf(bounds);
    if (area >= 0.8) continue; // most of the screen: the screen step says the same
    const step = { kind: what.kind, type: clip(what.type, 40) ?? 'group' };
    const text = clip(what.text, LIMIT.scopeText);
    if (text) step.text = text;
    const stepId = clip(what.id, 100);
    if (stepId) step.id = stepId;
    if (what.component) step.component = clip(what.component, 100);
    step.bounds = { x: r4(bounds.x), y: r4(bounds.y), w: r4(bounds.w), h: r4(bounds.h) };

    const last = steps[steps.length - 1];
    if (last.bounds && near(area, areaOf(last.bounds))) {
      merge(last, step);
      continue;
    }
    // Widening never gets smaller. SwiftUI reports a section's identifier on a per-row box that can
    // be smaller than the control in it (measured: a 338×28 box around a 370×52 switch row).
    if (last.bounds && area < areaOf(last.bounds)) continue;
    if (steps.length >= max - 1) break;
    steps.push(step);
  }
  steps.push({ ...SCREEN_STEP, bounds: { ...SCREEN_STEP.bounds } });
  return steps;
}

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
