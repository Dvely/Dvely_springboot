/**
 * Pinning on a shared web page (§14.3, #682): what is at the viewer's point, which component drew
 * it, where that component lives in the project, and a crop of it.
 *
 * The page is read with one Runtime.evaluate of `inspectPoint` below. It only reads the DOM and the
 * framework's dev-mode bookkeeping (React fibers, Vue instances, Svelte metadata); it changes
 * nothing and calls nothing of the page's own.
 */
import { LIMIT, clip, r4, screenResult, screenScope, unresolved, withDeadline } from './pin.js';
import { jpegUnder } from './jpeg.js';
import { parsePng } from './png.js';
import { definitionLine, findSourceHints, toProjectPath } from './source-hints.js';

const EVALUATE_TIMEOUT_MS = 1500;
/** A whole-screen probe only asks the page where it is; past this the answer goes without it. */
const SCREEN_READ_MS = 1000;
const CROP_MARGIN = 16;
const CROP_MAX_PIXELS = 800;
const CROP_MAX_BYTES = 480 * 1024;
/** Around a point with no element: enough to recognise the place. */
const SPOT = { width: 240, height: 160 };

/**
 * Runs inside the page. Self-contained (it is sent as source text): no imports, no closures.
 *
 * @param {{x: number, y: number, limit: {text: number, selector: number, ancestors: number, components: number}}} args
 */
