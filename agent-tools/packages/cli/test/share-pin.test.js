import assert from 'node:assert/strict';
import { afterEach, beforeEach, describe, it } from 'node:test';
import vm from 'node:vm';
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
import { inspectPoint, padded, resolveWebPin, spotAround } from '../src/share/pin-web.js';
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

  it('takes a whole-screen probe, and drops shapes it does not read (§15.6, §15.9)', () => {
    assert.deepEqual(parseProbe({ probeId: 'p8', screen: true }), { probeId: 'p8', screen: true });
    for (const bad of [
      { probeId: 'p8', screen: true, x: 0.5, y: 0.5 },
      { probeId: 'p8', screen: true, x: 0.5 },
      { probeId: 'p8', screen: 'yes' },
      { probeId: 'x8', screen: true },
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

  it('takes a dragged rectangle inside the screen, and nothing else with it (§15.4)', () => {
    const rect = { x: 0.1, y: 0.2, w: 0.3, h: 0.4 };
    assert.deepEqual(parseProbe({ probeId: 'p9', rect }), { probeId: 'p9', rect });
    assert.deepEqual(parseProbe({ probeId: 'p9', rect: { x: 0.5, y: 0, w: 0.5, h: 1 } }), { probeId: 'p9', rect: { x: 0.5, y: 0, w: 0.5, h: 1 } }, 'to the edge');
    assert.deepEqual(parseProbe({ probeId: 'p9', rect: { x: 0, y: 0, w: 1, h: 1, extra: 1 } }), { probeId: 'p9', rect: { x: 0, y: 0, w: 1, h: 1 } });
    for (const bad of [
      { x: -0.1, y: 0.2, w: 0.3, h: 0.4 },
      { x: 0.1, y: -0.01, w: 0.3, h: 0.4 },
      { x: 0.1, y: 0.2, w: 0, h: 0.4 },
      { x: 0.1, y: 0.2, w: 0.3, h: -0.1 },
      { x: 0.8, y: 0.2, w: 0.3, h: 0.4 },
      { x: 0.1, y: 0.7, w: 0.3, h: 0.4 },
      { x: 0.1, y: 0.2, w: 0.3 },
      { x: '0.1', y: 0.2, w: 0.3, h: 0.4 },
      { x: NaN, y: 0.2, w: 0.3, h: 0.4 },
      { x: 0.1, y: 0.2, w: Infinity, h: 0.4 },
      [0.1, 0.2, 0.3, 0.4],
      null,
      'x',
    ]) {
      assert.equal(parseProbe({ probeId: 'p9', rect: bad }), null, JSON.stringify(bad));
    }
    assert.equal(parseProbe({ probeId: 'p9', rect, screen: true }), null);
    assert.equal(parseProbe({ probeId: 'p9', rect, x: 0.5 }), null);
    assert.equal(parseProbe({ probeId: 'x9', rect }), null);
    // A rectangle that could not be read still says where it was: its centre.
    assert.deepEqual(unresolved({ probeId: 'p9', rect }, 'TIMEOUT'), { probeId: 'p9', ok: false, reason: 'TIMEOUT', point: { x: 0.25, y: 0.4 } });
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

describe('fitting a dragged area’s result (§15.9)', () => {
  const REGION = { x: 0.1, y: 0.2, w: 0.5, h: 0.3 };
  const ELEMENT = { type: 'p', text: '요금제', bounds: { x: 0.12, y: 0.3, w: 0.3, h: 0.05 } };
  const SCREEN = { kind: 'screen', type: 'screen', bounds: { x: 0, y: 0, w: 1, h: 1 } };
  const region = ({ members = [], near, steps = 1 } = {}) => ({
    probeId: 'p5',
    ok: true,
    screen: { label: '/plans' },
    point: { x: 0.35, y: 0.35 },
    element: ELEMENT,
    ancestors: [{ type: 'section' }],
    components: [{ name: 'PlanCard' }],
    sourceHints: [{ file: 'src/PlanCard.tsx', reason: 'COMPONENT' }],
    scopes: [
      { kind: 'region', type: 'region', bounds: REGION, members: Math.min(members.length, 12) },
      ...Array.from({ length: steps }, (_, i) => ({ kind: 'group', type: 'section', id: 's' + i, bounds: { x: 0, y: 0, w: 0.6 + i * 0.05, h: 0.6 } })),
      SCREEN,
    ],
    defaultScope: 0,
    members,
    empty: members.length === 0,
    ...(near ? { near } : {}),
  });
  const member = (i, text) => ({ type: 'a', text, id: 'm' + i, bounds: { x: 0.1, y: 0.2 + i * 0.01, w: 0.1, h: 0.01 } });

  it('carries a sentence step first and the look-alikes through from the page to the result (§15.3, §15.7)', async () => {
    const b = { x: 0.1, y: 0.2, w: 0.2, h: 0.2 };
    const raw = {
      ok: true,
      screen: { label: '/plans' },
      view: { width: 1000, height: 800, dpr: 1, pageX: 0, pageY: 0 },
      element: { type: 'p', text: '지금은 무료입니다. 팀원을 3명까지 초대할 수 있습니다.', bounds: { x: 0, y: 0.1, w: 0.8, h: 0.05 } },
      rect: { x: 0, y: 80, width: 800, height: 40 },
      ancestors: [],
      components: [],
      scopes: [
        { kind: 'text', type: 'text', text: '팀원을 3명까지 초대할 수 있습니다.', bounds: { x: 0.3, y: 0.1, w: 0.3, h: 0.05 } },
        { kind: 'element', type: 'p', bounds: { x: 0, y: 0.1, w: 0.8, h: 0.05 } },
        { kind: 'group', type: 'li', selector: 'li.plan-card', bounds: b, peers: { count: 3, bounds: [b, b, b, { x: 9 }] } },
        SCREEN,
      ],
      defaultScope: 1,
    };
    const page = { send: async (method) => (method === 'Runtime.evaluate' ? { result: { value: raw } } : {}) };
    const { result } = await resolveWebPin({ page, probe: { probeId: 'p1', x: 0.4, y: 0.12 }, project: null, origin: null, findHints: async () => [] });
    assert.deepEqual(result.scopes.map((sc) => sc.kind), ['text', 'element', 'group', 'screen']);
    assert.equal(result.defaultScope, 1);
    assert.equal(result.scopes[0].text, '팀원을 3명까지 초대할 수 있습니다.');
    assert.deepEqual(result.scopes[2].peers, { count: 3, bounds: [b, b, b] }, 'a box outside the screen is dropped');
  });

  it('drops the look-alikes’ boxes before anything else, keeping their count (§15.9)', () => {
    const box = { x: 0.1, y: 0.2, w: 0.2, h: 0.2 };
    const result = region({ members: [member(0, 'x'.repeat(40000))] });
    result.scopes[1] = { ...result.scopes[1], peers: { count: 20, bounds: Array.from({ length: 20 }, () => box) } };
    const fitted = JSON.parse(fitResult(result));
    const step = fitted.scopes.find((sc) => sc.peers);
    assert.deepEqual(step.peers, { count: 20, bounds: [] });
  });

  it('keeps the element step when a sentence step comes first, widest containers going first (§15.7)', () => {
    const sentence = { kind: 'text', type: 'text', text: '팀원을 3명까지 초대할 수 있습니다.', bounds: { x: 0.3, y: 0.1, w: 0.3, h: 0.05 } };
    const result = {
      probeId: 'p6', ok: true, point: { x: 0.4, y: 0.12 },
      element: { type: 'p', text: 'y'.repeat(200), bounds: { x: 0, y: 0.1, w: 0.8, h: 0.05 } },
      components: [{ name: 'x'.repeat(100) }], ancestors: [], sourceHints: [],
      scopes: [sentence, { kind: 'element', type: 'p', text: 'x', bounds: { x: 0, y: 0.1, w: 0.8, h: 0.05 } },
        { kind: 'group', type: 'section', text: 'z'.repeat(20000), bounds: { x: 0, y: 0, w: 0.9, h: 0.6 } }, SCREEN],
      defaultScope: 1,
    };
    const fitted = JSON.parse(fitResult(result));
    assert.deepEqual(fitted.scopes.map((sc) => sc.kind), ['text', 'element', 'screen']);
    assert.equal(fitted.defaultScope, 1);
  });

  it('drops the members’ words first, keeping everything else', () => {
    const members = Array.from({ length: 12 }, (_, i) => member(i, String(i).repeat(1500)));
    const near = [{ dir: 'up', type: 'h2', text: '요금제', bounds: ELEMENT.bounds, distance: 0.1 }];
    const fitted = JSON.parse(fitResult(region({ members, near })));
    assert.equal(fitted.members.length, 12);
    assert.ok(fitted.members.every((m) => m.text === undefined && m.id && m.bounds));
    assert.deepEqual(fitted.near, near, 'the neighbours go only after the words');
    assert.equal(fitted.scopes.length, 3);
    assert.deepEqual(fitted.ancestors, [{ type: 'section' }]);
  });

  it('then the neighbours, then the list — its count stays on the region step — then the outer steps', () => {
    const near = [{ dir: 'up', type: 'h2', text: 'x'.repeat(20000), bounds: ELEMENT.bounds, distance: 0.1 }];
    let fitted = JSON.parse(fitResult(region({ members: [member(0, '바꾸기')], near })));
    assert.equal(fitted.near, undefined);
    assert.deepEqual(fitted.members, [{ type: 'a', id: 'm0', bounds: member(0).bounds }]);

    const huge = Array.from({ length: 3000 }, (_, i) => member(i));
    fitted = JSON.parse(fitResult(region({ members: huge, steps: 4 })));
    assert.ok(Buffer.byteLength(JSON.stringify(fitted)) <= MAX_RESULT_JSON_BYTES);
    assert.equal(fitted.members, undefined);
    assert.equal(fitted.empty, false, 'not empty: the list was let go of, not found empty');
    assert.deepEqual(fitted.scopes[0], { kind: 'region', type: 'region', bounds: REGION, members: 12 });
    assert.equal(fitted.scopes.length, 6, 'the list went before any step');
    assert.deepEqual(fitted.element, ELEMENT);
    assert.deepEqual(fitted.components, [{ name: 'PlanCard' }]);
  });

  it('keeps the region step and `element` to the last', () => {
    const fat = { type: 'section', text: 'y'.repeat(200), bounds: { x: 0, y: 0, w: 0.5, h: 0.5 } };
    const result = region({ members: Array.from({ length: 3000 }, (_, i) => member(i, 'z'.repeat(60))), steps: 4 });
    result.ancestors = Array.from({ length: 5 }, () => ({ ...fat, text: 'a'.repeat(9000) }));
    result.components = Array.from({ length: 8 }, (_, i) => ({ name: 'C' + i, file: 'f'.repeat(9000) }));
    const fitted = JSON.parse(fitResult(result));
    assert.ok(Buffer.byteLength(JSON.stringify(fitted)) <= MAX_RESULT_JSON_BYTES);
    assert.equal(fitted.ok, true);
    assert.deepEqual(fitted.scopes, [{ kind: 'region', type: 'region', bounds: REGION, members: 12 }, SCREEN]);
    assert.deepEqual(fitted.element, { type: 'p', bounds: ELEMENT.bounds });
    assert.deepEqual(fitted.point, { x: 0.35, y: 0.35 });
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

  /** A small stand-in for the DOM: just what inspectPoint reads. A node joins its parent's children. */
  function el(localName, { id = '', attrs = {}, text = '', rect = null, parent = null, classes = [], extra = {} } = {}) {
    const node = {
      nodeType: 1,
      localName,
      id,
      innerText: text,
      textContent: text,
      parentElement: parent,
      children: [],
      classList: classes,
      getAttribute: (name) => attrs[name] ?? null,
      getBoundingClientRect: () => rect ?? { left: 0, top: 0, right: 0, bottom: 0 },
      closest: () => null,
      getRootNode: () => ({}),
      ...extra,
    };
    if (parent?.children) parent.children.push(node);
    return node;
  }
  /** Words written directly in an element (a text node child), as `childNodes` shows them. */
  const own = (text) => ({ childNodes: [{ nodeType: 3, nodeValue: text }] });

  function page(hit, { width = 1000, height = 800 } = {}) {
    for (const k of ['window', 'document', 'location', 'performance']) saved[k] = globalThis[k];
    const body = el('body');
    globalThis.document = { body, documentElement: el('html'), title: 'Vite App', elementFromPoint: () => hit };
    globalThis.window = { innerWidth: width, innerHeight: height, devicePixelRatio: 2, scrollX: 0, scrollY: 120 };
    globalThis.location = { pathname: '/settings', href: 'http://localhost:5173/settings?tab=1#top' };
    return body;
  }

  it('a tap on the app’s frame between things is an empty spot, with what is around it', () => {
    const body = page(null);
    const app = el('div', { text: '프로필 저장 요금제', rect: { left: 0, top: 0, right: 1000, bottom: 800 }, parent: body });
    const save = el('button', { text: '저장', rect: { left: 100, top: 200, right: 200, bottom: 240 }, parent: app });
    globalThis.document.elementFromPoint = () => app;
    globalThis.document.body.children = [app];
    const out = inspectPoint({ x: 0.15, y: 0.6, limit: LIMIT });
    assert.equal(out.ok, false);
    assert.equal(out.reason, 'NO_ELEMENT');
    assert.equal(JSON.stringify(out).includes('프로필 저장 요금제'), false, 'not the whole page as one element');
    assert.ok(Array.isArray(out.near));
    void save;
  });

  it('never sends what was typed into an editable area, only what names it', () => {
    const editor = el('div', {
      attrs: { role: 'textbox', 'aria-placeholder': '메모를 쓰세요' },
      text: '내 카드 번호는 1234',
      rect: { left: 10, top: 10, right: 400, bottom: 200 },
      extra: { isContentEditable: true },
    });
    const body = page(editor);
    editor.parentElement = body;
    const out = inspectPoint({ x: 0.1, y: 0.05, limit: LIMIT });
    assert.equal(out.element.text, '메모를 쓰세요');
    assert.equal(JSON.stringify(out).includes('1234'), false);
  });

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

  // ── the same thing, all of them (§15.3, case 3) ──────────────────────────────
  /** ul > li.plan-card ×3 (one off screen) > button "고르기", and a bare div beside them. */
  function cardsPage() {
    const list = el('ul', { classes: ['plans'], rect: box(0, 100, 1000, 500) });
    const cards = [0, 1, 2].map((i) => el('li', { classes: ['plan-card'], rect: box(20 + i * 330, 120, 320 + i * 330, 480), parent: list }));
    const offscreen = el('li', { classes: ['plan-card'], rect: box(0, 900, 300, 1200), parent: list });
    const stray = el('div', { rect: box(0, 600, 100, 650), parent: list });
    const button = el('button', { text: '고르기', rect: box(370, 420, 470, 460), parent: cards[1] });
    list.parentElement = page(button);
    return { cards, offscreen, stray };
  }

  it('says how many look the same as a step, and where those on screen are (§15.3 peers)', () => {
    cardsPage();
    const out = inspectPoint({ x: 0.42, y: 0.55, limit: LIMIT });
    const card = out.scopes.find((s) => s.type === 'li');
    assert.equal(card.peers.count, 4, 'every li.plan-card under the list, the one off screen too');
    assert.equal(card.peers.bounds.length, 3, 'only the boxes on screen are drawn');
    assert.equal(out.scopes[0].peers, undefined, 'one button per card: the button has no look-alikes');
    assert.equal(out.scopes.find((s) => s.type === 'ul').peers, undefined);
  });

  it('counts the cards one component drew as the same, whatever their classes', () => {
    function PlanCard() {}
    const host = (i) => ({ __reactFiber$k1: { tag: 5, type: 'div', return: { tag: 0, type: PlanCard, return: { tag: 5, type: 'section' } } }, id: '' });
    const grid = el('section', { attrs: { 'aria-label': '요금제' }, rect: box(0, 0, 1000, 600) });
    const cards = [0, 1, 2].map((i) => el('div', { classes: [`plan-${i}`], rect: box(i * 330, 0, i * 330 + 300, 400), parent: grid, extra: host(i) }));
    const button = el('button', { text: '고르기', rect: box(30, 300, 130, 340), parent: cards[0] });
    grid.parentElement = page(button);
    const out = inspectPoint({ x: 0.05, y: 0.4, limit: LIMIT });
    const step = out.scopes.find((s) => s.component === 'PlanCard');
    assert.equal(step.kind, 'component');
    assert.equal(step.peers.count, 3);
  });

  it('counts the look-alikes of a same-size item merged into the element step (li > a menus)', () => {
    const nav = el('ul', { attrs: { 'aria-label': '주 메뉴' }, rect: box(600, 0, 1000, 60) });
    const items = [0, 1, 2].map((i) => el('li', { rect: box(600 + i * 120, 10, 700 + i * 120, 50), parent: nav }));
    const link = el('a', { text: '주문', attrs: { href: '#' }, rect: box(720, 10, 820, 50), parent: items[1] });
    nav.parentElement = page(link);
    const out = inspectPoint({ x: 0.77, y: 0.0375, limit: LIMIT });
    assert.equal(out.scopes[0].kind, 'element');
    assert.equal(out.scopes[0].peers.count, 3, 'the links have no siblings; the items they fill do');
  });

  it('gives bare boxes no look-alikes: a div is like every other div', () => {
    const wrap = el('section', { id: 'wrap', rect: box(0, 0, 600, 400) });
    const a = el('div', { rect: box(0, 0, 300, 200), parent: wrap, attrs: { role: 'group' } });
    el('div', { rect: box(300, 0, 600, 200), parent: wrap, attrs: { role: 'group' } });
    const button = el('button', { text: '확인', rect: box(10, 10, 110, 50), parent: a });
    wrap.parentElement = page(button);
    const out = inspectPoint({ x: 0.05, y: 0.03, limit: LIMIT });
    assert.equal(out.scopes.some((s) => s.peers), false);
  });

  // ── a sentence under the point (§15.7, case 8) ───────────────────────────────
  function sentencePage({ inField = false, off = false } = {}) {
    const p = el('p', { text: '지금은 무료 요금제를 쓰고 있습니다. 팀원을 3명까지 초대할 수 있습니다.', rect: box(0, 100, 800, 140), attrs: { role: 'note' } });
    const data = '지금은 무료 요금제를 쓰고 있습니다. 팀원을 3명까지 초대할 수 있습니다.';
    const node = { nodeType: 3, data, parentElement: inField ? el('textarea', { rect: box(0, 100, 800, 140) }) : p };
    if (inField) node.parentElement.closest = () => node.parentElement;
    p.contains = (n) => n === node || inField;
    const body = page(p);
    p.parentElement = body;
    const line = { left: 300, top: 100, right: 620, bottom: 140 };
    globalThis.document.caretPositionFromPoint = () => ({ offsetNode: node, offset: data.indexOf('3명') });
    globalThis.document.createRange = () => {
      const range = {
        setStart: (n, o) => (range.s = o),
        setEnd: (n, o) => (range.e = o),
        getClientRects: () => [off ? { left: 0, top: 0, right: 10, bottom: 10 } : line],
        getBoundingClientRect: () => line,
      };
      return range;
    };
    return { p, data };
  }

  it('offers the sentence under the point one step narrower than the element, which stays the default (§15.7)', () => {
    sentencePage();
    const out = inspectPoint({ x: 0.4, y: 0.15, limit: LIMIT });
    assert.equal(out.defaultScope, 1);
    assert.deepEqual(out.scopes[0], { kind: 'text', type: 'text', text: '팀원을 3명까지 초대할 수 있습니다.', bounds: { x: 0.3, y: 0.125, w: 0.32, h: 0.05 } });
    assert.equal(out.scopes[1].kind, 'element');
    assert.equal(out.element.type, 'note', 'what older readers take is still the element');
  });

  it('makes no sentence step inside a field, nor when the caret snapped to text away from the point', () => {
    sentencePage({ inField: true });
    let out = inspectPoint({ x: 0.4, y: 0.15, limit: LIMIT });
    assert.equal(out.scopes.some((s) => s.kind === 'text'), false);
    assert.equal(out.defaultScope, 0);
    sentencePage({ off: true });
    out = inspectPoint({ x: 0.4, y: 0.15, limit: LIMIT });
    assert.equal(out.scopes.some((s) => s.kind === 'text'), false);
  });

  /**
   * A plans page (1000×800): main > section.plan > (div#intro > h2 "요금제", p "지금은 <em>무료</em> …"),
   * "더 보기", "자세히", button > span "바꾸기", img "그래프"; an aside with "도움말"; a footer.
   * `hit` names what elementFromPoint returns.
   */
  function plansPage(hit = 'p') {
    const main = el('main', { rect: box(0, 0, 1000, 800) });
    const section = el('section', { classes: ['plan'], rect: box(100, 100, 600, 400), parent: main });
    const intro = el('div', { id: 'intro', rect: box(110, 110, 590, 230), parent: section });
    const h2 = el('h2', { text: '요금제', rect: box(120, 120, 400, 160), parent: intro, extra: own('요금제') });
    const p = el('p', { text: '지금은 무료 요금제를 쓰고 있습니다.', rect: box(120, 180, 580, 220), parent: intro, extra: own('지금은 ') });
    el('em', { text: '무료', rect: box(170, 180, 200, 220), parent: p, extra: own('무료') });
    // in the DOM before the button, on its row: reading order puts it after
    el('a', { text: '더 보기', rect: box(420, 310, 580, 360), parent: section, extra: own('더 보기') });
    el('a', { text: '자세히', rect: box(300, 300, 400, 340), parent: section, extra: own('자세히') });
    const button = el('button', { classes: ['change'], text: '바꾸기', rect: box(120, 300, 240, 340), parent: section });
    el('span', { text: '바꾸기', rect: box(130, 310, 230, 330), parent: button, extra: own('바꾸기') });
    el('img', { attrs: { alt: '그래프' }, rect: box(420, 300, 580, 400), parent: section });
    const aside = el('aside', { rect: box(700, 450, 950, 560), parent: main });
    el('a', { text: '도움말', rect: box(700, 500, 900, 540), parent: aside, extra: own('도움말') });
    el('footer', { text: '© Qeploy', rect: box(0, 700, 1000, 800), parent: main, extra: own('© Qeploy') });
    const nodes = { main, section, p, button };
    const body = page(hit === 'body' ? null : nodes[hit]);
    if (hit === 'body') globalThis.document.elementFromPoint = () => body;
    body.children.push(main);
    main.parentElement = body;
    return nodes;
  }
  const AREA = { x: 0.11, y: 0.1375, w: 0.48, h: 0.3 }; // 110..590 × 110..350 px

  it('reads a dragged area: the centre element as a tap there, what is mostly inside in reading order, the card around it (§15.4)', () => {
    plansPage('p');
    const out = inspectPoint({ rect: AREA, limit: LIMIT });
    assert.equal(out.ok, true);
    const tap = inspectPoint({ x: AREA.x + AREA.w / 2, y: AREA.y + AREA.h / 2, limit: LIMIT });
    assert.deepEqual(out.element, tap.element, 'the centre element exactly as a point there');
    assert.equal(out.element.type, 'p');
    assert.deepEqual(
      out.members.map((m) => `${m.type}:${m.text}`),
      // em: words inside words; span: part of its button; the image is only half inside; the button,
      // 자세히 and 더 보기 share a row (centres 320, 320, 335 within half of 40)
      ['h2:요금제', 'p:지금은 무료 요금제를 쓰고 있습니다.', 'button:바꾸기', 'a:자세히', 'a:더 보기']
    );
    assert.deepEqual(out.members[0], { type: 'h2', text: '요금제', bounds: { x: 0.12, y: 0.15, w: 0.28, h: 0.05 } });
    assert.equal(out.cut, undefined);
    assert.equal(out.near, undefined);
    // div#intro holds half the area; section.plan all of it; main is the screen already
    assert.deepEqual(out.scopes, [
      { kind: 'region', type: 'region', bounds: { x: 0.11, y: 0.1375, w: 0.48, h: 0.3 } },
      { kind: 'group', type: 'section', selector: 'main > section.plan', bounds: { x: 0.1, y: 0.125, w: 0.5, h: 0.375 } },
      SCREEN_STEP,
    ]);
    assert.equal(out.defaultScope, 0);
  });

  it('lists twelve at most, looks into open shadow roots, and names a field by its label, never its value', () => {
    const body = page(null);
    const list = el('ul', { rect: box(0, 0, 1000, 400) });
    for (let i = 14; i >= 0; i--) {
      const x = (i % 5) * 200;
      const y = Math.floor(i / 5) * 100;
      el('a', { text: '항목 ' + i, rect: box(x, y, x + 150, y + 40), parent: list, extra: own('항목 ' + i) });
    }
    const host = el('x-card', { rect: box(0, 400, 1000, 600) });
    const shadow = { children: [] };
    host.shadowRoot = shadow;
    shadow.children.push(el('button', { text: '구독', rect: box(20, 420, 120, 460) }));
    shadow.children.push(el('input', { attrs: { type: 'email', placeholder: '이메일' }, rect: box(200, 420, 500, 460), extra: { value: 'minji@example.com', labels: [] } }));
    body.children.push(list, host);
    globalThis.document.elementFromPoint = () => body;

    const all = inspectPoint({ rect: { x: 0, y: 0, w: 1, h: 0.75 }, limit: LIMIT });
    assert.equal(all.members.length, 12);
    assert.deepEqual(all.members.map((m) => m.text), Array.from({ length: 12 }, (_, i) => '항목 ' + i));
    assert.equal(all.element, undefined, 'the centre is the page background');

    const lower = inspectPoint({ rect: { x: 0, y: 0.5, w: 1, h: 0.25 }, limit: LIMIT });
    assert.deepEqual(lower.members.map((m) => `${m.type}:${m.text}`), ['button:구독', 'input:이메일']);
    assert.doesNotMatch(JSON.stringify(lower), /minji/);
  });

  it('says what is around an empty area and an empty tap: the nearest named thing on each side', () => {
    plansPage('body');
    // 100..600 × 420..680: between the card and the footer
    const out = inspectPoint({ rect: { x: 0.1, y: 0.525, w: 0.5, h: 0.325 }, limit: LIMIT });
    assert.equal(out.ok, true);
    assert.equal(out.element, undefined);
    assert.deepEqual(out.members, []);
    assert.deepEqual(out.scopes, [{ kind: 'region', type: 'region', bounds: { x: 0.1, y: 0.525, w: 0.5, h: 0.325 } }, SCREEN_STEP]);
    assert.deepEqual(
      out.near.map((n) => [n.dir, n.type, n.text, n.distance]),
      [
        ['up', 'img', '그래프', 0.025],
        ['down', 'footer', '© Qeploy', 0.025],
        ['right', 'a', '도움말', 0.1],
      ]
    );
    assert.deepEqual(out.near[0].bounds, { x: 0.42, y: 0.375, w: 0.16, h: 0.125 });

    // A tap there finds nothing, and says what is around it (§15.4): across x = 350, 자세히 is nearest above.
    const tap = inspectPoint({ x: 0.35, y: 0.5625, limit: LIMIT });
    assert.equal(tap.reason, 'NO_ELEMENT');
    assert.deepEqual(tap.near.map((n) => [n.dir, n.text, n.distance]), [['up', '자세히', 0.1375], ['down', '© Qeploy', 0.3125]]);
  });

  it('answers a dragged area with the centre element when its 400 ms are up', () => {
    plansPage('button');
    let reads = 0;
    globalThis.performance = { now: () => (reads++ === 0 ? 1000 : 1401) };
    const out = inspectPoint({ rect: AREA, limit: LIMIT });
    assert.equal(out.ok, true);
    assert.equal(out.element.type, 'button');
    assert.equal(out.element.text, '바꾸기');
    assert.deepEqual(out.members, []);
    assert.equal(out.cut, true, 'cut short: not known to be empty');
    assert.equal(out.near, undefined);
    assert.deepEqual(out.scopes.map((s) => s.kind), ['region', 'screen']);
  });

  it('keeps the element when reading the area fails', () => {
    const { section } = plansPage('p');
    section.getBoundingClientRect = () => {
      throw new Error('detached');
    };
    const out = inspectPoint({ rect: AREA, limit: LIMIT });
    assert.equal(out.ok, true);
    assert.equal(out.element.type, 'p');
    assert.equal(out.scopes, undefined);
    assert.equal(out.members, undefined);
  });

  it('runs from its own source alone, as the page gets it: nothing from this module', () => {
    plansPage('p');
    const context = vm.createContext({ window: globalThis.window, document: globalThis.document, location: globalThis.location });
    // Through JSON, as returnByValue does (and out of the other realm's arrays)
    const run = (args) => JSON.parse(JSON.stringify(vm.runInContext(`(${inspectPoint.toString()})(${JSON.stringify({ ...args, limit: LIMIT })})`, context)));
    const area = run({ rect: AREA });
    assert.equal(area.element.type, 'p');
    assert.equal(area.members.length, 5);
    assert.equal(area.scopes.length, 3);
    globalThis.document.elementFromPoint = () => globalThis.document.body;
    assert.deepEqual(run({ x: 0.35, y: 0.5625 }).near.map((n) => n.dir), ['up', 'down']);
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
