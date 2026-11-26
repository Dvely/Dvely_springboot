/*
 * Qeploy 프리뷰 짚기 브리지 — docs/device-preview-share-design.md §16.4
 *
 * 프리뷰 게이트웨이가 HTML 문서 head 에 경로 shim 다음으로 넣는다. 프리뷰 문서는 CSP sandbox 로
 * 불투명 오리진이고, 이 브리지는 프리뷰 코드와 같은 권한으로 돈다(더 여는 것이 없다).
 *
 * 기다리기만 한다: 부모 창(window.parent)이 보낸 {qeploy: 'pin', v: 1} 메시지에만 답하고, 답도
 * 부모에게만 보낸다. DOM 은 읽기만 한다(scroll 요청의 스크롤만 예외). 입력란에 친 값은 보내지 않는다.
 *
 * inspectPoint 는 agent-tools/packages/cli/src/share/pin-web.js 의 것을 글자 그대로 옮겼다.
 * PreviewPinBridgeSyncTest 가 두 본문이 같은지 본다 — CLI 쪽을 고치면 아래 BEGIN~END 를 같이 바꾼다.
 * 결과를 다듬는 부분(clean*)은 CLI resolveWebPin 에서 소스 후보·잘라 내기를 뺀 것이다. 서버가
 * PinResultSanitizer 로 같은 상한을 다시 건다.
 */