export function inspectPoint(args) {
  const L = args.limit;
  const W = window.innerWidth;
  const H = window.innerHeight;
  const px = args.x * W;
  const py = args.y * H;
  const fold = (v, n) => {
    const t = String(v == null ? '' : v).replace(/\s+/g, ' ').trim();
    return t ? Array.from(t).slice(0, n).join('') : null;
  };
  const view = {
    width: W,
    height: H,
    dpr: window.devicePixelRatio || 1,
    pageX: window.visualViewport ? window.visualViewport.pageLeft : window.scrollX,
    pageY: window.visualViewport ? window.visualViewport.pageTop : window.scrollY,
  };
  const screen = { label: location.pathname || '/', url: location.href.split('#')[0], title: fold(document.title, 200) };

  let hit = document.elementFromPoint(px, py);
  for (let i = 0; hit && hit.shadowRoot && i < 8; i++) {
    const inner = hit.shadowRoot.elementFromPoint(px, py);
    if (!inner || inner === hit) break;
    hit = inner;
  }
  if (!hit || hit === document.documentElement || hit === document.body) return { ok: false, reason: 'NO_ELEMENT', screen, view };

  const up = (e) => e.parentElement || (e.getRootNode && e.getRootNode().host) || null;
  const INTERACTIVE = /^(a|button|input|select|textarea|label|summary|option)$/;
  const ROLES = /^(button|link|checkbox|radio|switch|tab|menuitem|option|textbox|combobox|slider)$/;
  const interactive = (e) => INTERACTIVE.test(e.localName) || ROLES.test(e.getAttribute('role') || '');
  // A press on a button's label or icon means the button.
  let el = hit;
  for (let e = hit, i = 0; e && e.nodeType === 1 && i < 5; e = up(e), i++) {
    if (interactive(e)) {
      el = e;
      break;
    }
  }
  if (!interactive(el) && el.closest) {
    const svg = el.closest('svg');
    if (svg) el = svg;
  }

  const typeOf = (e) => fold(e.getAttribute('role'), 40) || e.localName;
  const textOf = (e) => {
    const aria = e.getAttribute('aria-label');
    if (aria) return fold(aria, L.text);
    const tag = e.localName;
    if (tag === 'input' || tag === 'textarea' || tag === 'select') {
      const type = (e.getAttribute('type') || '').toLowerCase();
      if (tag === 'input' && /^(button|submit|reset)$/.test(type)) return fold(e.value, L.text);
      // What someone typed is not sent (passwords, personal details); the field is named by its label.
      const label = e.labels && e.labels[0] ? e.labels[0].innerText : null;
      return fold(label || e.getAttribute('placeholder') || e.getAttribute('name') || e.getAttribute('title'), L.text);
    }
    if (tag === 'img') return fold(e.getAttribute('alt') || e.getAttribute('title'), L.text);
    const inner = typeof e.innerText === 'string' ? e.innerText : e.textContent;
    return fold(inner, L.text) || fold(e.getAttribute('title'), L.text);
  };
  const stableClass = (c) =>
    /^[A-Za-z][\w-]{0,40}$/.test(c) && !/\d{3,}/.test(c) && !/^(css|sc|jsx|svelte|emotion|chakra)-/i.test(c) && !/__[\w-]{5,}$/.test(c);
  const part = (e) => {
    if (e.id && /^[A-Za-z][\w-]{0,60}$/.test(e.id)) return '#' + e.id;
    return e.localName + Array.from(e.classList || []).filter(stableClass).slice(0, 2).map((c) => '.' + c).join('');
  };
  const selectorOf = (node) => {
    const parts = [];
    for (let e = node, i = 0; e && e.nodeType === 1 && e !== document.body && e !== document.documentElement && i < 4; e = e.parentElement, i++) {
      const p = part(e);
      parts.unshift(p);
      if (p[0] === '#') break;
    }
    return parts.join(' > ');
  };

  const rect = el.getBoundingClientRect();
  const left = Math.max(0, rect.left);
  const top = Math.max(0, rect.top);
  const right = Math.min(W, rect.right);
  const bottom = Math.min(H, rect.bottom);
  const visible = right > left && bottom > top;
  const element = {
    type: typeOf(el),
    text: textOf(el),
    id: fold(el.id, 100),
    selector: fold(selectorOf(el), L.selector),
  };
  if (visible) element.bounds = { x: left / W, y: top / H, w: (right - left) / W, h: (bottom - top) / H };

  const LANDMARK = /^(form|nav|header|footer|main|section|article|aside|dialog|ul|ol|table|fieldset)$/;
  const ancestors = [];
  for (let a = up(el), i = 0; a && a.nodeType === 1 && a !== document.body && ancestors.length < L.ancestors && i < 40; a = up(a), i++) {
    if (a.id || a.getAttribute('role') || LANDMARK.test(a.localName)) {
      ancestors.push({ type: typeOf(a), id: fold(a.id, 100), text: fold(a.getAttribute('aria-label'), 80) });
    }
  }

  const clean = (n) => (typeof n === 'string' && /^[+A-Za-z_$][\w$.+-]{0,80}$/.test(n) && !/^(Anonymous|_c\d*)$/.test(n) ? n : null);
  const baseName = (p) => String(p).split(/[\\/]/).pop().replace(/\.[^.]+$/, '');

  const fiberKey = (node) => Object.keys(node).find((k) => k.indexOf('__reactFiber$') === 0 || k.indexOf('__reactInternalInstance$') === 0);
  // A component's name from a fiber's type: a function or class, forwardRef (`render`), memo (`type`).
  const nameOf = (t, depth) => {
    if (!t || depth > 3) return null;
    if (typeof t === 'function') return clean(t.displayName || t.name);
    if (typeof t === 'object') {
      if (t._context || t._payload) return null; // context providers, lazy wrappers
      return clean(t.displayName) || nameOf(t.render, depth + 1) || nameOf(t.type, depth + 1);
    }
    return null;
  };

  // React: walk the fiber chain up from the element. A component's own file is where the JSX it
  // rendered was written — the source of a fiber it owns — not where it was used.
  const react = (node) => {
    const key = fiberKey(node);
    if (!key) return null;
    const sourceOf = (f) => {
      const s = f._debugSource;
      if (s && typeof s.fileName === 'string') return { path: s.fileName, line: s.lineNumber | 0 };
      const stack = f._debugStack && f._debugStack.stack; // React 19: where the JSX ran
      if (typeof stack === 'string') {
        const lines = stack.split('\n');
        for (let i = 0; i < lines.length; i++) {
          const m = /(https?:\/\/[^\s()]+?):(\d+):\d+\)?\s*$/.exec(lines[i]);
          if (!m || /\/node_modules\/|\/@vite\/|\/@react-refresh|\/@id\//.test(m[1])) continue;
          return { url: m[1], line: +m[2] };
        }
      }
      return null;
    };
    const owned = new Map();
    const found = [];
    for (let f = node[key], i = 0; f && i < 400 && found.length < L.components; f = f.return, i++) {
      const owner = f._debugOwner;
      if (owner && !owned.has(owner)) owned.set(owner, sourceOf(f));
      const t = f.type;
      if (t && (typeof t === 'function' || typeof t === 'object')) {
        const name = nameOf(t, 0);
        if (name && (!found.length || found[found.length - 1].name !== name)) found.push({ name, fiber: f });
      }
    }
    return found.length ? found.map((c) => ({ name: c.name, source: owned.get(c.fiber) || null })) : null;
  };

  const vue = (node) => {
    for (let e = node, i = 0; e && i < 40; e = e.parentElement, i++) {
      if (e.__vueParentComponent) {
        const out = [];
        for (let c = e.__vueParentComponent, j = 0; c && j < 60 && out.length < L.components; c = c.parent, j++) {
          const t = c.type || {};
          const file = typeof t.__file === 'string' ? t.__file : null;
          const name = clean(t.name || t.__name) || (file ? clean(baseName(file)) : null);
          if (name) out.push({ name, source: file ? { path: file } : null });
        }
        return out.length ? out : null;
      }
      if (e.__vue__) {
        const out = [];
        for (let vm = e.__vue__, j = 0; vm && j < 60 && out.length < L.components; vm = vm.$parent, j++) {
          const o = vm.$options || {};
          const file = typeof o.__file === 'string' ? o.__file : null;
          const name = clean(o.name || o._componentTag) || (file ? clean(baseName(file)) : null);
          if (name) out.push({ name, source: file ? { path: file } : null });
        }
        return out.length ? out : null;
      }
    }
    return null;
  };

  const svelte = (node) => {
    const out = [];
    const seen = {};
    for (let e = node, i = 0; e && i < 80 && out.length < L.components; e = e.parentElement, i++) {
      const loc = e.__svelte_meta && e.__svelte_meta.loc;
      if (loc && typeof loc.file === 'string' && !seen[loc.file]) {
        seen[loc.file] = true;
        out.push({ name: clean(baseName(loc.file)) || 'Component', source: { path: loc.file, line: loc.line > 0 ? loc.line : 0 } });
      }
    }
    return out.length ? out : null;
  };

  let components = null;
  let framework = null;
  const tries = [['react', react], ['vue', vue], ['svelte', svelte]];
  for (let i = 0; i < tries.length && !components; i++) {
    try {
      components = tries[i][1](el) || (el !== hit ? tries[i][1](hit) : null);
      if (components) framework = tries[i][0];
    } catch (e) {
      components = null;
    }
  }

  const out = {
    ok: true,
    screen,
    view,
    element,
    rect: visible ? { x: left, y: top, width: right - left, height: bottom - top } : null,
    ancestors,
    components: components || [],
    framework,
  };

  // ── scope ladder (§15.3) ─────────────────────────────────────────────────
  // Worked out only now that the element is settled, and on a budget: a huge tree must not push the
  // whole evaluate past its timeout and lose the element with it (§15.12). Whatever happens here,
  // the answer above stands.
  const clock = typeof performance === 'object' && performance && typeof performance.now === 'function' ? () => performance.now() : () => Date.now();
  const BUDGET_MS = 400;
  const GROUP = /^(form|nav|header|footer|main|section|article|aside|dialog|ul|ol|li|table|fieldset)$/;
  // Generated ids (React's `:r1:`, `ember1234`) change between builds and name nothing.
  const stableId = (id) => typeof id === 'string' && /^[A-Za-z][\w-]{0,60}$/.test(id) && !/\d{3,}/.test(id);
  const boxOf = (node) => {
    const r = node.getBoundingClientRect();
    const l = Math.max(0, r.left);
    const t = Math.max(0, r.top);
    const rr = Math.min(W, r.right);
    const b = Math.min(H, r.bottom);
    return rr > l && b > t ? { x: l / W, y: t / H, w: (rr - l) / W, h: (b - t) / H } : null;
  };
  const areaOf = (b) => b.w * b.h; // of the viewport, which is 1
  const near = (a, b) => Math.abs(a - b) <= 0.1 * Math.max(a, b);
  // A step's own words: its aria-label, or text written directly in it — not everything inside it,
  // which for a form or a section is the whole page. Never what someone typed into an editable area.
  const ownText = (e) => {
    if (e.isContentEditable) return null;
    const aria = e.getAttribute('aria-label');
    if (aria) return fold(aria, L.scopeText);
    let t = '';
    const kids = e.childNodes || [];
    for (let i = 0; i < kids.length && i < 50; i++) if (kids[i] && kids[i].nodeType === 3) t += ' ' + kids[i].nodeValue;
    return fold(t, L.scopeText);
  };
  // A node is a component's root when going up from its own fiber, components come before the next
  // host node: the innermost of them drew it. The step's bounds are this node's alone, so a
  // component that renders several roots side by side (a fragment) is approximated by its first.
  const reactRoot = (node) => {
    const key = fiberKey(node);
    const own = key ? node[key] : null;
    for (let f = own && own.return, i = 0; f && i < 50; f = f.return, i++) {
      if (typeof f.type === 'string' || f.tag === 3 || f.tag === 4) return null; // a host node, the root, a portal
      const t = f.type;
      if (t && (typeof t === 'function' || typeof t === 'object')) {
        const name = nameOf(t, 0);
        if (name) return name;
      }
    }
    return null;
  };
  // Vue 3: every element knows the component that rendered it; the node is that component's root
  // when its rendered tree starts here. (Svelte keeps no such link; it gets no component steps.)
  const vueRoot = (node) => {
    const c = node.__vueParentComponent;
    if (!c || !c.subTree || c.subTree.el !== node) return null;
    const t = c.type || {};
    const file = typeof t.__file === 'string' ? t.__file : null;
    return clean(t.name || t.__name) || (file ? clean(baseName(file)) : null);
  };
  const componentAt = (node) => {
    try {
      return reactRoot(node) || vueRoot(node);
    } catch (e) {
      return null;
    }
  };
  // Two nodes with nearly the same box are one step, named by the most telling of them: a
  // component name over a stable id over a bare tag. The step keeps the inner node's type, so a
  // `<form id="profile">` that `ProfileForm` draws reads "form · ProfileForm". The innermost
  // component wins when several draw the same box.
  const merge = (kept, s) => {
    if (s.component && !kept.component) {
      kept.component = s.component;
      if (kept.kind === 'group') kept.kind = 'component';
    }
    // The element step says what `element` says; only a component name is added to it.
    if (kept.kind === 'element') return;
    if (stableId(s.id) && !stableId(kept.id)) {
      kept.id = s.id;
      if (s.selector) kept.selector = s.selector;
    }
    if (s.text && !kept.text) kept.text = s.text;
  };

  try {
    const start = clock();
    const first = { kind: 'element', type: element.type };
    const said = fold(element.text, L.scopeText);
    if (said) first.text = said;
    if (element.id) first.id = element.id;
    if (element.selector) first.selector = element.selector;
    const own = componentAt(el);
    if (own) first.component = own;
    if (element.bounds) first.bounds = element.bounds;
    const steps = [first];
    for (let a = up(el), i = 0; a && a.nodeType === 1 && a !== document.body && a !== document.documentElement && i < 40; a = up(a), i++) {
      if (clock() - start > BUDGET_MS) break;
      const name = componentAt(a);
      if (!name && !GROUP.test(a.localName) && !a.getAttribute('role') && !stableId(a.id)) continue;
      const bounds = boxOf(a);
      if (!bounds) continue;
      const area = areaOf(bounds);
      // Most of the screen already: the screen step says the same.
      if (area >= 0.8) continue;
      const step = { kind: name ? 'component' : 'group', type: typeOf(a) };
      const text = ownText(a);
      if (text) step.text = text;
      const id = fold(a.id, 100);
      if (id) step.id = id;
      const selector = fold(selectorOf(a), L.selector);
      if (selector) step.selector = selector;
      if (name) step.component = name;
      step.bounds = bounds;
      // Nearly the same box as the step below it: one step, so widening never stops twice in place.
      const last = steps[steps.length - 1];
      if (last.bounds && near(area, areaOf(last.bounds))) {
        merge(last, step);
        continue;
      }
      if (steps.length >= L.scopes - 1) break;
      steps.push(step);
    }
    steps.push({ kind: 'screen', type: 'screen', bounds: { x: 0, y: 0, w: 1, h: 1 } });
    out.scopes = steps;
    out.defaultScope = 0;
  } catch (e) {
    // The device side falls back to the element and the screen.
  }
  return out;
}

