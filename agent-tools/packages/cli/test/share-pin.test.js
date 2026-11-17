import assert from 'node:assert/strict';
import { afterEach, beforeEach, describe, it } from 'node:test';
import { mkdirSync, mkdtempSync, realpathSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { deflateSync } from 'node:zlib';
import { parsePng } from '../src/share/png.js';
import { join } from 'node:path';
import {
  MAX_CROP_BYTES,
  MAX_RESULT_JSON_BYTES,
  MAX_RESULT_JSON_BYTES_SCOPED,
  MAX_SCREEN_BYTES,
  encodePinResult,
  fitResult,
  noticeLine,
  parseNotice,
  parseProbe,
  pinResultLimit,
  screenResult,
  unresolved,
} from '../src/share/pin.js';
import { clearListings, definitionLine, findSourceHints, listSourceFiles, projectRoot, searchableText, toProjectPath } from '../src/share/source-hints.js';
import { inspectPoint, padded, spotAround } from '../src/share/pin-web.js';
import { LIMIT } from '../src/share/pin.js';

const JPEG = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x01, 0xff, 0xd9]);

/** Splits a PIN_RESULT payload back into its three parts (§14.3). */
export function decodePinResult(payload) {
  const jsonLength = payload.readUInt32BE(0);
  const json = JSON.parse(payload.subarray(4, 4 + jsonLength).toString('utf8'));
  const cropLength = payload.readUInt32BE(4 + jsonLength);
  const crop = payload.subarray(8 + jsonLength, 8 + jsonLength + cropLength);
  const screen = payload.subarray(8 + jsonLength + cropLength);
  return { json, crop, screen };
}