(function () {
  'use strict';
  var parentWindow;
  try {
    parentWindow = window.parent;
  } catch (e) {
    return;
  }
  if (!parentWindow || parentWindow === window) return;

  var VERSION = 1;
  var CAPS = ['point', 'rect', 'screen', 'hover', 'scroll'];
  // agent-tools/packages/cli/src/share/pin.js LIMIT 와 같다.
  var LIMIT = {
    text: 200, selector: 300, path: 300, label: 512, ancestors: 5, components: 8, hints: 5,
    scopes: 6, scopeText: 60, members: 12, memberText: 60, near: 4
  };
  var PROBE_ID = /^p[0-9]{1,9}$/;

  // BEGIN inspectPoint
function inspectPoint(args) {
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
  // END inspectPoint

  function readScreen() {
    var title = String(document.title == null ? '' : document.title).replace(/\s+/g, ' ').trim();
    return { label: location.pathname || '/', url: location.href.split('#')[0], title: title ? Array.from(title).slice(0, 200).join('') : null };
  }

  function clip(value, max) {
    if (value === null || value === undefined) return null;
    var text = String(value).replace(/\s+/g, ' ').trim();
    if (!text) return null;
    var points = Array.from(text);
    return points.length > max ? points.slice(0, max).join('') : text;
  }
  function r4(v) { return Math.round(Math.min(Math.max(v, 0), 1) * 10000) / 10000; }
  function str(v, max) { return typeof v === 'string' ? clip(v, max) : null; }
  function isRatio(v) { return typeof v === 'number' && isFinite(v) && v >= 0 && v <= 1; }
  function screenScope() { return { kind: 'screen', type: 'screen', bounds: { x: 0, y: 0, w: 1, h: 1 } }; }

  /**
   * 프리뷰는 production 빌드라 컴포넌트 함수 이름이 줄어 있다(`Xe`, `bt`). 그런 이름은 찾는 데 쓸모가
   * 없고 엉뚱한 파일로 이끌 수 있어 뺀다: 세 글자 미만이거나, 대문자 다음에 소문자가 오지 않는 이름.
   */
  function componentName(v) {
    var name = str(v, 100);
    return name && name.length >= 3 && /^[A-Z][a-z]/.test(name) ? name : null;
  }

  function cleanBounds(b) {
    if (!b || typeof b !== 'object' || ![b.x, b.y, b.w, b.h].every(function (v) { return typeof v === 'number' && isFinite(v); })) return null;
    var out = { x: r4(b.x), y: r4(b.y), w: r4(b.w), h: r4(b.h) };
    return out.w > 0 && out.h > 0 ? out : null;
  }

  /**
   * 이 문서의 주소는 게이트웨이 경로(/api/v1/previews/{세션}/{열람 토큰}/…)다. 토큰을 밖으로 내보내지 않게 앱이 보는 경로로
   * 바꾼다. 서버도 같은 일을 한다(PreviewPinService.appPath) — 여기서는 부모 창에까지 토큰이 가지 않게.
   */
  var GATEWAY_PREFIX = /^(?:[A-Za-z][A-Za-z0-9+.-]*:\/\/[^\/?#]*)?\/api\/v1\/previews\/[^\/?#]+\/[^\/?#]+/;
  function appPath(value) {
    if (typeof value !== 'string') return value;
    var m = GATEWAY_PREFIX.exec(value);
    if (!m) return value;
    var rest = value.slice(m[0].length);
    return !rest ? '/' : rest.charAt(0) === '/' ? rest : '/' + rest;
  }

  function cleanScreen(s) {
    if (!s || typeof s !== 'object') return undefined;
    var out = {};
    var label = str(appPath(s.label), LIMIT.label);
    var url = typeof s.url === 'string' ? appPath(s.url).slice(0, 2048) : null;
    var title = str(s.title, LIMIT.text);
    if (label) out.label = label;
    if (url) out.url = url;
    if (title) out.title = title;
    return Object.keys(out).length ? out : undefined;
  }

  function cleanElement(e) {
    var out = {};
    var type = str(e.type, 40);
    var text = str(e.text, LIMIT.text);
    var id = str(e.id, 100);
    var selector = str(e.selector, LIMIT.selector);
    if (type) out.type = type;
    if (text) out.text = text;
    if (id) out.id = id;
    if (selector) out.selector = selector;
    var bounds = cleanBounds(e.bounds);
    if (bounds) out.bounds = bounds;
    return out;
  }

  function cleanAncestor(a) {
    if (!a || typeof a !== 'object') return null;
    var out = {};
    var type = str(a.type, 40);
    var id = str(a.id, 100);
    var text = str(a.text, 80);
    if (type) out.type = type;
    if (id) out.id = id;
    if (text) out.text = text;
    return Object.keys(out).length ? out : null;
  }

  /** 이름만 보낸다. 파일 위치는 빌드 컨테이너의 것이라 쓸모가 없다(§16.4). */
  function cleanComponents(list) {
    var out = [];
    (Array.isArray(list) ? list : []).slice(0, LIMIT.components).forEach(function (c) {
      var name = componentName(c && c.name);
      if (name) out.push({ name: name });
    });
    return out;
  }

  function cleanItem(m) {
    if (!m || typeof m !== 'object') return null;
    var bounds = cleanBounds(m.bounds);
    if (!bounds) return null;
    var item = { type: str(m.type, 40) || 'element' };
    var text = str(m.text, LIMIT.memberText);
    var id = str(m.id, 100);
    if (text) item.text = text;
    if (id) item.id = id;
    item.bounds = bounds;
    return item;
  }

  function cleanMembers(list) {
    if (!Array.isArray(list)) return null;
    var out = [];
    for (var i = 0; i < list.length && out.length < LIMIT.members; i++) {
      var item = cleanItem(list[i]);
      if (item) out.push(item);
    }
    return out;
  }

  var NEAR_DIRS = ['up', 'down', 'left', 'right'];
  function cleanNear(list) {
    if (!Array.isArray(list)) return null;
    var out = [];
    var seen = {};
    for (var i = 0; i < list.length && out.length < LIMIT.near; i++) {
      var n = list[i];
      if (!n || typeof n !== 'object' || NEAR_DIRS.indexOf(n.dir) < 0 || seen[n.dir]) continue;
      if (typeof n.distance !== 'number' || !isFinite(n.distance) || n.distance < 0) continue;
      var item = cleanItem(n);
      if (!item) continue;
      seen[n.dir] = true;
      var near = { dir: n.dir };
      Object.keys(item).forEach(function (k) { near[k] = item[k]; });
      near.distance = r4(n.distance);
      out.push(near);
    }
    return out;
  }

  var STEP_KINDS = { group: true, component: true };
  function cleanScopes(raw, first) {
    var list = Array.isArray(raw) ? raw : [];
    var steps = [first];
    for (var i = 1; i < list.length; i++) {
      if (steps.length >= LIMIT.scopes - 1) break;
      var s = list[i];
      if (!s || typeof s !== 'object' || !STEP_KINDS[s.kind]) continue;
      var bounds = cleanBounds(s.bounds);
      if (!bounds) continue;
      var step = { kind: s.kind, type: str(s.type, 40) || 'element' };
      var text = str(s.text, LIMIT.scopeText);
      var id = str(s.id, 100);
      var selector = str(s.selector, LIMIT.selector);
      var name = componentName(s.component);
      if (text) step.text = text;
      if (id) step.id = id;
      if (selector) step.selector = selector;
      if (name) step.component = name;
      else if (s.kind === 'component') continue;
      step.bounds = bounds;
      var peers = cleanPeers(s.peers);
      if (peers) step.peers = peers;
      steps.push(step);
    }
    steps.push(screenScope());
    return steps;
  }

  function elementStep(raw, element) {
    var list = Array.isArray(raw) ? raw : [];
    var head = list[0] && typeof list[0] === 'object' && list[0].kind === 'element' ? list[0] : {};
    var first = { kind: 'element' };
    if (element.type) first.type = element.type;
    var said = clip(element.text, LIMIT.scopeText);
    if (said) first.text = said;
    if (element.id) first.id = element.id;
    if (element.selector) first.selector = element.selector;
    var component = componentName(head.component);
    if (component) first.component = component;
    if (element.bounds) first.bounds = element.bounds;
    var peers = cleanPeers(head.peers);
    if (peers) first.peers = peers;
    return first;
  }

  /** §15.3: 같은 모양 형제의 수와 화면 안의 테두리(20개까지) */
  function cleanPeers(p) {
    if (!p || typeof p !== 'object' || typeof p.count !== 'number' || p.count % 1 !== 0 || p.count < 2) return null;
    var bounds = (Array.isArray(p.bounds) ? p.bounds : []).slice(0, 20).map(cleanBounds).filter(Boolean);
    return { count: Math.min(p.count, 10000), bounds: bounds };
  }

  /** §15.7: 문장 칸. 문장은 요소 글자만큼(200자)까지 */
  function cleanSentence(s) {
    if (!s || typeof s !== 'object' || s.kind !== 'text') return null;
    var bounds = cleanBounds(s.bounds);
    var text = str(s.text, LIMIT.text);
    return bounds && text ? { kind: 'text', type: 'text', text: text, bounds: bounds } : null;
  }

  function regionStep(rect, members, cut) {
    var step = { kind: 'region', type: 'region', bounds: { x: r4(rect.x), y: r4(rect.y), w: r4(rect.w), h: r4(rect.h) } };
    if (members && !(cut && members.length === 0)) step.members = members.length;
    return step;
  }

  function unresolved(probe, reason, screen, at) {
    var out = { probeId: probe.id, ok: false, reason: reason };
    if (at && isRatio(at.x) && isRatio(at.y)) out.point = { x: r4(at.x), y: r4(at.y) };
    if (screen) out.screen = screen;
    return out;
  }

  /** CLI resolveWebPin 과 같은 결과 모양(§14.3·§15). sourceHints 와 그림은 없다. */
  function resolve(probe) {
    if (probe.screen) {
      var whole = { probeId: probe.id, ok: true, scopes: [screenScope()], defaultScope: 0 };
      var where = cleanScreen(readScreen());
      if (where) whole.screen = where;
      return whole;
    }
    var drawn = probe.rect || null;
    var at = drawn ? { x: drawn.x + drawn.w / 2, y: drawn.y + drawn.h / 2 } : { x: probe.x, y: probe.y };
    var raw = null;
    try {
      raw = inspectPoint(drawn ? { rect: drawn, limit: LIMIT } : { x: probe.x, y: probe.y, limit: LIMIT });
    } catch (e) {
      raw = null;
    }
    if (!raw || typeof raw !== 'object') return unresolved(probe, 'UNSUPPORTED', null, at);
    var pageScreen = cleanScreen(raw.screen);
    if (!drawn && (!raw.ok || !raw.element || typeof raw.element !== 'object')) {
      var missed = unresolved(probe, 'NO_ELEMENT', pageScreen, at);
      var around = cleanNear(raw.near);
      if (around && around.length) missed.near = around;
      return missed;
    }
    if (drawn && !raw.ok) return unresolved(probe, 'NO_ELEMENT', pageScreen, at);

    var element = raw.element && typeof raw.element === 'object' ? cleanElement(raw.element) : null;
    var components = element ? cleanComponents(raw.components) : [];
    var ancestors = element ? (Array.isArray(raw.ancestors) ? raw.ancestors : []).slice(0, LIMIT.ancestors).map(cleanAncestor).filter(Boolean) : [];
    var out = { probeId: probe.id, ok: true, point: { x: r4(at.x), y: r4(at.y) } };
    if (pageScreen) out.screen = pageScreen;
    if (drawn) {
      var members = cleanMembers(raw.members);
      var empty = Boolean(members) && members.length === 0 && raw.cut !== true;
      if (element) out.element = element;
      out.ancestors = ancestors;
      out.components = components;
      out.scopes = cleanScopes(raw.scopes, regionStep(drawn, members, raw.cut === true));
      out.defaultScope = 0;
      out.members = members || [];
      out.empty = empty;
      if (empty) out.near = cleanNear(raw.near) || [];
      return out;
    }
    out.element = element;
    out.ancestors = ancestors;
    out.components = components;
    // §15.7: 문장 칸이 있으면 요소 칸 앞에 둔다. 기본 칸은 요소 그대로
    var rawScopes = Array.isArray(raw.scopes) ? raw.scopes : [];
    var sentence = cleanSentence(rawScopes[0]);
    var ladder = sentence ? rawScopes.slice(1) : rawScopes;
    var scopes = cleanScopes(ladder, elementStep(ladder, element));
    if (sentence && scopes.length >= LIMIT.scopes) scopes.splice(scopes.length - 2, 1);
    out.scopes = sentence ? [sentence].concat(scopes) : scopes;
    out.defaultScope = sentence ? 1 : 0;
    return out;
  }

  /** 짚으면 잡힐 요소의 테두리만(§16.4 hover). 짚기와 같은 규칙으로 고른다. */
  function hit(x, y) {
    var raw = null;
    try {
      raw = inspectPoint({ x: x, y: y, limit: LIMIT });
    } catch (e) {
      raw = null;
    }
    var bounds = raw && raw.ok && raw.element ? cleanBounds(raw.element.bounds) : null;
    return bounds ? { x: x, y: y, bounds: bounds } : { x: x, y: y };
  }

  function parseRect(r) {
    if (!r || typeof r !== 'object') return null;
    var x = r.x, y = r.y, w = r.w, h = r.h;
    if (![x, y, w, h].every(function (v) { return typeof v === 'number' && isFinite(v); })) return null;
    if (x < 0 || y < 0 || !(w > 0) || !(h > 0) || x + w > 1.0001 || y + h > 1.0001) return null;
    return { x: x, y: y, w: Math.min(w, 1 - x), h: Math.min(h, 1 - y) };
  }

  function reply(message) {
    message.qeploy = 'pin';
    message.v = VERSION;
    try {
      // 불투명 오리진에서 부모의 이름을 알 길이 없다. 보내는 것은 이 문서에서 읽은 것뿐이다.
      parentWindow.postMessage(message, '*');
    } catch (e) {
      // 부모가 사라졌다 — 알릴 곳이 없다.
    }
  }

  window.addEventListener('message', function (event) {
    if (event.source !== parentWindow) return;
    var data = event.data;
    if (!data || typeof data !== 'object' || data.qeploy !== 'pin' || data.v !== VERSION) return;
    switch (data.type) {
      case 'hello':
        reply({ type: 'ready', caps: CAPS });
        return;
      case 'hover':
        if (isRatio(data.x) && isRatio(data.y)) {
          var h = hit(data.x, data.y);
          h.type = 'hit';
          reply(h);
        }
        return;
      case 'probe': {
        if (typeof data.id !== 'string' || !PROBE_ID.test(data.id)) return;
        var probe = null;
        if (data.screen === true) probe = { id: data.id, screen: true };
        else if (data.rect !== undefined) {
          var rect = parseRect(data.rect);
          probe = rect ? { id: data.id, rect: rect } : null;
        } else if (isRatio(data.x) && isRatio(data.y)) probe = { id: data.id, x: data.x, y: data.y };
        if (!probe) return;
        reply({ type: 'result', id: data.id, result: resolve(probe) });
        return;
      }
      case 'scroll':
        if (typeof data.dx === 'number' && typeof data.dy === 'number' && isFinite(data.dx) && isFinite(data.dy)) {
          window.scrollBy(Math.max(-4000, Math.min(4000, data.dx)), Math.max(-4000, Math.min(4000, data.dy)));
        }
        return;
      default:
    }
  });
})();