/** Runs inside the page for a whole-screen probe (§15.6): where the page is, nothing else. */
export function readScreen() {
  const title = String(document.title == null ? '' : document.title).replace(/\s+/g, ' ').trim();
  return { label: location.pathname || '/', url: location.href.split('#')[0], title: title ? Array.from(title).slice(0, 200).join('') : null };
}

/**
 * @param {object} o
 * @param {{send: Function}} o.page
 * @param {{probeId: string, x: number, y: number}|{probeId: string, screen: true}} o.probe a point, or
 *   the whole screen (resolveWebScreen)
 * @param {string|null} o.project the folder to look in for source candidates; null looks nowhere
 * @param {string|null} o.origin the shared origin
 * @param {Buffer|null} o.screen the latest screencast frame (what the viewer saw)
 * @returns {Promise<{result: object, crop: Buffer|null, screen: Buffer|null}>}
 */
export async function resolveWebPin({ page, probe, project, origin, screen = null, findHints = findSourceHints }) {
  if (probe.screen) return resolveWebScreen({ page, probe, screen });
  let raw = null;
  try {
    const args = JSON.stringify({ x: probe.x, y: probe.y, limit: LIMIT });
    const reply = await page.send('Runtime.evaluate', {
      expression: `(${inspectPoint.toString()})(${args})`,
      returnByValue: true,
      silent: true,
      timeout: EVALUATE_TIMEOUT_MS,
    });
    raw = reply?.exceptionDetails ? null : reply?.result?.value;
  } catch {
    raw = null;
  }
  if (!raw || typeof raw !== 'object') return { result: unresolved(probe, 'UNSUPPORTED'), crop: null, screen };

  const pageScreen = cleanScreen(raw.screen);
  const view = cleanView(raw.view);
  if (!raw.ok || !raw.element) {
    const spot = view ? spotAround(probe, view) : null;
    return { result: unresolved(probe, 'NO_ELEMENT', pageScreen), crop: spot ? await crop(page, spot, view) : null, screen };
  }

  const element = cleanElement(raw.element);
  const components = [];
  for (const c of Array.isArray(raw.components) ? raw.components.slice(0, LIMIT.components) : []) {
    const name = clip(c?.name, 100);
    if (!name) continue;
    const file = c.source ? toProjectPath(c.source, project, origin) : null;
    // A file path from React 18 (`_debugSource`) or Svelte carries the line on disk. A module URL
    // from a React 19 stack carries a line of the transformed module: look the definition up instead.
    let line = !c.source?.url && Number.isSafeInteger(c.source?.line) && c.source.line > 0 ? c.source.line : null;
    if (file && !line) line = await definitionLine(project, file, name);
    components.push(file ? { name, file, ...(line ? { line } : {}) } : { name });
  }
  const sourceHints = await findHints({ project, components, element }).catch(() => []);
  const rect = cleanRect(raw.rect);
  const result = {
    probeId: probe.probeId,
    ok: true,
    screen: pageScreen,
    point: { x: r4(probe.x), y: r4(probe.y) },
    element,
    ancestors: (Array.isArray(raw.ancestors) ? raw.ancestors : []).slice(0, LIMIT.ancestors).map(cleanAncestor).filter(Boolean),
    components,
    sourceHints,
    scopes: cleanScopes(raw.scopes, element),
    defaultScope: 0,
  };
  const area = rect && view ? padded(rect, view) : view ? spotAround(probe, view) : null;
  return { result, crop: area ? await crop(page, area, view) : null, screen };
}

