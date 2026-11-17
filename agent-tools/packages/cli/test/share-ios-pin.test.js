import assert from 'node:assert/strict';
import { afterEach, beforeEach, describe, it } from 'node:test';
import { existsSync, mkdirSync, mkdtempSync, readdirSync, realpathSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { deflateSync } from 'node:zlib';
import { AxeCommands } from '../src/share/ios/axe.js';
import { describeIosPoint, describeIosRegion, parseAxTree, pickElement, resolveIosPin, screenTitle } from '../src/share/ios/pin.js';
import { clearListings } from '../src/share/source-hints.js';
import { ACME_TREE, APP_PID } from './fixtures/ios-acme.js';

const POINTS = { width: 402, height: 874 };

const at = (x, y, probeId = 'p1') => ({ probeId, x: x / POINTS.width, y: y / POINTS.height });

/** An RGB PNG (filter 0), `pixel(x, y)` → [r, g, b]. */
function makePng(width, height, pixel) {
  const rows = [];
  for (let y = 0; y < height; y++) {
    const row = Buffer.alloc(1 + width * 3);
    for (let x = 0; x < width; x++) row.set(pixel(x, y), 1 + x * 3);
    rows.push(row);
  }
  const crc32 = (buf) => {
    let c = ~0;
    for (const b of buf) {
      c ^= b;
      for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1));
    }
    return ~c >>> 0;
  };
  const chunk = (type, data) => {
    const head = Buffer.alloc(8);
    head.writeUInt32BE(data.length);
    head.write(type, 4, 'latin1');
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(Buffer.concat([head.subarray(4), data])));
    return Buffer.concat([head, data, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 2;
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(Buffer.concat(rows))), chunk('IEND', Buffer.alloc(0))]);
}

/** The screen at 1x (402×874 px): white, the save button blue. */
const SCREEN_PNG = makePng(402, 874, (x, y) => (x >= 16 && x < 386 && y >= 458 && y < 510 ? [0, 122, 255] : [255, 255, 255]));
const jpegSize = (b) => {
  for (let i = 2; i + 9 < b.length; ) {
    if (b[i] !== 0xff) return null;
    const marker = b[i + 1];
    if (marker === 0xc0) return { width: b.readUInt16BE(i + 7), height: b.readUInt16BE(i + 5) };
    i += 2 + b.readUInt16BE(i + 2);
  }
  return null;
};
const isJpeg = (b) => Buffer.isBuffer(b) && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff;
const FRAME = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x01, 0xff, 0xd9]);

function fakeCommands({ tree = ACME_TREE, png = SCREEN_PNG, failTree = false } = {}) {
  const commands = {
    sent: [],
    shots: 0,
    request: async (args) => {
      commands.sent.push(args.join(' '));
      if (failTree) return { command: args[0], didError: true, error: { code: 'commandFailed' } };
      return tree;
    },
    screenshot: async () => {
      commands.shots++;
      if (!png) throw new Error('screenshot failed');
      return png;
    },
  };
  return commands;
}

describe('ios accessibility tree (§14.3, #682 iOS)', () => {
  it('reads elements in tree order with their parents, the app root and secure fields', () => {
    const nodes = parseAxTree(ACME_TREE);
    assert.equal(nodes[0].app, true);
    assert.equal(nodes[0].label, 'Acme');
    assert.equal(nodes[0].pid, APP_PID);
    const save = nodes.find((n) => n.id === 'saveButton');
    assert.equal(save.label, '저장');
    assert.equal(save.parent.parent.type, 'Group');
    assert.ok(nodes.indexOf(nodes.find((n) => n.label === '프로필')) < nodes.indexOf(nodes.find((n) => n.label === '이름')), 'tree order');
    assert.equal(nodes.find((n) => n.id === 'passwordField').secure, true);
    assert.equal(nodes.find((n) => n.id === 'nameField').secure, false);
    // The bare array (describe-ui without --output json) and an AXFrame-only element read the same.
    const bare = parseAxTree([{ type: 'Button', role: 'AXButton', AXLabel: 'OK', AXFrame: '{{10, 20.5}, {30, 40}}' }]);
    assert.deepEqual(bare[0].frame, { x: 10, y: 20.5, w: 30, h: 40 });
    assert.deepEqual(parseAxTree(null), []);
  });

  it('picks the smallest named element; a switch label or knob means the switch row', () => {
    const nodes = parseAxTree(ACME_TREE);
    assert.equal(pickElement(nodes, 200, 484).node.id, 'saveButton');
    const label = pickElement(nodes, 60, 397);
    assert.equal(label.node.type, 'CheckBox');
    assert.equal(label.text, '알림 받기');
    const knob = pickElement(nodes, 340, 397);
    assert.equal(knob.node.label, '알림 받기', 'the unnamed knob is the named switch around it');
    // The scroll indicator over the right edge is not what anyone points at.
    assert.equal(pickElement(nodes, 384, 594).node.label, '지금은 무료 요금제를 쓰고 있습니다.');
    assert.equal(pickElement(nodes, 500, 500), null, 'outside the screen');
  });

  it('names the screen by its navigation title, not by a section header', () => {
    const nodes = parseAxTree(ACME_TREE);
    assert.equal(screenTitle(nodes, POINTS), '프로필');
    const noTitle = nodes.filter((n) => n.label !== '프로필');
    assert.equal(screenTitle(noTitle, POINTS), null, '이름 sits below the top fifth');
  });
});

