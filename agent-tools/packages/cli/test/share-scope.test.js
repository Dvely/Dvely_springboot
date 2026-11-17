import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { buildLadder, SCREEN_STEP } from '../src/share/scope.js';
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