/**
 * The whole screen (§15.6): the frame the viewer saw and where the page is. No element and no crop.
 * The page is asked with a short evaluate of its own; a page too busy to say goes without `screen`.
 *
 * @returns {Promise<{result: object, crop: null, screen: Buffer|null}>}
 */
export async function resolveWebScreen({ page, probe, screen = null }) {
  let raw = null;
  try {
    const reply = await withDeadline(
      page.send('Runtime.evaluate', {
        expression: `(${readScreen.toString()})()`,
        returnByValue: true,
        silent: true,
        timeout: SCREEN_READ_MS,
      }),
      SCREEN_READ_MS
    );
    raw = reply?.exceptionDetails ? null : reply?.result?.value;
  } catch {
    raw = null;
  }
  return { result: screenResult(probe, cleanScreen(raw)), crop: null, screen };
}

const str = (v, max) => (typeof v === 'string' ? clip(v, max) : null);

const STEP_KINDS = new Set(['group', 'component']);

/**
 * The ladder as the page reported it, checked like everything else from the page: known kinds
 * only, words and names cut to size, bounds in 0..1 with something visible. The element step is
 * rebuilt from the cleaned `element` so the two always agree; only the component name comes from
 * the page's step. Whatever the page sent, the ladder starts at the element and ends at the screen.
 */