describe('ios pin description (§14.3, #682 iOS)', () => {
  it('describes the button: AX type, label, accessibilityIdentifier, bounds; the app and screen', () => {
    const result = describeIosPoint(parseAxTree(ACME_TREE), at(200, 484), POINTS, { bundleId: 'dev.qeploy.acme', appPid: APP_PID });
    assert.equal(result.ok, true);
    assert.deepEqual(result.screen, { label: 'dev.qeploy.acme', bundleId: 'dev.qeploy.acme', appName: 'Acme', title: '프로필' });
    assert.deepEqual(result.element, { type: 'Button', text: '저장', id: 'saveButton', bounds: { x: 0.0398, y: 0.524, w: 0.9204, h: 0.0599 } });
    assert.deepEqual(result.ancestors, []);
    assert.deepEqual(result.components, [], 'the tree has roles, not view types');
  });

  it('never sends what was typed: a field is named by its id, a secure one is marked', () => {
    const nodes = parseAxTree(ACME_TREE);
    const name = describeIosPoint(nodes, at(200, 234), POINTS);
    assert.deepEqual(name.element, { type: 'TextField', id: 'nameField', bounds: name.element.bounds });
    const secret = describeIosPoint(nodes, at(200, 345), POINTS);
    assert.equal(secret.element.type, 'SecureTextField');
    assert.equal(secret.element.id, 'passwordField');
    assert.equal(JSON.stringify(secret).includes('••'), false);
    assert.equal(JSON.stringify(name).includes('김민지'), false);
  });

  it('names the bundle only while the shared app is in front; the background is NO_ELEMENT', () => {
    const nodes = parseAxTree(ACME_TREE);
    const other = describeIosPoint(nodes, at(200, 484), POINTS, { bundleId: 'dev.qeploy.acme', appPid: 1 });
    assert.deepEqual(other.screen, { label: 'Acme', appName: 'Acme', title: '프로필' });
    const whole = describeIosPoint(nodes, at(200, 484), POINTS);
    assert.equal(whole.screen.label, 'Acme', 'the whole simulator shared: the app by its name');
    const empty = describeIosPoint(nodes, at(200, 760), POINTS, { bundleId: 'dev.qeploy.acme', appPid: APP_PID });
    assert.equal(empty.ok, false);
    assert.equal(empty.reason, 'NO_ELEMENT');
    assert.equal(empty.screen.label, 'dev.qeploy.acme');
    // §15.4: the nearest named element around it; the scroll indicator is none
    assert.deepEqual(empty.near, [
      { dir: 'up', type: 'StaticText', text: '지금은 무료 요금제를 쓰고 있습니다.', bounds: { x: 0.0398, y: 0.6499, w: 0.9204, h: 0.0599 }, distance: 0.1598 },
    ]);
  });

  it('lists named ancestors: the navigation bar around its title', () => {
    const result = describeIosPoint(parseAxTree(ACME_TREE), at(60, 140), POINTS);
    assert.deepEqual(result.element.text, '프로필');
    assert.deepEqual(result.ancestors, [{ type: 'Group', id: '프로필' }]);
  });
});

