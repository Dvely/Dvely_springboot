import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { buildLadder, buildRegion, nearAround, readingOrder, scopeIdsOf, SCREEN_STEP } from '../src/share/scope.js';
import { describeAndroidPoint, parseUiDump } from '../src/share/android/pin.js';
import { describeIosPoint, parseAxTree } from '../src/share/ios/pin.js';
import { clearListings, findSourceHints } from '../src/share/source-hints.js';
import { mkdirSync, mkdtempSync, realpathSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { ACME_TREE } from './fixtures/ios-acme.js';

/** A chain of nodes, innermost first: `{name, box: [x, y, w, h] as screen ratios, ...what describe says}` */
function chain(...specs) {
  let parent = null;
  const nodes = [];
  for (const spec of specs.slice().reverse()) {
    const node = { ...spec, parent };
    nodes.unshift(node);
    parent = node;
  }
  return nodes;
}
const boundsOf = (n) => (n.box ? { x: n.box[0], y: n.box[1], w: n.box[2], h: n.box[3] } : null);
const describeAll = (n) => (n.say === false ? null : { kind: n.kind ?? 'group', type: n.type ?? 'div', id: n.id, text: n.text, component: n.component });

describe('scope ladder (§15.3, #694 PR-3)', () => {
  it('goes from the element outward to the screen, skipping nodes that say nothing or have no box', () => {
    const [button] = chain(
      { name: 'button' },
      { name: 'wrapper', say: false, box: [0.1, 0.4, 0.5, 0.2] },
      { name: 'card', type: 'section', id: 'plan', box: [0.05, 0.3, 0.9, 0.3] },
      { name: 'offscreen', type: 'aside', box: null },
      { name: 'page', type: 'main', box: [0, 0, 1, 0.9] }
    );
    const steps = buildLadder({
      element: { type: 'button', text: '바꾸기', bounds: { x: 0.1, y: 0.45, w: 0.2, h: 0.05 } },
      node: button,
      boundsOf,
      describe: describeAll,
    });
    assert.deepEqual(steps.map((s) => s.kind + ':' + s.type), ['element:button', 'group:section', 'screen:screen']);
    assert.equal(steps[1].id, 'plan');
    assert.deepEqual(steps.at(-1), SCREEN_STEP);
    assert.notEqual(steps.at(-1), SCREEN_STEP, 'a copy, not the frozen constant');
  });

  it('merges a box within 10% of the one below it, keeping the more telling name', () => {
    const [button] = chain(
      { name: 'button' },
      { name: 'SaveButton root', kind: 'component', type: 'div', component: 'SaveButton', box: [0.1, 0.5, 0.2, 0.051] },
      { name: 'form', type: 'form', box: [0.05, 0.3, 0.9, 0.4] },
      { name: 'ProfileForm root', kind: 'component', type: 'div', component: 'ProfileForm', id: 'profile', box: [0.05, 0.3, 0.9, 0.41] }
    );
    const steps = buildLadder({
      element: { type: 'button', text: '저장', bounds: { x: 0.1, y: 0.5, w: 0.2, h: 0.05 } },
      node: button,
      boundsOf,
      describe: describeAll,
    });
    assert.equal(steps.length, 3);
    assert.equal(steps[0].kind, 'element', 'the element step stays the element');
    assert.equal(steps[0].component, 'SaveButton');
    assert.equal(steps[0].id, undefined);
    assert.deepEqual([steps[1].kind, steps[1].type, steps[1].component, steps[1].id], ['component', 'form', 'ProfileForm', 'profile']);
  });

  it('drops boxes of 80% of the screen or more, and stops at six steps with the screen last', () => {
    const specs = [{ name: 'el' }];
    for (let i = 1; i <= 8; i++) specs.push({ name: 'g' + i, type: 'section', box: [0, 0, 0.3 + i * 0.07, 0.3 + i * 0.07] });
    const steps = buildLadder({ element: { type: 'a', bounds: { x: 0, y: 0, w: 0.1, h: 0.1 } }, node: chain(...specs)[0], boundsOf, describe: describeAll });
    assert.equal(steps.length, 6);
    assert.equal(steps.at(-1).kind, 'screen');
    assert.ok(steps.slice(1, -1).every((s) => s.bounds.w * s.bounds.h < 0.8));
  });

  it('never widens to a smaller box (SwiftUI puts a section id on a box smaller than its row)', () => {
    const [el] = chain(
      { name: 'switch row' },
      { name: 'section row box', type: 'Group', id: 'securitySection', box: [0.08, 0.44, 0.84, 0.032] },
      { name: 'card', type: 'Group', id: 'profileForm', box: [0.04, 0.2, 0.92, 0.5] }
    );
    const steps = buildLadder({ element: { type: 'CheckBox', bounds: { x: 0.04, y: 0.42, w: 0.92, h: 0.06 } }, node: el, boundsOf, describe: describeAll });
    assert.deepEqual(steps.map((s) => s.id ?? s.kind), ['element', 'profileForm', 'screen']);
  });

  it('cuts step text to 60 characters', () => {
    const [el] = chain({ name: 'el' }, { name: 'g', type: 'section', text: '가'.repeat(100), box: [0, 0, 0.5, 0.5] });
    const steps = buildLadder({ element: { type: 'p', text: '나'.repeat(100) }, node: el, boundsOf, describe: describeAll });
    assert.equal(Array.from(steps[0].text).length, 60);
    assert.equal(Array.from(steps[1].text).length, 60);
  });
});

/**
 * A tree in tree order (a parent before its children) from a nested spec:
 * `{name, box: [x, y, w, h] as screen ratios, member?: {type, text?, id?, interactive?}, step?: {...}, kids?: [...]}`.
 */
function tree(spec, parent = null, out = []) {
  const node = { name: spec.name, box: spec.box, member: spec.member, step: spec.step, parent };
  out.push(node);
  for (const kid of spec.kids ?? []) tree(kid, node, out);
  return out;
}
const regionOf = (nodes, rect) =>
  buildRegion({ rect, nodes, boundsOf, describe: (n) => n.step ?? null, memberOf: (n) => n.member ?? null });
const named = (nodes, name) => nodes.find((n) => n.name === name);

describe('dragged area (§15.4, #694 PR-4)', () => {
  // A plan card in the top half; a footer at the bottom; a help link on the right.
  const page = () =>
    tree({
      name: 'page', box: [0, 0, 1, 1], step: { kind: 'group', type: 'main' },
      kids: [
        {
          name: 'card', box: [0.1, 0.1, 0.5, 0.4], step: { kind: 'group', type: 'section', id: 'plan' },
          kids: [
            { name: 'title', box: [0.12, 0.12, 0.3, 0.05], member: { type: 'h2', text: '요금제' } },
            {
              name: 'body', box: [0.12, 0.2, 0.46, 0.05], member: { type: 'p', text: '지금은 무료 요금제를 쓰고 있습니다.' },
              kids: [{ name: 'strong', box: [0.2, 0.2, 0.05, 0.05], member: { type: 'strong', text: '무료' } }],
            },
            {
              name: 'button', box: [0.12, 0.38, 0.12, 0.05], member: { type: 'button', text: '바꾸기', interactive: true },
              kids: [{ name: 'label', box: [0.13, 0.39, 0.1, 0.03], member: { type: 'span', text: '바꾸기' } }],
            },
            { name: 'more', box: [0.42, 0.39, 0.16, 0.06], member: { type: 'a', text: '더 보기', interactive: true } },
            { name: 'details', box: [0.3, 0.38, 0.1, 0.05], member: { type: 'a', text: '자세히', interactive: true } },
            { name: 'chart', box: [0.42, 0.38, 0.16, 0.12], member: { type: 'img', text: '그래프' } },
          ],
        },
        { name: 'help', box: [0.7, 0.62, 0.2, 0.05], member: { type: 'a', text: '도움말', interactive: true } },
        { name: 'footer', box: [0, 0.875, 1, 0.125], member: { type: 'footer', text: '© Qeploy' } },
        { name: 'backdrop', box: [0, 0, 1, 0.9], member: { type: 'div', text: '배경' } },
      ],
    });

  it('lists what is mostly inside, in reading order; a part of a kept control goes, words inside words stay', () => {
    const nodes = page();
    // 0.11..0.59 × 0.11..0.44: the chart is 50% inside (out), "더 보기" 83% (in)
    const r = regionOf(nodes, { x: 0.11, y: 0.11, w: 0.48, h: 0.33 });
    assert.deepEqual(
      r.members.map((m) => `${m.type}:${m.text}`),
      // button, 자세히 and 더 보기 share a row (centres 0.405, 0.405, 0.42 within half the smaller height)
      ['h2:요금제', 'p:지금은 무료 요금제를 쓰고 있습니다.', 'strong:무료', 'button:바꾸기', 'a:자세히', 'a:더 보기']
    );
    assert.equal(r.members.some((m) => m.type === 'span'), false, 'the label is part of its button');
    assert.equal(r.empty, false);
    assert.equal(r.near, undefined, 'neighbours only for an empty area');
    assert.deepEqual(r.members[0], { type: 'h2', text: '요금제', bounds: { x: 0.12, y: 0.12, w: 0.3, h: 0.05 } });
    assert.deepEqual(r.scopes[0], { kind: 'region', type: 'region', bounds: { x: 0.11, y: 0.11, w: 0.48, h: 0.33 }, members: 6 });
  });

  it('starts the wider steps at the smallest container holding 90% of the area, then widens by the ladder rules', () => {
    const nodes = page();
    const r = regionOf(nodes, { x: 0.11, y: 0.11, w: 0.48, h: 0.33 });
    // the card holds all of it; the page is the screen already
    assert.deepEqual(r.scopes.map((s) => s.kind + ':' + (s.id ?? s.type)), ['region:region', 'group:plan', 'screen:screen']);
    assert.deepEqual(scopeIdsOf(r.scopes), [{ id: 'plan', scope: 1 }], 'the region step has no id; the card is searched');

    // A box holding 85% of a wider area is not the container; the card (100%) is.
    const wide = tree({
      name: 'card', box: [0, 0, 0.6, 0.6], step: { kind: 'group', type: 'section', id: 'card' },
      kids: [{ name: 'inner', box: [0, 0, 0.6, 0.51], step: { kind: 'group', type: 'div', id: 'inner' } }],
    });
    assert.deepEqual(regionOf(wide, { x: 0, y: 0, w: 0.6, h: 0.6 }).scopes.map((s) => s.id ?? s.kind), ['region', 'card', 'screen']);

    // Nearly the rectangle itself is still its own step: nothing merges into the region.
    const same = tree({ name: 'card', box: [0.1, 0.1, 0.4, 0.4], step: { kind: 'group', type: 'section', id: 'card' } });
    assert.deepEqual(regionOf(same, { x: 0.1, y: 0.1, w: 0.4, h: 0.41 }).scopes.map((s) => s.id ?? s.kind), ['region', 'card', 'screen']);
  });

  it('merges same-size containers, never narrows, keeps six steps with the region and the screen', () => {
    const specs = [{ name: 'c0', box: [0, 0, 0.2, 0.2], step: { kind: 'group', type: 'div', id: 'c0' } }];
    // c1 nearly c0's size (merged into it), c2 smaller (skipped), then ever wider boxes
    specs.push({ name: 'c1', box: [0, 0, 0.2, 0.21], step: { kind: 'component', type: 'div', component: 'Card' } });
    specs.push({ name: 'c2', box: [0, 0, 0.19, 0.15], step: { kind: 'group', type: 'div', id: 'c2' } });
    for (let i = 3; i <= 9; i++) specs.push({ name: 'c' + i, box: [0, 0, 0.2 + i * 0.07, 0.2 + i * 0.07], step: { kind: 'group', type: 'section', id: 'c' + i } });
    // nest: c9 > c8 > … > c0
    let spec = null;
    for (const s of specs) spec = spec ? { ...s, kids: [spec] } : s;
    const r = regionOf(tree(spec), { x: 0.01, y: 0.01, w: 0.18, h: 0.18 });
    assert.equal(r.scopes.length, 6);
    assert.deepEqual(r.scopes.map((s) => s.id ?? s.kind), ['region', 'c0', 'c3', 'c4', 'c5', 'screen']);
    assert.deepEqual([r.scopes[1].kind, r.scopes[1].component], ['component', 'Card']);
  });

  it('keeps twelve members at most, in reading order', () => {
    const kids = [];
    for (let i = 14; i >= 0; i--) kids.push({ name: 'i' + i, box: [0.1 + (i % 5) * 0.15, 0.1 + Math.floor(i / 5) * 0.1, 0.1, 0.05], member: { type: 'a', text: '항목 ' + i, interactive: true } });
    const r = regionOf(tree({ name: 'list', box: [0, 0, 0.9, 0.5], kids }), { x: 0, y: 0, w: 0.9, h: 0.5 });
    assert.equal(r.members.length, 12);
    assert.deepEqual(r.members.map((m) => m.text), Array.from({ length: 12 }, (_, i) => '항목 ' + i));
    assert.equal(r.scopes[0].members, 12);
    assert.equal(Array.from(regionOf(tree({ name: 'x', box: [0, 0, 0.5, 0.1], member: { type: 'p', text: '가'.repeat(100) } }), { x: 0, y: 0, w: 0.5, h: 0.1 }).members[0].text).length, 60);
  });

  it('says what is around an empty area: one per side, the nearest, its gap; not the page behind', () => {
    const nodes = page();
    // 0.1..0.6 × 0.525..0.85: between the card and the footer
    const r = regionOf(nodes, { x: 0.1, y: 0.525, w: 0.5, h: 0.325 });
    assert.deepEqual(r.members, []);
    assert.equal(r.empty, true);
    assert.equal(r.scopes[0].members, 0);
    assert.deepEqual(r.near, [
      // the chart's bottom (0.5) is nearest above; the backdrop covers 90% and is no neighbour
      { dir: 'up', type: 'img', text: '그래프', bounds: { x: 0.42, y: 0.38, w: 0.16, h: 0.12 }, distance: 0.025 },
      { dir: 'down', type: 'footer', text: '© Qeploy', bounds: { x: 0, y: 0.875, w: 1, h: 0.125 }, distance: 0.025 },
      { dir: 'right', type: 'a', text: '도움말', bounds: { x: 0.7, y: 0.62, w: 0.2, h: 0.05 }, distance: 0.1 },
    ]);
  });

  it('treats a point as a zero-size area, and needs the neighbour across its line', () => {
    const nodes = page();
    const near = nearAround({ rect: { x: 0.35, y: 0.56, w: 0, h: 0 }, nodes, boundsOf, memberOf: (n) => n.member ?? null });
    // above x = 0.35: 자세히 (0.30..0.40) ends at 0.43; the button (0.12..0.24) is not across the line
    assert.deepEqual(near.map((n) => [n.dir, n.text, n.distance]), [['up', '자세히', 0.13], ['down', '© Qeploy', 0.315]]);
    // Equally near: the smaller box is the more particular one.
    const tie = tree({ name: 'root', box: [0, 0, 1, 1], kids: [
      { name: 'card', box: [0, 0, 0.6, 0.3], member: { type: 'section', id: 'card' } },
      { name: 'line', box: [0.1, 0.25, 0.3, 0.05], member: { type: 'p', text: '끝' } },
    ] });
    assert.deepEqual(nearAround({ rect: { x: 0.2, y: 0.4, w: 0, h: 0 }, nodes: tie, boundsOf, memberOf: (n) => n.member ?? null }).map((n) => n.text ?? n.id), ['끝']);
  });

  it('reads rows top to bottom, left to right', () => {
    const at = (x, y, h = 0.04) => ({ bounds: { x, y, w: 0.1, h } });
    const list = [at(0.5, 0.1), at(0.1, 0.11), at(0.3, 0.3), at(0.1, 0.32, 0.02)];
    assert.deepEqual(readingOrder(list).map((m) => [m.bounds.x, m.bounds.y]), [[0.1, 0.11], [0.5, 0.1], [0.1, 0.32], [0.3, 0.3]]);
  });
});

describe('android scope ladder (§15.3, #694 PR-3)', () => {
  // 540×1200: a list of plan cards (an app class), each pressable; a toolbar with an id.
  const DUMP = `<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0">
<node text="" resource-id="" class="android.widget.FrameLayout" package="com.example.pin" content-desc="" clickable="false" bounds="[0,0][540,1200]">
<node text="" resource-id="android:id/content" class="android.widget.FrameLayout" package="com.example.pin" content-desc="" clickable="false" bounds="[0,0][540,1200]">
<node text="" resource-id="com.example.pin:id/plans" class="androidx.recyclerview.widget.RecyclerView" package="com.example.pin" content-desc="" clickable="false" bounds="[0,200][540,900]">
<node text="" resource-id="" class="com.example.pin.ui.PlanCard" package="com.example.pin" content-desc="무료 요금제" clickable="true" bounds="[20,220][520,420]">
<node text="무료" resource-id="com.example.pin:id/plan_name" class="android.widget.TextView" package="com.example.pin" content-desc="" clickable="false" bounds="[40,240][200,280]" />
<node text="바꾸기" resource-id="com.example.pin:id/change" class="android.widget.Button" package="com.example.pin" content-desc="" clickable="true" bounds="[360,340][500,400]" />
</node>
</node>
</node>
</node>
</hierarchy>`;
  const SIZE = { width: 540, height: 1200 };

  it('the button, the card (an app class: a component), the list, the screen', () => {
    const r = describeAndroidPoint(parseUiDump(DUMP), { probeId: 'p1', x: 430 / 540, y: 370 / 1200 }, SIZE, { package: 'com.example.pin', activity: '.MainActivity' });
    assert.deepEqual(
      r.scopes.map((s) => [s.kind, s.type, s.id ?? s.component ?? s.text ?? '']),
      [
        ['element', 'Button', 'change'],
        ['component', 'PlanCard', 'PlanCard'],
        ['group', 'RecyclerView', 'plans'],
        ['screen', 'screen', ''],
      ]
    );
    assert.equal(r.scopes[1].text, '무료 요금제');
    assert.equal(r.defaultScope, 0);
    assert.ok(!JSON.stringify(r.scopes).includes('android:id'), 'the platform frame is no step');
  });

  it('a label inside the card means the card, and the ladder starts there', () => {
    const r = describeAndroidPoint(parseUiDump(DUMP), { probeId: 'p2', x: 100 / 540, y: 260 / 1200 }, SIZE, null);
    assert.equal(r.element.type, 'PlanCard');
    assert.deepEqual(r.scopes.map((s) => s.kind), ['element', 'group', 'screen']);
    assert.equal(r.scopes[0].component, 'PlanCard');
  });
});

describe('ios scope ladder (§15.3, #694 PR-3)', () => {
  const POINTS = { width: 402, height: 874 };
  const at = (x, y) => ({ probeId: 'p1', x: x / POINTS.width, y: y / POINTS.height });

  it('SwiftUI without names: the element and the screen only', () => {
    const r = describeIosPoint(parseAxTree(ACME_TREE), at(200, 484), POINTS);
    assert.deepEqual(r.scopes.map((s) => s.kind + ':' + (s.id ?? s.type)), ['element:saveButton', 'screen:screen']);
  });

  it('a named container, a cell and a list become steps; the navigation bar is the title’s step', () => {
    const tree = structuredClone(ACME_TREE);
    // Wrap the switch row in a container the app named, inside a cell, inside a list
    const app = tree.data.result[0];
    const list = app.children[0].children[1];
    const row = list.children.find((n) => n.type === 'CheckBox');
    const named = { type: 'Group', role: 'AXGroup', AXUniqueId: 'securitySection', frame: { x: 16, y: 320, width: 370, height: 104 }, children: [row] };
    const cell = { type: 'Cell', role: 'AXCell', frame: { x: 0, y: 300, width: 402, height: 140 }, children: [named] };
    const table = { type: 'Table', role: 'AXTable', frame: { x: 0, y: 160, width: 402, height: 500 }, children: [cell] };
    list.children = list.children.filter((n) => n !== row).concat(table);

    const r = describeIosPoint(parseAxTree(tree), at(60, 397), POINTS);
    assert.deepEqual(
      r.scopes.map((s) => s.kind + ':' + s.type + (s.id ? '#' + s.id : '')),
      ['element:CheckBox', 'group:Group#securitySection', 'group:Cell', 'group:Table', 'screen:screen']
    );
    assert.equal(r.scopes[0].text, '알림 받기');

    const title = describeIosPoint(parseAxTree(ACME_TREE), at(60, 140), POINTS);
    assert.deepEqual(title.scopes.map((s) => s.kind + ':' + (s.id ?? s.text)), ['element:프로필', 'group:프로필', 'screen:undefined']);
  });

  it('never makes a step of a secure field or of what was typed', () => {
    const r = describeIosPoint(parseAxTree(ACME_TREE), at(200, 345), POINTS);
    assert.equal(JSON.stringify(r.scopes).includes('••'), false);
    assert.equal(r.scopes[0].type, 'SecureTextField');
  });
});

describe('source candidates for the wider steps (§15.3, #694 PR-3)', () => {
  it('finds each wider step’s id once and marks its step, after the element’s own id', async () => {
    clearListings();
    const project = realpathSync(mkdtempSync(join(tmpdir(), 'qeploy-scope-hints-')));
    const file = (rel, body) => {
      mkdirSync(dirname(join(project, rel)), { recursive: true });
      writeFileSync(join(project, rel), body);
    };
    file('Package.swift', '// swift-tools-version:5.9\n');
    file('Acme/SaveButton.swift', 'struct SaveButton: View {\n  var body: some View { Button("저장") {}.accessibilityIdentifier("saveButton") }\n}\n');
    file('Acme/ProfileScreen.swift', 'VStack {\n  SaveButton()\n}\n.accessibilityElement(children: .contain)\n.accessibilityIdentifier("profileForm")\n');
    file('Acme/Other.swift', '.accessibilityIdentifier("profileForm")\n');

    const hints = await findSourceHints({
      project,
      element: { id: 'saveButton', text: '저장' },
      scopeIds: [{ id: 'profileForm', scope: 1 }, { id: 'saveButton', scope: 2 }, { id: 'bad id!', scope: 3 }, { id: 'x1', scope: 0 }],
    });
    clearListings();
    assert.deepEqual(hints[0], { file: 'Acme/SaveButton.swift', line: 2, reason: 'ID' }, 'the element’s own id first');
    const wider = hints.filter((h) => h.scope !== undefined);
    assert.equal(wider.length, 1, 'one place per wider step; a step that repeats the element’s id or is not an id is skipped');
    assert.equal(wider[0].scope, 1);
    assert.equal(wider[0].reason, 'ID');
    assert.ok(['Acme/ProfileScreen.swift', 'Acme/Other.swift'].includes(wider[0].file));
  });
});