describe('pin probe and result (§14.3)', () => {
  it('takes only a well-formed probe', () => {
    assert.deepEqual(parseProbe({ probeId: 'p7', x: 0.42, y: 0.17 }), { probeId: 'p7', x: 0.42, y: 0.17 });
    for (const bad of [
      null,
      { probeId: 'p7', x: 1.2, y: 0.1 },
      { probeId: 'p7', x: -0.1, y: 0.1 },
      { probeId: 'p7', x: '0.5', y: 0.1 },
      { probeId: '7', x: 0.5, y: 0.5 },
      { probeId: 'p1234567890', x: 0.5, y: 0.5 },
      { probeId: 'p1;rm', x: 0.5, y: 0.5 },
    ]) {
      assert.equal(parseProbe(bad), null, JSON.stringify(bad));
    }
  });

  it('takes a whole-screen probe, and drops shapes it does not read yet (§15.6, §15.9)', () => {
    assert.deepEqual(parseProbe({ probeId: 'p8', screen: true }), { probeId: 'p8', screen: true });
    for (const bad of [
      { probeId: 'p8', screen: true, x: 0.5, y: 0.5 },
      { probeId: 'p8', screen: true, x: 0.5 },
      { probeId: 'p8', screen: 'yes' },
      { probeId: 'x8', screen: true },
      { probeId: 'p8', rect: { x: 0.1, y: 0.1, w: 0.2, h: 0.2 } },
      { probeId: 'p8', x: 0.5, y: 0.5, rect: { x: 0.1, y: 0.1, w: 0.2, h: 0.2 } },
    ]) {
      assert.equal(parseProbe(bad), null, JSON.stringify(bad));
    }
    // An answer to a probe without a point says no point.
    assert.deepEqual(unresolved({ probeId: 'p8', screen: true }, 'TIMEOUT'), { probeId: 'p8', ok: false, reason: 'TIMEOUT' });
    assert.deepEqual(screenResult({ probeId: 'p8', screen: true }, { label: '/' }), {
      probeId: 'p8',
      ok: true,
      screen: { label: '/' },
      scopes: [{ kind: 'screen', type: 'screen', bounds: { x: 0, y: 0, w: 1, h: 1 } }],
      defaultScope: 0,
    });
  });

  it('takes the result limit from WELCOME only when it is a whole number from 16 to 48 KiB', () => {
    assert.equal(MAX_RESULT_JSON_BYTES, 16384);
    assert.equal(MAX_RESULT_JSON_BYTES_SCOPED, 49152);
    for (const ok of [16384, 32768, 49152]) assert.equal(pinResultLimit(ok), ok);
    for (const bad of [undefined, null, 16383, 49153, 1e9, 32768.5, '49152', NaN, -1]) assert.equal(pinResultLimit(bad), 16384, String(bad));
  });

  it('lays out [json length][json][crop length][crop][screen]', () => {
    const payload = encodePinResult({ probeId: 'p1', ok: true }, JPEG, Buffer.concat([JPEG, JPEG]));
    const { json, crop, screen } = decodePinResult(payload);
    assert.deepEqual(json, { probeId: 'p1', ok: true });
    assert.deepEqual(crop, JPEG);
    assert.deepEqual(screen, Buffer.concat([JPEG, JPEG]));
  });

  it('sends no image that is missing, not a JPEG, or over its limit', () => {
    const png = Buffer.from([0x89, 0x50, 0x4e, 0x47]);
    const big = (n) => Buffer.concat([JPEG, Buffer.alloc(n)]);
    for (const [crop, screen] of [
      [null, null],
      [png, png],
      [big(MAX_CROP_BYTES), big(MAX_SCREEN_BYTES)],
    ]) {
      const { crop: c, screen: s } = decodePinResult(encodePinResult({ probeId: 'p1', ok: false }, crop, screen));
      assert.equal(c.length, 0);
      assert.equal(s.length, 0);
    }
  });

  it('lets go of the least useful parts first to stay under 16 KiB', () => {
    const long = 'x'.repeat(5000);
    const result = {
      probeId: 'p3',
      ok: true,
      point: { x: 0.5, y: 0.5 },
      screen: { label: '/settings' },
      element: { type: 'button', text: '저장', bounds: { x: 0.1, y: 0.1, w: 0.1, h: 0.1 } },
      ancestors: Array.from({ length: 5 }, () => ({ type: 'div', text: long })),
      components: [{ name: 'SaveButton', file: 'src/SaveButton.tsx' }],
      sourceHints: [{ file: 'src/SaveButton.tsx', reason: 'COMPONENT' }],
    };
    const fitted = JSON.parse(fitResult(result));
    assert.ok(Buffer.byteLength(JSON.stringify(fitted)) <= MAX_RESULT_JSON_BYTES);
    assert.equal(fitted.ancestors, undefined);
    assert.equal(fitted.element.text, '저장');
    assert.equal(fitted.components[0].name, 'SaveButton');
    assert.equal(fitted.sourceHints[0].reason, 'COMPONENT');
  });

  const SCREEN_STEP = { kind: 'screen', type: 'screen', bounds: { x: 0, y: 0, w: 1, h: 1 } };
  const BUTTON_BOUNDS = { x: 0.1, y: 0.25, w: 0.12, h: 0.055 };
  /** A result with a five-step ladder whose outer steps are fat enough to break 16 KiB. */
  function scopedResult({ stepBytes = 4500, components = [{ name: 'SaveButton' }] } = {}) {
    const wide = (n) => ({ kind: 'group', type: 'section', id: `s${n}`, selector: `#s${n} ` + 'y'.repeat(stepBytes), bounds: { x: 0, y: 0, w: 0.2 * n, h: 0.2 * n } });
    return {
      probeId: 'p4',
      ok: true,
      point: { x: 0.13, y: 0.27 },
      screen: { label: '/settings' },
      element: { type: 'button', text: '저장', selector: 'form > button.save', bounds: BUTTON_BOUNDS },
      ancestors: [{ type: 'form' }],
      components,
      sourceHints: [{ file: 'src/SaveButton.tsx', reason: 'COMPONENT' }],
      scopes: [{ kind: 'element', type: 'button', text: '저장', selector: 'form > button.save', component: 'SaveButton', bounds: BUTTON_BOUNDS }, wide(1), wide(2), wide(3), wide(4), SCREEN_STEP],
      defaultScope: 0,
    };
  }

  it('lets go of the widest scope steps first, keeping the element step, the screen and everything else (§15.9)', () => {
    const result = scopedResult();
    const fitted = JSON.parse(fitResult(result));
    assert.ok(Buffer.byteLength(JSON.stringify(fitted)) <= MAX_RESULT_JSON_BYTES);
    assert.deepEqual(fitted.scopes.map((s) => s.id ?? s.kind), ['element', 's1', 's2', 's3', 'screen'], 'the widest step went, nothing else');
    assert.deepEqual(fitted.scopes[0], result.scopes[0]);
    assert.deepEqual(fitted.element, result.element);
    assert.deepEqual(fitted.ancestors, [{ type: 'form' }]);
    assert.equal(fitted.defaultScope, 0);
    // The same result where the server takes 48 KiB goes as it is.
    assert.deepEqual(JSON.parse(fitResult(result, MAX_RESULT_JSON_BYTES_SCOPED)), result);
    const { json } = decodePinResult(encodePinResult(result, null, null, MAX_RESULT_JSON_BYTES_SCOPED));
    assert.equal(json.scopes.length, 6);
    assert.equal(decodePinResult(encodePinResult(result, null, null)).json.scopes.length, 5);
  });

  it('keeps the element step and `element` through every step but the last resort', () => {
    // Fat steps and fat components: the ladder empties to the element and the screen, then the
    // §14 order runs down to the slim element.
    const components = Array.from({ length: 8 }, (_, i) => ({ name: `C${i}`, file: 'z'.repeat(6000) }));
    const fitted = JSON.parse(fitResult(scopedResult({ components })));
    assert.ok(Buffer.byteLength(JSON.stringify(fitted)) <= MAX_RESULT_JSON_BYTES);
    assert.deepEqual(fitted.scopes, [{ kind: 'element', type: 'button', bounds: BUTTON_BOUNDS }, SCREEN_STEP]);
    assert.deepEqual(fitted.element, { type: 'button', bounds: BUTTON_BOUNDS });
    assert.equal(fitted.ok, true);
    assert.equal(fitted.components, undefined);
    assert.equal(fitted.ancestors, undefined);
  });
});