describe('a dragged area on the simulator (§15.4, #694 PR-4)', () => {
  const area = (l, t, r, b) => ({ x: l / POINTS.width, y: t / POINTS.height, w: (r - l) / POINTS.width, h: (b - t) / POINTS.height });

  it('lists the named elements inside by their labels — never a value — and the centre element', () => {
    const r = describeIosRegion(parseAxTree(ACME_TREE), { probeId: 'p1', rect: area(10, 160, 392, 430) }, POINTS, { bundleId: 'dev.qeploy.acme', appPid: APP_PID });
    assert.equal(r.ok, true);
    assert.equal(r.screen.label, 'dev.qeploy.acme');
    assert.equal(r.element, undefined, 'the centre (201, 295) is between the fields: background');
    assert.deepEqual(
      r.members.map((m) => [m.type, m.text ?? '', m.id ?? '']),
      [
        ['Heading', '이름', ''],
        ['TextField', '', 'nameField'],
        ['SecureTextField', '', 'passwordField'],
        // the switch row; its label and its knob are part of it
        ['CheckBox', '알림 받기', ''],
      ]
    );
    assert.deepEqual(r.scopes.map((s) => s.kind), ['region', 'screen'], 'SwiftUI names no container here');
    assert.equal(r.scopes[0].members, 4);
    const json = JSON.stringify(r);
    assert.equal(json.includes('김민지'), false);
    assert.equal(json.includes('••'), false);
  });

  it('starts the wider steps at the named container holding it', () => {
    const tree = structuredClone(ACME_TREE);
    const list = tree.data.result[0].children[0].children[1];
    const row = list.children.find((n) => n.type === 'CheckBox');
    const named = { type: 'Group', role: 'AXGroup', AXUniqueId: 'securitySection', frame: { x: 16, y: 320, width: 370, height: 104 }, children: [row] };
    const cell = { type: 'Cell', role: 'AXCell', frame: { x: 0, y: 300, width: 402, height: 140 }, children: [named] };
    const table = { type: 'Table', role: 'AXTable', frame: { x: 0, y: 160, width: 402, height: 500 }, children: [cell] };
    list.children = list.children.filter((n) => n !== row).concat(table);

    const nodes = parseAxTree(tree);
    const r = describeIosRegion(nodes, { probeId: 'p2', rect: area(20, 330, 380, 415) }, POINTS);
    assert.equal(r.element.text, '알림 받기');
    assert.deepEqual(r.scopes.map((s) => s.kind + ':' + s.type + (s.id ? '#' + s.id : '')), ['region:region', 'group:Group#securitySection', 'group:Cell', 'group:Table', 'screen:screen']);
    // A named group only names itself: what is in it stays. The section box is tall, so the switch
    // row sits on its row (centres 372 and 397, within 26).
    assert.deepEqual(r.members.map((m) => m.id ?? m.text), ['passwordField', 'securitySection', '알림 받기']);
    // A little more and the cell (61% inside) is in: something to press, it speaks for what is in it,
    // named by the first label inside.
    const more = describeIosRegion(nodes, { probeId: 'p3', rect: area(20, 325, 380, 420) }, POINTS);
    assert.deepEqual(more.members.map((m) => `${m.type}:${m.id ?? m.text}`), ['SecureTextField:passwordField', 'Cell:알림 받기']);
  });

  it('around an empty area, the nearest element on each side', () => {
    const r = describeIosRegion(parseAxTree(ACME_TREE), { probeId: 'p3', rect: area(20, 640, 380, 800) }, POINTS);
    assert.deepEqual(r.members, []);
    assert.equal(r.empty, true);
    assert.deepEqual(r.near, [
      { dir: 'up', type: 'StaticText', text: '지금은 무료 요금제를 쓰고 있습니다.', bounds: { x: 0.0398, y: 0.6499, w: 0.9204, h: 0.0599 }, distance: 0.0225 },
    ]);
  });
});

