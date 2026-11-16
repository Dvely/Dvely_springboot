/**
 * Pinning on a shared web page (§14.3, #682): what is at the viewer's point, which component drew
 * it, where that component lives in the project, and a crop of it.
 *
 * The page is read with one Runtime.evaluate of `inspectPoint` below. It only reads the DOM and the
 * framework's dev-mode bookkeeping (React fibers, Vue instances, Svelte metadata); it changes
 * nothing and calls nothing of the page's own.
 */
import { LIMIT, clip, r4, unresolved } from './pin.js';
import { definitionLine, findSourceHints, toProjectPath } from './source-hints.js';

const EVALUATE_TIMEOUT_MS = 1500;
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
  const parts = [];
  for (let e = el, i = 0; e && e.nodeType === 1 && e !== document.body && e !== document.documentElement && i < 4; e = e.parentElement, i++) {
    const p = part(e);
    parts.unshift(p);
    if (p[0] === '#') break;
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
    selector: fold(parts.join(' > '), L.selector),
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

  // React: walk the fiber chain up from the element. A component's own file is where the JSX it
  // rendered was written — the source of a fiber it owns — not where it was used.
  const react = (node) => {
    const key = Object.keys(node).find((k) => k.indexOf('__reactFiber$') === 0 || k.indexOf('__reactInternalInstance$') === 0);
    if (!key) return null;
    const nameOf = (t, depth) => {
      if (!t || depth > 3) return null;
      if (typeof t === 'function') return clean(t.displayName || t.name);
      if (typeof t === 'object') {
        if (t._context || t._payload) return null; // context providers, lazy wrappers
        return clean(t.displayName) || nameOf(t.render, depth + 1) || nameOf(t.type, depth + 1);
      }
      return null;
    };
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

  return {
    ok: true,
    screen,
    view,
    element,
    rect: visible ? { x: left, y: top, width: right - left, height: bottom - top } : null,
    ancestors,
    components: components || [],
    framework,
  };
}

/**
 * @param {object} o
 * @param {{send: Function}} o.page
 * @param {{probeId: string, x: number, y: number}} o.probe
 * @param {string|null} o.project the folder to look in for source candidates; null looks nowhere
 * @param {string|null} o.origin the shared origin
 * @param {Buffer|null} o.screen the latest screencast frame (what the viewer saw)
 * @returns {Promise<{result: object, crop: Buffer|null, screen: Buffer|null}>}
 */
export async function resolveWebPin({ page, probe, project, origin, screen = null, findHints = findSourceHints }) {
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
  };
  const area = rect && view ? padded(rect, view) : view ? spotAround(probe, view) : null;
  return { result, crop: area ? await crop(page, area, view) : null, screen };
}

const str = (v, max) => (typeof v === 'string' ? clip(v, max) : null);

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
 * A JPEG of one area of the page. Large areas are scaled down so the crop stays a crop (and under
 * the 512 KiB the server keeps). CDP's clip is in page coordinates, hence the scroll offset.
 */
const round = (v) => Math.round(v * 100) / 100;

async function crop(page, area, view) {
  const devicePixels = Math.max(area.width, area.height) * view.dpr;
  let scale = Math.min(1, CROP_MAX_PIXELS / devicePixels);
  for (const quality of [80, 55]) {
    try {
      const shot = await page.send('Page.captureScreenshot', {
        format: 'jpeg',
        quality,
        clip: { x: round(area.x + view.pageX), y: round(area.y + view.pageY), width: round(area.width), height: round(area.height), scale },
        captureBeyondViewport: false,
      });
      const bytes = typeof shot?.data === 'string' ? Buffer.from(shot.data, 'base64') : null;
      if (bytes && bytes.length <= CROP_MAX_BYTES) return bytes;
      scale /= 2;
    } catch {
      return null;
    }
  }
  return null;
}