function cleanScopes(raw, element) {
  const list = Array.isArray(raw) ? raw : [];
  const head = list[0] && typeof list[0] === 'object' && list[0].kind === 'element' ? list[0] : {};
  const first = { kind: 'element' };
  if (element.type) first.type = element.type;
  const said = clip(element.text, LIMIT.scopeText);
  if (said) first.text = said;
  if (element.id) first.id = element.id;
  if (element.selector) first.selector = element.selector;
  const component = str(head.component, 100);
  if (component) first.component = component;
  if (element.bounds) first.bounds = element.bounds;
  const steps = [first];
  for (const s of list.slice(1)) {
    if (steps.length >= LIMIT.scopes - 1) break;
    if (!s || typeof s !== 'object' || !STEP_KINDS.has(s.kind)) continue;
    const bounds = cleanBounds(s.bounds);
    if (!bounds) continue;
    const step = { kind: s.kind, type: str(s.type, 40) ?? 'element' };
    const text = str(s.text, LIMIT.scopeText);
    const id = str(s.id, 100);
    const selector = str(s.selector, LIMIT.selector);
    const name = str(s.component, 100);
    if (text) step.text = text;
    if (id) step.id = id;
    if (selector) step.selector = selector;
    if (name) step.component = name;
    else if (s.kind === 'component') continue; // a component step without its name says nothing
    step.bounds = bounds;
    steps.push(step);
  }
  steps.push(screenScope());
  return steps;
}