describe('ios pin on the simulator (§14.3, #682 iOS)', () => {
  let project;
  beforeEach(() => {
    clearListings();
    project = realpathSync(mkdtempSync(join(tmpdir(), 'qeploy-ios-pin-')));
    const file = (rel, body) => {
      mkdirSync(dirname(join(project, rel)), { recursive: true });
      writeFileSync(join(project, rel), body);
    };
    file('Package.swift', '// swift-tools-version:5.9\n');
    file('Acme/SaveButton.swift', 'import SwiftUI\n\nstruct SaveButton: View {\n    var body: some View {\n        Button("저장") {}\n            .font(.caption)\n            .accessibilityIdentifier("saveButton")\n    }\n}\n');
    file('Acme/Legacy/ProfileViewController.swift', 'saveButton.accessibilityIdentifier = "saveButton"\n');
    file('Base.lproj/Main.storyboard', '<button><accessibility key="accessibilityConfiguration" identifier="saveButton"/></button>\n');
    file('build/Acme.app/SaveButton.swift', '.accessibilityIdentifier("saveButton")\n');
  });
  afterEach(() => clearListings());

  it('reads the tree and a screenshot together; crops the element, sends the frame the viewer saw', async () => {
    const commands = fakeCommands();
    const { result, crop, screen } = await resolveIosPin({ commands, probe: at(200, 484), project, bundleId: 'dev.qeploy.acme', appPid: APP_PID, frame: FRAME });
    assert.deepEqual(commands.sent, ['describe-ui']);
    assert.equal(commands.shots, 1);
    assert.equal(result.element.id, 'saveButton');
    // SwiftUI `.accessibilityIdentifier("…")`, UIKit `= "…"`, a storyboard's identifier; not build output.
    assert.deepEqual(
      result.sourceHints.map((h) => `${h.file}:${h.line ?? ''}:${h.reason}`).sort(),
      ['Acme/Legacy/ProfileViewController.swift:1:ID', 'Acme/SaveButton.swift:5:TEXT', 'Acme/SaveButton.swift:7:ID', 'Base.lproj/Main.storyboard:1:ID'].sort()
    );
    assert.ok(isJpeg(crop));
    // The button (370×52.33 pt) and 12 pt around it, at 1 px per point here, rounded outward.
    assert.deepEqual(jpegSize(crop), { width: 396, height: 78 });
    assert.equal(screen, FRAME);
  });

  it('scales the crop to the screenshot and makes the screen from it when there is no frame', async () => {
    const big = makePng(1206, 2622, () => [240, 240, 245]);
    const { crop, screen } = await resolveIosPin({ commands: fakeCommands({ png: big }), probe: at(200, 484), project: null });
    // 394×76.33 pt at 3 px per point, then cut down to 800 on the long side.
    assert.deepEqual(jpegSize(crop), { width: 800, height: 155 });
    assert.ok(isJpeg(screen));
    assert.equal(Math.max(jpegSize(screen).width, jpegSize(screen).height), 1280);
  });

  it('still answers with the point and the picture when the tree cannot be read', async () => {
    const { result, crop, screen } = await resolveIosPin({
      commands: fakeCommands({ failTree: true }),
      probe: at(200, 484),
      project,
      points: POINTS,
      frame: FRAME,
    });
    assert.equal(result.ok, false);
    assert.equal(result.reason, 'UNSUPPORTED');
    assert.deepEqual(jpegSize(crop), { width: 240, height: 160 }, 'the spot around the point');
    assert.equal(screen, FRAME);
  });

  it('crops a dragged area as drawn, 12 pt around it, and searches its container’s id (§15.4)', async () => {
    const rect = { x: 20 / 402, y: 640 / 874, w: 360 / 402, h: 160 / 874 };
    const { result, crop, screen } = await resolveIosPin({ commands: fakeCommands(), probe: { probeId: 'p5', rect }, project, frame: FRAME });
    assert.equal(result.ok, true);
    assert.equal(result.empty, true);
    assert.deepEqual(result.sourceHints, []);
    assert.deepEqual(jpegSize(crop), { width: 384, height: 184 });
    assert.equal(screen, FRAME);

    // The save button in the middle of an area: its id is found as a tap's would be.
    const around = { x: 10 / 402, y: 440 / 874, w: 382 / 402, h: 90 / 874 };
    const save = await resolveIosPin({ commands: fakeCommands(), probe: { probeId: 'p6', rect: around }, project, frame: FRAME });
    assert.equal(save.result.element.id, 'saveButton');
    assert.deepEqual(save.result.members.map((m) => m.id), ['saveButton']);
    assert.ok(save.result.sourceHints.some((h) => h.file === 'Acme/SaveButton.swift' && h.reason === 'ID'), JSON.stringify(save.result.sourceHints));

    // No tree: the centre and the area's crop still go.
    const blind = await resolveIosPin({ commands: fakeCommands({ failTree: true }), probe: { probeId: 'p7', rect }, project, points: POINTS, frame: FRAME });
    assert.equal(blind.result.reason, 'UNSUPPORTED');
    assert.deepEqual(blind.result.point, { x: 0.4975, y: 0.8238 });
    assert.deepEqual(jpegSize(blind.crop), { width: 384, height: 184 });
  });

  it('makes no crop when the screenshot is not the screen the tree describes (turned)', async () => {
    const turned = makePng(874, 402, () => [255, 255, 255]);
    const { result, crop } = await resolveIosPin({ commands: fakeCommands({ png: turned }), probe: at(200, 484), project: null, frame: FRAME });
    assert.equal(result.ok, true);
    assert.equal(crop, null);
  });

  const SCREEN_STEP = { kind: 'screen', type: 'screen', bounds: { x: 0, y: 0, w: 1, h: 1 } };

  it('answers the whole screen from one tree read and the frame the viewer saw, with no screenshot (§15.6)', async () => {
    const commands = fakeCommands();
    const { result, crop, screen } = await resolveIosPin({ commands, probe: { probeId: 'p2', screen: true }, project, bundleId: 'dev.qeploy.acme', appPid: APP_PID, frame: FRAME });
    assert.deepEqual(result, {
      probeId: 'p2',
      ok: true,
      screen: { label: 'dev.qeploy.acme', bundleId: 'dev.qeploy.acme', appName: 'Acme', title: '프로필' },
      scopes: [SCREEN_STEP],
      defaultScope: 0,
    });
    assert.equal(crop, null);
    assert.equal(screen, FRAME);
    assert.deepEqual(commands.sent, ['describe-ui']);
    assert.equal(commands.shots, 0, 'the frame is the screen');
  });

  it('makes the whole screen from a screenshot without a frame, and answers without `screen` when the tree cannot be read', async () => {
    let commands = fakeCommands();
    let out = await resolveIosPin({ commands, probe: { probeId: 'p3', screen: true }, project: null });
    assert.equal(commands.shots, 1);
    assert.ok(isJpeg(out.screen));
    assert.equal(out.result.screen.appName, 'Acme');
    assert.equal(out.crop, null);

    commands = fakeCommands({ failTree: true });
    out = await resolveIosPin({ commands, probe: { probeId: 'p4', screen: true }, project: null, bundleId: 'dev.qeploy.acme', points: POINTS, frame: FRAME });
    assert.deepEqual(out.result, { probeId: 'p4', ok: true, scopes: [SCREEN_STEP], defaultScope: 0 });
    assert.equal(out.screen, FRAME);
  });

  it('works without a screenshot: no crop, the frame as the screen', async () => {
    const { result, crop, screen } = await resolveIosPin({ commands: fakeCommands({ png: null }), probe: at(200, 484), project: null, frame: FRAME });
    assert.equal(result.element.id, 'saveButton');
    assert.equal(crop, null);
    assert.equal(screen, FRAME);
  });
});