describe('feedback notice in the owner terminal (§14.4)', () => {
  it('shows who, what and the start of the comment on one safe line', () => {
    const notice = parseNotice({
      feedbackId: 21,
      author: { role: 'GUEST', name: '민지\u001b[2J' },
      label: '<button> "저장"',
      comment: '더 크게\n\u001b]0;pwned\u0007 파란색으로',
      state: 'NEW',
    });
    assert.equal(notice.guest, true);
    const line = noticeLine(notice);
    assert.equal(line, `피드백 #21 · 손님 '민지' · <button> "저장" · "더 크게 파란색으로"`);
    assert.doesNotMatch(line, /[\u0000-\u001f]/);
  });

  it('ignores a notice without a feedback id', () => {
    assert.equal(parseNotice({ feedbackId: 'x' }), null);
    assert.equal(parseNotice({ feedbackId: -1 }), null);
    assert.equal(parseNotice(null), null);
  });
});

describe('source candidates in the project folder (§14.3, #682)', () => {
  let root;
  let outside;
  beforeEach(() => {
    clearListings();
    root = realpathSync(mkdtempSync(join(tmpdir(), 'qeploy-pin-')));
    outside = realpathSync(mkdtempSync(join(tmpdir(), 'qeploy-outside-')));
    const file = (rel, body) => {
      mkdirSync(join(root, rel, '..'), { recursive: true });
      writeFileSync(join(root, rel), body);
    };
    file('package.json', '{"name":"demo"}');
    file('src/components/SaveButton.tsx', 'export function SaveButton() {\n  return <button id="save">저장</button>;\n}\n');
    file('src/pages/Settings.tsx', 'import { SaveButton } from "../components/SaveButton";\n\nexport default function Settings() {\n  return <form><h1>프로필</h1><SaveButton /></form>;\n}\n');
    file('src/i18n/ko.json', '{\n  "save": "저장"\n}\n');
    file('node_modules/ui/Button.tsx', 'export function SaveButton() { return "저장"; }\n');
    file('dist/assets/index.js', 'id="save" 저장 function SaveButton(){}\n');
    file('.cache/x.tsx', '저장\n');
    writeFileSync(join(outside, 'secret.tsx'), '저장\n');
    symlinkSync(join(outside, 'secret.tsx'), join(root, 'src', 'linked.tsx'));
  });
  afterEach(() => clearListings());

  it('searches a folder only when it looks like a project, or when --project names it', () => {
    assert.deepEqual(projectRoot({ cwd: root }), { dir: root, why: 'cwd' });
    assert.deepEqual(projectRoot({ cwd: outside }), { dir: null, why: 'not-project' });
    assert.deepEqual(projectRoot({ flag: outside, cwd: root }), { dir: outside, why: 'flag' });
    assert.deepEqual(projectRoot({ flag: 'nope', cwd: root }), { dir: null, why: 'missing' });
  });

  it('turns what the dev server reported into a project path, and nothing outside the project', () => {
    const origin = 'http://localhost:5173';
    assert.equal(toProjectPath({ path: join(root, 'src/components/SaveButton.tsx') }, root), 'src/components/SaveButton.tsx');
    assert.equal(toProjectPath({ path: 'src/pages/Settings.tsx' }, root), 'src/pages/Settings.tsx');
    assert.equal(toProjectPath({ url: `${origin}/src/components/SaveButton.tsx?t=1712` }, root, origin), 'src/components/SaveButton.tsx');
    assert.equal(toProjectPath({ url: `${origin}/@fs${join(root, 'src/pages/Settings.tsx')}` }, root, origin), 'src/pages/Settings.tsx');
    for (const src of [
      { path: join(outside, 'secret.tsx') },
      { path: '../outside/secret.tsx' },
      { path: '/etc/hosts' },
      { path: 'src/linked.tsx' }, // a link out of the project
      { path: 'node_modules/ui/Button.tsx' },
      { path: '.cache/x.tsx' },
      { path: 'src/missing.tsx' },
      { url: `http://evil.example/src/components/SaveButton.tsx` },
      { url: 'file:///etc/hosts' },
      { url: 'not a url' },
    ]) {
      assert.equal(toProjectPath(src, root, origin), null, JSON.stringify(src));
    }
    assert.equal(toProjectPath({ path: 'src/pages/Settings.tsx' }, null), null);
  });

  it('puts the reported component first, then definitions, ids and visible text', async () => {
    const hints = await findSourceHints({
      project: root,
      components: [{ name: 'SaveButton', file: 'src/components/SaveButton.tsx', line: 2 }, { name: 'Settings' }],
      element: { id: 'save', text: '저장' },
    });
    assert.deepEqual(hints[0], { file: 'src/components/SaveButton.tsx', line: 2, reason: 'COMPONENT' });
    assert.ok(hints.some((h) => h.file === 'src/pages/Settings.tsx' && h.reason === 'COMPONENT' && h.line === 3), JSON.stringify(hints));
    assert.ok(hints.some((h) => h.file === 'src/i18n/ko.json' && h.reason === 'TEXT' && h.line === 2), JSON.stringify(hints));
    assert.ok(hints.length <= 5);
    for (const h of hints) {
      assert.doesNotMatch(h.file, /node_modules|dist\/|\.cache|linked/, h.file);
      assert.ok(!h.file.startsWith('/'));
    }
  });

  it('finds a component known only by name: its definition, or at least its file', async () => {
    let hints = await findSourceHints({ project: root, components: [{ name: 'SaveButton' }], element: {} });
    assert.deepEqual(hints[0], { file: 'src/components/SaveButton.tsx', line: 1, reason: 'COMPONENT' });
    // No time left to read files (the listing is cached): the file name still says where it is.
    await listSourceFiles(root);
    let first = true;
    const late = () => (first ? ((first = false), 0) : 5000);
    hints = await findSourceHints({ project: root, components: [{ name: 'SaveButton' }], element: {}, budgetMs: 1000, now: late });
    assert.deepEqual(hints[0], { file: 'src/components/SaveButton.tsx', reason: 'COMPONENT' });
  });

  it('finds an id and visible text without any component', async () => {
    const hints = await findSourceHints({ project: root, components: [], element: { id: 'save', text: '저장' } });
    assert.deepEqual(hints[0], { file: 'src/components/SaveButton.tsx', line: 2, reason: 'ID' });
    assert.ok(hints.some((h) => h.reason === 'TEXT'));
  });

  it('stops when the time is up and looks nowhere without a project', async () => {
    let t = 0;
    const hints = await findSourceHints({ project: root, element: { text: '저장' }, budgetMs: 5, now: () => (t += 10) });
    assert.deepEqual(hints, []);
    assert.deepEqual(await findSourceHints({ project: null, element: { text: '저장' } }), []);
  });

  it('finds where a component is defined in its file', async () => {
    assert.equal(await definitionLine(root, 'src/pages/Settings.tsx', 'Settings'), 3);
    assert.equal(await definitionLine(root, 'src/components/SaveButton.tsx', 'SaveButton'), 1);
    assert.equal(await definitionLine(root, 'src/components/SaveButton.tsx', 'Missing'), null);
    assert.equal(await definitionLine(root, 'src/components/SaveButton.tsx', 'a.*'), null);
    assert.equal(await definitionLine(root, 'src/nope.tsx', 'SaveButton'), null);
  });

  it('does not search for text that is too short, too long or has no letters', () => {
    assert.equal(searchableText('저장'), '저장');
    for (const t of ['x', '1234', '...', 'a'.repeat(81), null]) assert.equal(searchableText(t), null, String(t));
  });
});

