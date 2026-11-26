/**
 * Pinning on a shared web page (§14.3, #682): what is at the viewer's point, which component drew
 * it, where that component lives in the project, and a crop of it. A dragged rectangle (§15.4) is
 * read the same way at its centre, plus what is inside it and what holds it.
 *
 * The page is read with one Runtime.evaluate of `inspectPoint` below. It only reads the DOM and the
 * framework's dev-mode bookkeeping (React fibers, Vue instances, Svelte metadata); it changes
 * nothing and calls nothing of the page's own.
 */
import { LIMIT, clip, probePoint, r4, screenResult, screenScope, unresolved, withDeadline } from './pin.js';
import { jpegUnder } from './jpeg.js';
import { parsePng } from './png.js';
import { NEAR_DIRS, scopeIdsOf } from './scope.js';
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
 * A point (`x`, `y`) is read as §14.3 says, then its scope ladder (§15.3). A dragged rectangle
 * (`rect`, §15.4) is read at its centre exactly as a point there would be, then as an area: the named
 * elements inside it, the containers holding it, and around an empty one the nearest named elements.
 *
 * @param {{x?: number, y?: number, rect?: {x: number, y: number, w: number, h: number},
 *   limit: {text: number, selector: number, ancestors: number, components: number}}} args
 */