describe('axe screenshot (§14.3)', () => {
  it('runs `axe --output json screenshot --udid … --output <file>`, returns the PNG and removes the file', async () => {
    const calls = [];
    const commands = new AxeCommands({
      axePath: '/x/axe',
      udid: 'AAAA-1',
      run: async (file, args, options) => {
        calls.push({ file, args, options });
        writeFileSync(args.at(-1), SCREEN_PNG);
        return '{"command":"screenshot","didError":false,"data":{"result":{}}}';
      },
    });
    const png = await commands.screenshot();
    assert.ok(png.equals(SCREEN_PNG));
    const { file, args, options } = calls[0];
    assert.equal(file, '/x/axe');
    assert.deepEqual(args.slice(0, 6), ['--output', 'json', 'screenshot', '--udid', 'AAAA-1', '--output']);
    assert.equal(options.timeout, 5000);
    assert.equal(existsSync(dirname(args.at(-1))), false, 'the temporary folder is gone');
    assert.equal(readdirSync(tmpdir()).includes(dirname(args.at(-1)).split('/').pop()), false);

    commands.stop();
    await assert.rejects(commands.screenshot());
  });

  it('removes the file when AXe fails', async () => {
    let path = null;
    const commands = new AxeCommands({
      axePath: '/x/axe',
      udid: 'AAAA-1',
      run: async (_file, args) => {
        path = args.at(-1);
        writeFileSync(path, 'half');
        throw new Error('axe failed');
      },
    });
    await assert.rejects(commands.screenshot(), /axe failed/);
    assert.equal(existsSync(dirname(path)), false);
  });
});