describe('reading the page at a point (inspectPoint, §14.3)', () => {
  const saved = {};
  afterEach(() => {
    for (const k of ['window', 'document', 'location', 'performance']) {
      if (saved[k] === undefined) delete globalThis[k];
      else globalThis[k] = saved[k];
    }
  });

  /** A small stand-in for the DOM: just what inspectPoint reads. */
  function el(localName, { id = '', attrs = {}, text = '', rect = null, parent = null, classes = [], extra = {} } = {}) {
    const node = {
      nodeType: 1,
      localName,
      id,
      innerText: text,
      textContent: text,
      parentElement: parent,
      classList: classes,
      getAttribute: (name) => attrs[name] ?? null,
      getBoundingClientRect: () => rect ?? { left: 0, top: 0, right: 0, bottom: 0 },
      closest: () => null,
      getRootNode: () => ({}),
      ...extra,
    };
    return node;
  }

  function page(hit, { width = 1000, height = 800 } = {}) {
    for (const k of ['window', 'document', 'location', 'performance']) saved[k] = globalThis[k];
    const body = el('body');
    globalThis.document = { body, documentElement: el('html'), title: 'Vite App', elementFromPoint: () => hit };
    globalThis.window = { innerWidth: width, innerHeight: height, devicePixelRatio: 2, scrollX: 0, scrollY: 120 };
    globalThis.location = { pathname: '/settings', href: 'http://localhost:5173/settings?tab=1#top' };
    return body;
  }

  it('picks the button under a label, and names React components with their own files', () => {
    function ProfileForm() {}
    function SaveButton() {}
    const formFiber = { type: ProfileForm, return: null };
    const buttonComponentFiber = {
      type: SaveButton,
      return: formFiber,
      _debugOwner: formFiber,
      _debugSource: { fileName: '/proj/src/ProfileForm.tsx', lineNumber: 30 },
    };
    const hostFiber = {
      type: 'button',
      return: buttonComponentFiber,
      _debugOwner: buttonComponentFiber,
      _debugSource: { fileName: '/proj/src/components/SaveButton.tsx', lineNumber: 12 },
    };
    const form = el('form', { classes: ['profile', 'css-1x2y3z4'] });
    const button = el('button', {
      id: '',
      classes: ['save'],
      text: ' 저장 ',
      rect: { left: 100, top: 200, right: 220, bottom: 244 },
      parent: form,
      extra: { __reactFiber$abc: hostFiber },
    });
    const span = el('span', { text: '저장', parent: button, rect: { left: 120, top: 210, right: 150, bottom: 230 } });
    const body = page(span);
    form.parentElement = body;

    const out = inspectPoint({ x: 0.13, y: 0.27, limit: LIMIT });
    assert.equal(out.ok, true);
    assert.deepEqual(out.element, {
      type: 'button',
      text: '저장',
      id: null,
      selector: 'form.profile > button.save',
      bounds: { x: 0.1, y: 0.25, w: 0.12, h: 0.055 },
    });
    assert.deepEqual(out.components, [
      { name: 'SaveButton', source: { path: '/proj/src/components/SaveButton.tsx', line: 12 } },
      { name: 'ProfileForm', source: { path: '/proj/src/ProfileForm.tsx', line: 30 } },
    ]);
    assert.equal(out.framework, 'react');
    assert.deepEqual(out.ancestors, [{ type: 'form', id: null, text: null }]);
    assert.deepEqual(out.screen, { label: '/settings', url: 'http://localhost:5173/settings?tab=1', title: 'Vite App' });
    assert.deepEqual(out.view, { width: 1000, height: 800, dpr: 2, pageX: 0, pageY: 120 });
  });

  it('names a text field by its label, never by what was typed into it', () => {
    const input = el('input', {
      attrs: { type: 'password', placeholder: '비밀번호' },
      rect: { left: 10, top: 10, right: 210, bottom: 50 },
      extra: { value: 'hunter2', labels: [] },
    });
    page(input);
    const out = inspectPoint({ x: 0.05, y: 0.03, limit: LIMIT });
    assert.equal(out.element.text, '비밀번호');
    assert.doesNotMatch(JSON.stringify(out), /hunter2/);
  });

  it('reads Vue and Svelte dev metadata', () => {
    const vueParent = { type: { name: 'SettingsPage', __file: '/proj/src/pages/SettingsPage.vue' }, parent: null };
    const vueChild = { type: { __name: 'SaveButton', __file: '/proj/src/components/SaveButton.vue' }, parent: vueParent };
    page(el('button', { text: '저장', rect: { left: 0, top: 0, right: 10, bottom: 10 }, extra: { __vueParentComponent: vueChild } }));
    let out = inspectPoint({ x: 0.001, y: 0.001, limit: LIMIT });
    assert.equal(out.framework, 'vue');
    assert.deepEqual(out.components.map((c) => c.name), ['SaveButton', 'SettingsPage']);

    const outer = el('div', { extra: { __svelte_meta: { loc: { file: 'src/routes/+page.svelte', line: 8 } } } });
    const inner = el('button', {
      text: '저장',
      parent: outer,
      rect: { left: 0, top: 0, right: 10, bottom: 10 },
      extra: { __svelte_meta: { loc: { file: 'src/lib/SaveButton.svelte', line: 3 } } },
    });
    page(inner);
    out = inspectPoint({ x: 0.001, y: 0.001, limit: LIMIT });
    assert.equal(out.framework, 'svelte');
    assert.deepEqual(out.components, [
      { name: 'SaveButton', source: { path: 'src/lib/SaveButton.svelte', line: 3 } },
      { name: '+page', source: { path: 'src/routes/+page.svelte', line: 8 } },
    ]);
  });

  it('says NO_ELEMENT for the page background', () => {
    const body = page(null);
    globalThis.document.elementFromPoint = () => body;
    assert.equal(inspectPoint({ x: 0.5, y: 0.5, limit: LIMIT }).reason, 'NO_ELEMENT');
  });

  const box = (left, top, right, bottom) => ({ left, top, right, bottom });
  const SCREEN_STEP = { kind: 'screen', type: 'screen', bounds: { x: 0, y: 0, w: 1, h: 1 } };

  /**
   * A React page (1000×800): main (full screen) > section#page (85 %) > section "프로필 설정" >
   * ProfileForm's root div > form#profile (as big as that div) > div.row > SaveButton's root span (as
   * big as the button) > button "저장". Fibers as React builds them: each DOM node's host fiber, the
   * components between them.
   */
  function profilePage() {
    function ProfileForm() {}
    function SaveButton() {}
    const fiber = (type, parent, tag = typeof type === 'string' ? 5 : 0) => ({ tag, type, return: parent });
    const root = fiber(null, null, 3);
    const sectionF = fiber('section', root);
    const profileF = fiber(ProfileForm, sectionF);
    const cardF = fiber('div', profileF);
    const formF = fiber('form', cardF);
    const rowF = fiber('div', formF);
    const saveF = fiber(SaveButton, rowF);
    const wrapF = fiber('span', saveF);
    const buttonF = fiber('button', wrapF);
    const f = (fib) => ({ __reactFiber$k1: fib });

    const main = el('main', { rect: box(0, 0, 1000, 800) });
    const outer = el('section', { id: 'page', rect: box(0, 0, 1000, 680), parent: main });
    const section = el('section', { attrs: { 'aria-label': '프로필 설정' }, rect: box(0, 100, 1000, 500), parent: outer, extra: f(sectionF) });
    const card = el('div', { classes: ['card'], rect: box(50, 120, 550, 420), parent: section, extra: f(cardF) });
    const form = el('form', { id: 'profile', rect: box(50, 120, 550, 410), parent: card, extra: { ...f(formF), childNodes: [{ nodeType: 3, nodeValue: ' 프로필 ' }, { nodeType: 1 }] } });
    const row = el('div', { classes: ['row'], rect: box(50, 190, 550, 260), parent: form, extra: f(rowF) });
    const wrap = el('span', { classes: ['save-wrap'], rect: box(100, 200, 222, 244), parent: row, extra: f(wrapF) });
    const button = el('button', { classes: ['save'], text: '저장', rect: box(100, 200, 220, 244), parent: wrap, extra: f(buttonF) });
    const body = page(button);
    main.parentElement = body;
    return { button, form };
  }

  const ELEMENT_STEP = {
    kind: 'element',
    type: 'button',
    text: '저장',
    selector: '#profile > div.row > span.save-wrap > button.save',
    bounds: { x: 0.1, y: 0.25, w: 0.12, h: 0.055 },
  };

  it('offers a ladder from the element to the screen: same-size boxes merged, components named, near-full-screen ones left to the screen step (§15.3)', () => {
    profilePage();
    const out = inspectPoint({ x: 0.13, y: 0.27, limit: LIMIT });
    assert.equal(out.ok, true);
    assert.equal(out.defaultScope, 0);
    assert.deepEqual(out.scopes, [
      // SaveButton's root is a span as big as the button: one step, the button's, with its name.
      { ...ELEMENT_STEP, component: 'SaveButton' },
      // ProfileForm's root div and the form inside it: one step, called by the form and the component.
      { kind: 'component', type: 'form', text: '프로필', id: 'profile', selector: '#profile', component: 'ProfileForm', bounds: { x: 0.05, y: 0.15, w: 0.5, h: 0.3625 } },
      { kind: 'group', type: 'section', text: '프로필 설정', selector: '#page > section', bounds: { x: 0, y: 0.125, w: 1, h: 0.5 } },
      // section#page (85 % of the viewport) and main (all of it) are as good as the screen.
      SCREEN_STEP,
    ]);
    // What older servers read is as before.
    assert.deepEqual(out.element, { type: 'button', text: '저장', id: null, selector: ELEMENT_STEP.selector, bounds: ELEMENT_STEP.bounds });
    assert.deepEqual(out.components.map((c) => c.name), ['SaveButton', 'ProfileForm']);
  });

  it('stops the ladder when its 400 ms are up, and still answers with the element', () => {
    profilePage();
    let reads = 0;
    globalThis.performance = { now: () => (reads++ === 0 ? 1000 : 1401) };
    const out = inspectPoint({ x: 0.13, y: 0.27, limit: LIMIT });
    assert.equal(out.ok, true);
    assert.deepEqual(out.scopes, [ELEMENT_STEP, SCREEN_STEP]);
    assert.equal(out.element.text, '저장');
    assert.ok(reads >= 2);
  });

  it('answers with the element when working out the ladder fails', () => {
    const { form } = profilePage();
    form.getBoundingClientRect = () => {
      throw new Error('detached');
    };
    const out = inspectPoint({ x: 0.13, y: 0.27, limit: LIMIT });
    assert.equal(out.ok, true);
    assert.equal(out.element.type, 'button');
    assert.equal(out.scopes, undefined, 'the device side falls back to the element and the screen');
  });

  it('keeps at most six steps, narrowest first, the screen last', () => {
    // Each box 1.2 times wider and taller than the one inside it: no two merge, none near full screen.
    const kinds = [['li'], ['ul'], ['nav'], ['div', { attrs: { role: 'region' } }], ['div', { id: 'filters' }], ['article'], ['aside'], ['footer']];
    let parent = null;
    const chain = [];
    for (let k = kinds.length; k >= 1; k--) {
      const s = 1.2 ** k;
      const [tag, opts = {}] = kinds[k - 1];
      const node = el(tag, { ...opts, rect: box(0, 0, 100 * s, 80 * s), parent });
      chain.push(node);
      parent = node;
    }
    const button = el('button', { text: '필터', rect: box(0, 0, 100, 80), parent });
    chain[0].parentElement = page(button);
    const out = inspectPoint({ x: 0.01, y: 0.01, limit: LIMIT });
    assert.equal(out.scopes.length, 6);
    assert.deepEqual(out.scopes.map((s) => s.type), ['button', 'li', 'ul', 'nav', 'region', 'screen']);
    assert.deepEqual(out.scopes.map((s) => s.kind), ['element', 'group', 'group', 'group', 'group', 'screen']);
  });

  it('names a Vue 3 component step by the component whose rendered tree starts at that node', () => {
    const instance = { type: { __name: 'PlanCard', __file: '/proj/src/components/PlanCard.vue' }, subTree: {}, parent: null };
    const card = el('div', { classes: ['plan'], rect: box(0, 0, 400, 300), extra: { __vueParentComponent: instance } });
    instance.subTree.el = card;
    const button = el('button', { text: '바꾸기', rect: box(20, 240, 120, 280), parent: card, extra: { __vueParentComponent: instance } });
    page(button);
    card.parentElement = globalThis.document.body;
    const out = inspectPoint({ x: 0.05, y: 0.32, limit: LIMIT });
    assert.equal(out.scopes[0].component, undefined, 'the button is not the root of PlanCard, and much smaller than it');
    assert.deepEqual(out.scopes[1], { kind: 'component', type: 'div', selector: 'div.plan', component: 'PlanCard', bounds: { x: 0, y: 0, w: 0.4, h: 0.375 } });
    assert.deepEqual(out.scopes.at(-1), SCREEN_STEP);
  });

  it('crops the element with a margin, or a fixed spot, and stays on screen', () => {
    const view = { width: 1000, height: 800 };
    assert.deepEqual(padded({ x: 5, y: 100, width: 120, height: 44 }, view), { x: 0, y: 84, width: 141, height: 76 });
    assert.deepEqual(spotAround({ x: 0.99, y: 0.01 }, view), { x: 760, y: 0, width: 240, height: 160 });
  });
});