function cleanBounds(b) {
  if (!b || typeof b !== 'object' || ![b.x, b.y, b.w, b.h].every((v) => typeof v === 'number' && Number.isFinite(v))) return null;
  const out = { x: r4(b.x), y: r4(b.y), w: r4(b.w), h: r4(b.h) };
  return out.w > 0 && out.h > 0 ? out : null;
}

function cleanScreen(s) {
  if (!s || typeof s !== 'object') return undefined;
  const out = {};
  const label = str(s.label, LIMIT.label);
  const url = typeof s.url === 'string' ? s.url.slice(0, 2048) : null;
  const title = str(s.title, LIMIT.text);
  if (label) out.label = label;
  if (url) out.url = url;
  if (title) out.title = title;
  return Object.keys(out).length ? out : undefined;
}

function cleanView(v) {
  if (!v || typeof v !== 'object') return null;
  const n = (x) => (typeof x === 'number' && Number.isFinite(x) ? x : null);
  const width = n(v.width);
  const height = n(v.height);
  if (!width || !height || width <= 0 || height <= 0) return null;
  return { width, height, dpr: n(v.dpr) > 0 ? v.dpr : 1, pageX: n(v.pageX) ?? 0, pageY: n(v.pageY) ?? 0 };
}

function cleanElement(e) {
  const out = {};
  const type = str(e.type, 40);
  const text = str(e.text, LIMIT.text);
  const id = str(e.id, 100);
  const selector = str(e.selector, LIMIT.selector);
  if (type) out.type = type;
  if (text) out.text = text;
  if (id) out.id = id;
  if (selector) out.selector = selector;
  const b = e.bounds;
  if (b && [b.x, b.y, b.w, b.h].every((v) => typeof v === 'number' && Number.isFinite(v))) {
    out.bounds = { x: r4(b.x), y: r4(b.y), w: r4(b.w), h: r4(b.h) };
  }
  return out;
}