export function inspectPoint(args) {
  const L = args.limit;
  const W = window.innerWidth;
  const H = window.innerHeight;
  const drawn = args.rect && typeof args.rect === 'object' ? args.rect : null;
  const px = (drawn ? drawn.x + drawn.w / 2 : args.x) * W;
  const py = (drawn ? drawn.y + drawn.h / 2 : args.y) * H;
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
  // Whatever is worked out after the element — the ladder, an area's contents, the neighbours of an
  // empty spot — runs on this budget: a huge tree must not push the whole evaluate past its timeout
  // and lose the element with it (§15.12).
  const clock = typeof performance === 'object' && performance && typeof performance.now === 'function' ? () => performance.now() : () => Date.now();
  const BUDGET_MS = 400;
  const MAX_NODES = 2000;

  const up = (e) => e.parentElement || (e.getRootNode && e.getRootNode().host) || null;
  const INTERACTIVE = /^(a|button|input|select|textarea|label|summary|option)$/;
  const ROLES = /^(button|link|checkbox|radio|switch|tab|menuitem|option|textbox|combobox|slider)$/;
  const interactive = (e) => INTERACTIVE.test(e.localName) || ROLES.test(e.getAttribute('role') || '');
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
    // An editable area (and anything inside one) holds what someone typed, like a field: name it, not its words.
    if (e.isContentEditable) {
      return fold(e.getAttribute('aria-placeholder') || e.getAttribute('placeholder') || e.getAttribute('data-placeholder') || e.getAttribute('title'), L.text);
    }
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

  // ── scope ladder (§15.3) ───────────────────────────────────────────────────
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
  const alike = (a, b) => Math.abs(a - b) <= 0.1 * Math.max(a, b);
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
  const meaningful = (a) => Boolean(componentAt(a) || GROUP.test(a.localName) || a.getAttribute('role') || stableId(a.id));
  // The node each step stands for, to count its look-alikes (§15.3 peers) once the ladder is built,
  // and the same-size nodes merged into it: in `li > a` the links have no look-alikes, their items do.
  const nodeOf = new Map();
  const mergedOf = new Map();
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
  // Adds the steps from `from` outward. Nothing merges into a dragged region's own step, nor is
  // measured against it; above it, as in scope.js, a container never narrows the ladder.
  const climb = (steps, from, start, region) => {
    for (let a = from, i = 0; a && a.nodeType === 1 && a !== document.body && a !== document.documentElement && i < 40; a = up(a), i++) {
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
      const measured = last.bounds && last.kind !== 'region';
      if (measured && alike(area, areaOf(last.bounds))) {
        // The step's look-alikes are counted on the node its component drew.
        if (name && !last.component) nodeOf.set(last, a);
        if (!mergedOf.has(last)) mergedOf.set(last, []);
        mergedOf.get(last).push(a);
        merge(last, step);
        continue;
      }
      if (region && measured && area < areaOf(last.bounds)) continue;
      if (steps.length >= L.scopes - 1) break;
      nodeOf.set(step, a);
      steps.push(step);
    }
  };
  const screenStep = () => ({ kind: 'screen', type: 'screen', bounds: { x: 0, y: 0, w: 1, h: 1 } });

  // ── the same thing, all of them (§15.3, case 3) ─────────────────────────────
  // A sibling looks the same when the same component drew it, or — without one — when it has the same
  // tag and the same stable classes. A bare `div` looks like every other bare `div`: that is no shape.
  const LISTY = /^(li|tr|article|option|dt|dd|figure)$/;
  const shapeOf = (e) => {
    const classes = Array.from(e.classList || []).filter(stableClass).sort().slice(0, 3);
    return classes.length || LISTY.test(e.localName) ? e.localName + '.' + classes.join('.') : null;
  };
  const peersOf = (node, component) => {
    const parent = node && node.parentElement;
    if (!parent) return null;
    const shape = component ? null : shapeOf(node);
    if (!component && !shape) return null;
    const kids = parent.children || [];
    let count = 0;
    const bounds = [];
    for (let i = 0; i < kids.length && i < 500; i++) {
      const k = kids[i];
      if (!k || k.nodeType !== 1) continue;
      if (component ? componentAt(k) !== component : shapeOf(k) !== shape) continue;
      count++;
      const b = bounds.length < 20 ? boxOf(k) : null;
      if (b) bounds.push(b);
    }
    return count >= 2 ? { count, bounds } : null;
  };
  const addPeers = (steps, start) => {
    for (let i = 0; i < steps.length; i++) {
      if (clock() - start > BUDGET_MS) break;
      const s = steps[i];
      if (s.kind !== 'element' && s.kind !== 'group' && s.kind !== 'component') continue;
      let peers = peersOf(nodeOf.get(s), s.component || null);
      const outer = mergedOf.get(s) || [];
      for (let j = 0; !peers && j < outer.length; j++) peers = peersOf(outer[j], null);
      if (peers) s.peers = peers;
    }
  };

  // ── a sentence under the point (§15.7, case 8) ──────────────────────────────
  // From the text node under the point only, cut at sentence ends. Never inside a field someone types
  // into. A caret snaps to the nearest text even off it, so only a point on the sentence's own lines counts.
  const SENTENCE_END = /[.!?。！？\n]/;
  const sentenceAt = (x, y, within) => {
    let node = null;
    let offset = 0;
    if (typeof document.caretPositionFromPoint === 'function') {
      const p = document.caretPositionFromPoint(x, y);
      if (p) {
        node = p.offsetNode;
        offset = p.offset;
      }
    } else if (typeof document.caretRangeFromPoint === 'function') {
      const r = document.caretRangeFromPoint(x, y);
      if (r) {
        node = r.startContainer;
        offset = r.startOffset;
      }
    }
    if (!node || node.nodeType !== 3 || !within || !within.contains || !within.contains(node)) return null;
    const host = node.parentElement;
    if (!host || host.isContentEditable || (host.closest && host.closest('input,textarea,select,[contenteditable]'))) return null;
    const data = String(node.data || '');
    let s = Math.min(Math.max(0, offset), data.length);
    let e = s;
    while (s > 0 && !SENTENCE_END.test(data[s - 1])) s--;
    while (e < data.length && !SENTENCE_END.test(data[e])) e++;
    if (e < data.length && data[e] !== '\n') e++;
    while (s < e && /\s/.test(data[s])) s++;
    while (e > s && /\s/.test(data[e - 1])) e--;
    if (e <= s) return null;
    const range = document.createRange();
    range.setStart(node, s);
    range.setEnd(node, e);
    const lines = range.getClientRects();
    let on = false;
    for (let i = 0; i < lines.length && !on; i++) {
      const r = lines[i];
      on = x >= r.left && x <= r.right && y >= r.top && y <= r.bottom;
    }
    if (!on) return null;
    const r = range.getBoundingClientRect();
    const l = Math.max(0, r.left);
    const t = Math.max(0, r.top);
    const rr = Math.min(W, r.right);
    const b = Math.min(H, r.bottom);
    const text = fold(data.slice(s, e), L.text);
    return text && rr > l && b > t ? { kind: 'text', type: 'text', text, bounds: { x: l / W, y: t / H, w: (rr - l) / W, h: (b - t) / H } } : null;
  };

  // ── what is in an area, and around an empty spot (§15.4) ─────────────────────
  // Boxes here are CSS pixels of the viewport, `{x, y, w, h}`.
  const SKIP = /^(script|style|template|noscript|iframe|object|embed)$/;
  const rawOf = (e) => {
    const r = e.getBoundingClientRect();
    return { x: r.left, y: r.top, w: r.right - r.left, h: r.bottom - r.top };
  };
  const onScreen = (b) => {
    const l = Math.max(0, b.x);
    const t = Math.max(0, b.y);
    const r = Math.min(W, b.x + b.w);
    const bt = Math.min(H, b.y + b.h);
    return r > l && bt > t ? { x: l, y: t, w: r - l, h: bt - t } : null;
  };
  const ratioOf = (b) => ({ x: b.x / W, y: b.y / H, w: b.w / W, h: b.h / H });
  const overlap = (a, b) => {
    const w = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x);
    const h = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y);
    return w > 0 && h > 0 ? w * h : 0;
  };
  // Whether [a0, a1] overlaps [b0, b1]; a zero-width [b0, b1] (a point's) must lie within [a0, a1].
  const spans = (a0, a1, b0, b1) => (b1 > b0 ? a0 < b1 && a1 > b0 : a0 <= b0 && b0 <= a1);
  // What a person would name when describing an area: something to press or type into, words of its
  // own, a described picture. A box of a pixel or two is screen-reader text; one of most of the
  // screen is the page itself. 'interactive' speaks for everything inside it (a button's label).
  const kindOf = (e, box) => {
    if (box.w < 2 || box.h < 2 || box.w * box.h >= 0.8 * W * H) return null;
    if (interactive(e) || e.isContentEditable) return 'interactive';
    const tag = e.localName;
    if (tag === 'img' || tag === 'svg' || e.getAttribute('role') === 'img') return e.getAttribute('alt') || e.getAttribute('aria-label') ? 'plain' : null;
    const kids = e.childNodes || [];
    for (let i = 0; i < kids.length && i < 50; i++) if (kids[i] && kids[i].nodeType === 3 && /\S/.test(kids[i].nodeValue || '')) return 'plain';
    return null;
  };
  const shown = (e) => typeof e.checkVisibility !== 'function' || e.checkVisibility({ opacityProperty: true, visibilityProperty: true });
  // Whether an element found at `kind` stays, given what its nearest kept ancestor was: inside
  // something to press it is part of it; inside words of their own, only something to press stands
  // apart (the outer words already include the inner ones: `<p>… <em>무료</em></p>`).
  const stays = (state, kind) => state !== 'interactive' && (state !== 'plain' || kind === 'interactive');
  // Elements in tree order from <body>, open shadow roots included, looking inside only those whose
  // box passes `look`. One with no box (`display: contents`, a collapsed wrapper) is looked inside,
  // since what is in it may still show. `visit(e, box on screen or null, state)` returns the state
  // for its children. Stops at 2,000 elements or when the time is up, and then says false.
  const walk = (look, visit, start) => {
    const stack = [];
    const push = (list, state) => {
      if (!list) return;
      for (let i = list.length - 1; i >= 0; i--) stack.push([list[i], state]);
    };
    push(document.body && document.body.children, null);
    for (let n = 0; stack.length; n++) {
      if (n >= MAX_NODES || clock() - start > BUDGET_MS) return false;
      const [e, state] = stack.pop();
      if (!e || e.nodeType !== 1 || SKIP.test(e.localName)) continue;
      const raw = rawOf(e);
      if (raw.w > 0 && raw.h > 0 && !look(raw)) continue;
      const next = visit(e, onScreen(raw), state);
      // A picture's insides and an editor's contents (what someone typed) are not read.
      if (e.localName === 'svg' || e.isContentEditable) continue;
      if (e.shadowRoot) push(e.shadowRoot.children, next);
      push(e.children, next);
    }
    return true;
  };
  // Top to bottom by row, then left to right; boxes whose vertical centres are within half the
  // smaller height share a row.
  const readingOrder = (list) => {
    const cy = (b) => b.y + b.h / 2;
    const sorted = list.slice().sort((a, b) => cy(a.box) - cy(b.box));
    const rows = [];
    for (let i = 0; i < sorted.length; i++) {
      const m = sorted[i];
      const row = rows[rows.length - 1];
      if (row && row.some((o) => Math.abs(cy(o.box) - cy(m.box)) <= Math.min(o.box.h, m.box.h) / 2)) row.push(m);
      else rows.push([m]);
    }
    const out = [];
    for (let i = 0; i < rows.length; i++) out.push(...rows[i].sort((a, b) => a.box.x - b.box.x));
    return out;
  };
  const itemOf = (m) => {
    const e = m.e;
    const item = { type: typeOf(e) };
    // An editor is named by its label only: its words are what someone typed.
    const text = fold(e.isContentEditable ? e.getAttribute('aria-label') : textOf(e), L.memberText);
    if (text) item.text = text;
    const id = fold(e.id, 100);
    if (id) item.id = id;
    item.bounds = ratioOf(m.box);
    return item;
  };
  // The nearest named element on each side of `T` (zero-size for a point): entirely above, below,
  // left or right of it, and across its span on the other axis.
  const nearOf = (T, start) => {
    const best = {};
    const cross = (raw) => onScreen(raw) && (spans(raw.x, raw.x + raw.w, T.x, T.x + T.w) || spans(raw.y, raw.y + raw.h, T.y, T.y + T.h));
    walk(cross, (e, box, state) => {
      const kind = box ? kindOf(e, box) : null;
      if (!kind || !shown(e) || !stays(state, kind)) return state;
      const xs = spans(box.x, box.x + box.w, T.x, T.x + T.w);
      const ys = spans(box.y, box.y + box.h, T.y, T.y + T.h);
      const sides = [];
      if (xs && box.y + box.h <= T.y) sides.push(['up', (T.y - box.y - box.h) / H]);
      if (xs && box.y >= T.y + T.h) sides.push(['down', (box.y - T.y - T.h) / H]);
      if (ys && box.x + box.w <= T.x) sides.push(['left', (T.x - box.x - box.w) / W]);
      if (ys && box.x >= T.x + T.w) sides.push(['right', (box.x - T.x - T.w) / W]);
      for (let i = 0; i < sides.length; i++) {
        const dir = sides[i][0];
        const gap = sides[i][1];
        const cur = best[dir];
        if (!cur || gap < cur.gap || (gap === cur.gap && box.w * box.h < cur.box.w * cur.box.h)) best[dir] = { e, box, gap };
      }
      return kind;
    }, start);
    const out = [];
    const dirs = ['up', 'down', 'left', 'right'];
    for (let i = 0; i < dirs.length && out.length < L.near; i++) {
      const b = best[dirs[i]];
      if (b) out.push(Object.assign({ dir: dirs[i] }, itemOf(b), { distance: b.gap }));
    }
    return out;
  };

  let hit = document.elementFromPoint(px, py);
  for (let i = 0; hit && hit.shadowRoot && i < 8; i++) {
    const inner = hit.shadowRoot.elementFromPoint(px, py);
    if (!inner || inner === hit) break;
    hit = inner;
  }
  // A frame that covers most of the screen and says nothing itself (the app's root, a page wrapper)
  // is the background, as on Android and iOS: the spot is empty (§15.4). Without this a tap between
  // things named the whole app, with every word on the page as its text.
  const backdrop = (e) => {
    if (!e || e.nodeType !== 1 || interactive(e) || e.getAttribute('aria-label')) return false;
    const kids = e.childNodes || [];
    for (let i = 0; i < kids.length && i < 50; i++) if (kids[i] && kids[i].nodeType === 3 && String(kids[i].nodeValue).trim()) return false;
    const r = e.getBoundingClientRect();
    const w = Math.min(W, r.right) - Math.max(0, r.left);
    const h = Math.min(H, r.bottom) - Math.max(0, r.top);
    return w > 0 && h > 0 && (w * h) / (W * H) >= 0.8;
  };
  const none = !hit || hit === document.documentElement || hit === document.body || backdrop(hit);
  if (none && !drawn) {
    // An empty spot: what is around it says where it is (§15.4).
    const out = { ok: false, reason: 'NO_ELEMENT', screen, view };
    try {
      out.near = nearOf({ x: px, y: py, w: 0, h: 0 }, clock());
    } catch (e) {
      // The point alone, as before.
    }
    return out;
  }

  const out = { ok: true, screen, view };
  let el = null;
  if (!none) {
    // A press on a button's label or icon means the button.
    el = hit;
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
    out.element = element;
    out.rect = visible ? { x: left, y: top, width: right - left, height: bottom - top } : null;
    out.ancestors = ancestors;
    out.components = components || [];
    out.framework = framework;
  } else {
    out.ancestors = [];
    out.components = [];
    out.framework = null;
  }

  if (drawn) {
    // ── a dragged area (§15.4) ──────────────────────────────────────────────
    // Each part on its own: a failure in one leaves the others, and the element above, standing.
    const R = { x: drawn.x * W, y: drawn.y * H, w: drawn.w * W, h: drawn.h * H };
    const start = clock();
    try {
      // The smallest container holding 90% of the area. Such a box always holds the area's centre,
      // so it is the centre element or one of its ancestors: the first of those, going out, that is a
      // step and holds that much.
      const steps = [{ kind: 'region', type: 'region', bounds: ratioOf(R) }];
      for (let a = none ? null : hit, i = 0; a && a.nodeType === 1 && a !== document.body && a !== document.documentElement && i < 40; a = up(a), i++) {
        if (clock() - start > BUDGET_MS) break;
        if (meaningful(a) && overlap(rawOf(a), R) >= 0.9 * R.w * R.h) {
          climb(steps, a, start, true);
          break;
        }
      }
      addPeers(steps, start);
      steps.push(screenStep());
      out.scopes = steps;
      out.defaultScope = 0;
    } catch (e) {
      // The device side falls back to the region and the screen.
    }
    let members = null;
    let whole = false;
    try {
      const found = [];
      whole = walk(
        (raw) => overlap(raw, R) > 0,
        (e, box, state) => {
          const kind = box ? kindOf(e, box) : null;
          if (!kind || overlap(box, R) < 0.6 * box.w * box.h || !shown(e) || !stays(state, kind)) return state;
          found.push({ e, box });
          return kind;
        },
        start
      );
      members = readingOrder(found).slice(0, L.members).map(itemOf);
    } catch (e) {
      members = null;
    }
    if (members) {
      out.members = members;
      // Cut short, an area with nothing listed is not known to be empty.
      if (!whole) out.cut = true;
      else if (!members.length) {
        try {
          out.near = nearOf(R, start);
        } catch (e) {
          // Empty, with nothing said around it.
        }
      }
    }
    return out;
  }

  // ── scope ladder (§15.3) ───────────────────────────────────────────────────
  // Worked out only now that the element is settled. Whatever happens here, the answer above stands.
  try {
    const start = clock();
    const element = out.element;
    const first = { kind: 'element', type: element.type };
    const said = fold(element.text, L.scopeText);
    if (said) first.text = said;
    if (element.id) first.id = element.id;
    if (element.selector) first.selector = element.selector;
    const own = componentAt(el);
    if (own) first.component = own;
    if (element.bounds) first.bounds = element.bounds;
    const steps = [first];
    nodeOf.set(first, el);
    climb(steps, up(el), start, false);
    addPeers(steps, start);
    steps.push(screenStep());
    // §15.7: the sentence under the point is one step narrower than the element. The element stays the
    // default; the ladder keeps its length, giving up its widest container step for it.
    let sentence = null;
    try {
      sentence = sentenceAt(px, py, el);
    } catch (e) {
      sentence = null;
    }
    if (sentence) {
      if (steps.length >= L.scopes) steps.splice(steps.length - 2, 1);
      out.scopes = [sentence].concat(steps);
      out.defaultScope = 1;
    } else {
      out.scopes = steps;
      out.defaultScope = 0;
    }
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
 * @param {{probeId: string, x: number, y: number}|{probeId: string, screen: true}|
 *   {probeId: string, rect: object}} o.probe a point, the whole screen (resolveWebScreen) or a dragged
 *   rectangle (§15.4)
 * @param {string|null} o.project the folder to look in for source candidates; null looks nowhere
 * @param {string|null} o.origin the shared origin
 * @param {Buffer|null} o.screen the latest screencast frame (what the viewer saw)
 * @returns {Promise<{result: object, crop: Buffer|null, screen: Buffer|null}>}
 */
export async function resolveWebPin({ page, probe, project, origin, screen = null, findHints = findSourceHints }) {
  if (probe.screen) return resolveWebScreen({ page, probe, screen });
  const drawn = probe.rect ?? null;
  let raw = null;
  try {
    const args = JSON.stringify(drawn ? { rect: drawn, limit: LIMIT } : { x: probe.x, y: probe.y, limit: LIMIT });
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
  // A rectangle is cut as the person drew it, with the margin an element gets (§15.4).
  const drawnArea = drawn && view ? padded({ x: drawn.x * view.width, y: drawn.y * view.height, width: drawn.w * view.width, height: drawn.h * view.height }, view) : null;
  if (!drawn && (!raw.ok || !raw.element || typeof raw.element !== 'object')) {
    const spot = view ? spotAround(probe, view) : null;
    const result = unresolved(probe, 'NO_ELEMENT', pageScreen);
    // What is around an empty spot says where it is (§15.4).
    const near = cleanNear(raw.near);
    if (near) result.near = near;
    return { result, crop: spot ? await crop(page, spot, view) : null, screen };
  }
  if (drawn && !raw.ok) {
    return { result: unresolved(probe, 'NO_ELEMENT', pageScreen), crop: drawnArea ? await crop(page, drawnArea, view) : null, screen };
  }

  // A rectangle's centre may be the page background: then there is no element, only the area.
  const element = raw.element && typeof raw.element === 'object' ? cleanElement(raw.element) : null;
  const components = [];
  for (const c of element && Array.isArray(raw.components) ? raw.components.slice(0, LIMIT.components) : []) {
    const name = clip(c?.name, 100);
    if (!name) continue;
    const file = c.source ? toProjectPath(c.source, project, origin) : null;
    // A file path from React 18 (`_debugSource`) or Svelte carries the line on disk. A module URL
    // from a React 19 stack carries a line of the transformed module: look the definition up instead.
    let line = !c.source?.url && Number.isSafeInteger(c.source?.line) && c.source.line > 0 ? c.source.line : null;
    if (file && !line) line = await definitionLine(project, file, name);
    components.push(file ? { name, file, ...(line ? { line } : {}) } : { name });
  }
  const ancestors = element ? (Array.isArray(raw.ancestors) ? raw.ancestors : []).slice(0, LIMIT.ancestors).map(cleanAncestor).filter(Boolean) : [];

  if (drawn) {
    // A page cut short (its budget, 2,000 elements) or failing says nothing of an empty area: only a
    // whole walk that found nothing is `empty`, and only then are the neighbours worth sending.
    const members = cleanMembers(raw.members);
    const empty = Boolean(members) && members.length === 0 && raw.cut !== true;
    const scopes = cleanScopes(raw.scopes, regionStep(drawn, members, raw.cut === true));
    const centre = probePoint(probe);
    const result = {
      probeId: probe.probeId,
      ok: true,
      screen: pageScreen,
      point: { x: r4(centre.x), y: r4(centre.y) },
      ...(element ? { element } : {}),
      ancestors,
      components,
    };
    // The containers' ids are searched with the centre element's, as a ladder's are on Android and iOS.
    result.sourceHints = await findHints({ project, components, element: element ?? {}, scopeIds: scopeIdsOf(scopes) }).catch(() => []);
    Object.assign(result, { scopes, defaultScope: 0, members: members ?? [], empty });
    if (empty) result.near = cleanNear(raw.near) ?? [];
    return { result, crop: drawnArea ? await crop(page, drawnArea, view) : null, screen };
  }

  const sourceHints = await findHints({ project, components, element }).catch(() => []);
  const rect = cleanRect(raw.rect);
  // §15.7: a sentence step, when the page found one, comes before the element step; the element stays the default.
  const rawScopes = Array.isArray(raw.scopes) ? raw.scopes : [];
  const sentence = cleanSentence(rawScopes[0]);
  const ladder = sentence ? rawScopes.slice(1) : rawScopes;
  const scopes = cleanScopes(ladder, elementStep(ladder, element));
  if (sentence && scopes.length >= LIMIT.scopes) scopes.splice(scopes.length - 2, 1);
  const result = {
    probeId: probe.probeId,
    ok: true,
    screen: pageScreen,
    point: { x: r4(probe.x), y: r4(probe.y) },
    element,
    ancestors,
    components,
    sourceHints,
    scopes: sentence ? [sentence, ...scopes] : scopes,
    defaultScope: sentence ? 1 : 0,
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
 * only, words and names cut to size, bounds in 0..1 with something visible. The first step is made
 * here (elementStep, regionStep), never taken from the page. Whatever the page sent, the ladder
 * starts there and ends at the screen.
 */
function cleanScopes(raw, first) {
  const list = Array.isArray(raw) ? raw : [];
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
    const peers = cleanPeers(s.peers);
    if (peers) step.peers = peers;
    steps.push(step);
  }
  steps.push(screenScope());
  return steps;
}

/** §15.3: how many look the same, and where (20 boxes at most). One is not "the same thing, all of them". */
function cleanPeers(p) {
  if (!p || typeof p !== 'object' || !Number.isInteger(p.count) || p.count < 2) return null;
  const bounds = (Array.isArray(p.bounds) ? p.bounds : []).slice(0, 20).map(cleanBounds).filter(Boolean);
  return { count: Math.min(p.count, 10000), bounds };
}

/** §15.7: the sentence step, with the sentence as long as an element's text may be. */
function cleanSentence(s) {
  if (!s || typeof s !== 'object' || s.kind !== 'text') return null;
  const bounds = cleanBounds(s.bounds);
  const text = str(s.text, LIMIT.text);
  return bounds && text ? { kind: 'text', type: 'text', text, bounds } : null;
}

/**
 * The element step, rebuilt from the cleaned `element` so the two always agree; only the component
 * name comes from the page's step.
 */
function elementStep(raw, element) {
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
  const peers = cleanPeers(head.peers);
  if (peers) first.peers = peers;
  return first;
}

/**
 * The region step (§15.4): the rectangle the person drew, as the probe said it, and how many named
 * elements are inside. A count the page could not finish is left out rather than given as 0, which
 * would read "empty".
 */
function regionStep(rect, members, cut) {
  const step = { kind: 'region', type: 'region', bounds: { x: r4(rect.x), y: r4(rect.y), w: r4(rect.w), h: r4(rect.h) } };
  if (members && !(cut && members.length === 0)) step.members = members.length;
  return step;
}

/** The elements inside a dragged area as the page listed them (reading order), checked and cut. */
function cleanMembers(list) {
  if (!Array.isArray(list)) return null;
  const out = [];
  for (const m of list) {
    if (out.length >= LIMIT.members) break;
    const item = cleanItem(m);
    if (item) out.push(item);
  }
  return out;
}

/** The neighbours of an empty spot: one per side at most, up · down · left · right. */
function cleanNear(list) {
  if (!Array.isArray(list)) return null;
  const out = [];
  const seen = new Set();
  for (const n of list) {
    if (out.length >= LIMIT.near) break;
    if (!n || typeof n !== 'object' || !NEAR_DIRS.includes(n.dir) || seen.has(n.dir)) continue;
    if (typeof n.distance !== 'number' || !Number.isFinite(n.distance) || n.distance < 0) continue;
    const item = cleanItem(n);
    if (!item) continue;
    seen.add(n.dir);
    out.push({ dir: n.dir, ...item, distance: r4(n.distance) });
  }
  return out;
}

/** `{type, text?, id?, bounds}`; nothing without a box on screen. */
function cleanItem(m) {
  if (!m || typeof m !== 'object') return null;
  const bounds = cleanBounds(m.bounds);
  if (!bounds) return null;
  const item = { type: str(m.type, 40) ?? 'element' };
  const text = str(m.text, LIMIT.memberText);
  const id = str(m.id, 100);
  if (text) item.text = text;
  if (id) item.id = id;
  item.bounds = bounds;
  return item;
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