describe('PNG for web crops (no CDP clip)', () => {
  /** A PNG whose rows use every filter, encoded the way the spec defines them. */
  function pngWithFilters(width, height, channels, pixel) {
    const stride = width * channels;
    const plain = Buffer.alloc(height * stride);
    for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) pixel(x, y).slice(0, channels).forEach((v, c) => (plain[y * stride + x * channels + c] = v));
    const rows = [];
    for (let y = 0; y < height; y++) {
      const filter = y % 5;
      const row = Buffer.alloc(stride + 1);
      row[0] = filter;
      for (let i = 0; i < stride; i++) {
        const v = plain[y * stride + i];
        const a = i >= channels ? plain[y * stride + i - channels] : 0;
        const b = y > 0 ? plain[(y - 1) * stride + i] : 0;
        const c = y > 0 && i >= channels ? plain[(y - 1) * stride + i - channels] : 0;
        const p = a + b - c;
        const paeth = Math.abs(p - a) <= Math.abs(p - b) && Math.abs(p - a) <= Math.abs(p - c) ? a : Math.abs(p - b) <= Math.abs(p - c) ? b : c;
        const predicted = [0, a, b, (a + b) >> 1, paeth][filter];
        row[i + 1] = (v - predicted) & 0xff;
      }
      rows.push(row);
    }
    const chunk = (type, data) => {
      const len = Buffer.alloc(4);
      len.writeUInt32BE(data.length);
      return Buffer.concat([len, Buffer.from(type, 'latin1'), data, Buffer.alloc(4)]);
    };
    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(width, 0);
    ihdr.writeUInt32BE(height, 4);
    ihdr[8] = 8;
    ihdr[9] = channels === 4 ? 6 : 2;
    return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(Buffer.concat(rows))), chunk('IEND', Buffer.alloc(0))]);
  }

  it('undoes every row filter, RGB and RGBA', () => {
    const colour = (x, y) => [(x * 37 + y * 11) & 255, (x * 5 + y * 53) & 255, (x * y) & 255, 255];
    for (const channels of [3, 4]) {
      const png = parsePng(pngWithFilters(13, 10, channels, colour));
      assert.equal(png.width, 13);
      assert.equal(png.height, 10);
      const px = [0, 0, 0];
      for (let y = 0; y < 10; y++) {
        for (let x = 0; x < 13; x++) {
          png.pixel(x, y, px);
          assert.deepEqual(px, colour(x, y).slice(0, 3), `${channels} (${x},${y})`);
        }
      }
    }
  });

  it('refuses what Chrome does not produce', () => {
    assert.equal(parsePng(Buffer.from('not a png')), null);
    assert.equal(parsePng(null), null);
    const ok = pngWithFilters(2, 2, 3, () => [0, 0, 0]);
    const sixteenBit = Buffer.from(ok);
    sixteenBit[24] = 16; // IHDR bit depth
    assert.equal(parsePng(sixteenBit), null);
  });
});