function cleanAncestor(a) {
  if (!a || typeof a !== 'object') return null;
  const out = {};
  const type = str(a.type, 40);
  const id = str(a.id, 100);
  const text = str(a.text, 80);
  if (type) out.type = type;
  if (id) out.id = id;
  if (text) out.text = text;
  return Object.keys(out).length ? out : null;
}

function cleanRect(r) {
  if (!r || typeof r !== 'object') return null;
  const vals = [r.x, r.y, r.width, r.height];
  if (!vals.every((v) => typeof v === 'number' && Number.isFinite(v)) || r.width <= 0 || r.height <= 0) return null;
  return { x: r.x, y: r.y, width: r.width, height: r.height };
}

/** The element with a margin, kept on screen. CSS pixels relative to the viewport. */
export function padded(rect, view) {
  const x = Math.max(0, rect.x - CROP_MARGIN);
  const y = Math.max(0, rect.y - CROP_MARGIN);
  const right = Math.min(view.width, rect.x + rect.width + CROP_MARGIN);
  const bottom = Math.min(view.height, rect.y + rect.height + CROP_MARGIN);
  return { x, y, width: right - x, height: bottom - y };
}

/** A fixed-size area centred on the point, kept on screen. */
export function spotAround(probe, view) {
  const width = Math.min(SPOT.width, view.width);
  const height = Math.min(SPOT.height, view.height);
  const x = Math.min(Math.max(probe.x * view.width - width / 2, 0), view.width - width);
  const y = Math.min(Math.max(probe.y * view.height - height / 2, 0), view.height - height);
  return { x, y, width, height };
}

/**
 * A JPEG of one area of the page, cut by us from one full-viewport screenshot.
 *
 * Not CDP's `clip`: a clipped capture changes the page's device metrics while it runs, and the
 * screencast sends that zoomed frame to every viewer — on a still page it stayed on their screen
 * (measured 2026-11-17). A plain capture leaves the view as it is. The screenshot is in device
 * pixels; the area is in CSS pixels of the viewport.
 */
async function crop(page, area, view) {
  let shot = null;
  try {
    const reply = await page.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
    shot = typeof reply?.data === 'string' ? parsePng(Buffer.from(reply.data, 'base64')) : null;
  } catch {
    return null;
  }
  if (!shot) return null;
  const k = shot.width / view.width;
  return jpegUnder(
    shot,
    { x: area.x * k, y: area.y * k, width: area.width * k, height: area.height * k },
    { maxSide: CROP_MAX_PIXELS, maxBytes: CROP_MAX_BYTES, quality: 80 }
  );
}
