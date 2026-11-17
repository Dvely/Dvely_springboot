import assert from 'node:assert/strict';
import { afterEach, beforeEach, describe, it } from 'node:test';
import { mkdirSync, mkdtempSync, realpathSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { cropRgb, encodeJpeg, jpegUnder, parseScreencap } from '../src/share/jpeg.js';
import { describeAndroidPoint, parseUiDump, pickNode, resolveAndroidPin } from '../src/share/android/pin.js';
import { parseForegroundActivity } from '../src/share/android/adb.js';
import { clearListings } from '../src/share/source-hints.js';

/** A 540×1200 screen: a form with a title, a password field and a custom button; a Compose-like row. */
export const UI_DUMP = `<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0">
<node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.example.pin" content-desc="" clickable="false" password="false" bounds="[0,0][540,1200]">
<node index="0" text="" resource-id="android:id/content" class="android.widget.FrameLayout" package="com.example.pin" content-desc="" clickable="false" password="false" bounds="[0,0][540,1200]">
<node index="0" text="" resource-id="com.example.pin:id/form" class="android.widget.LinearLayout" package="com.example.pin" content-desc="프로필 양식" clickable="false" password="false" bounds="[0,100][540,700]">
<node index="0" text="프로필 &amp; 설정" resource-id="com.example.pin:id/title" class="android.widget.TextView" package="com.example.pin" content-desc="" clickable="false" password="false" bounds="[21,120][300,160]" />
<node index="1" text="hunter2" resource-id="com.example.pin:id/password" class="android.widget.EditText" package="com.example.pin" content-desc="" clickable="true" password="true" bounds="[21,200][519,260]" />
<node index="2" text="저장" resource-id="com.example.pin:id/save" class="com.example.pin.ui.FancyButton" package="com.example.pin" content-desc="" clickable="true" password="false" bounds="[21,600][519,663]" />
</node>
</node>
<node index="1" text="" resource-id="" class="android.view.View" package="com.example.pin" content-desc="" clickable="true" password="false" bounds="[50,750][250,810]">
<node index="0" text="더 보기" resource-id="" class="android.widget.TextView" package="com.example.pin" content-desc="" clickable="false" password="false" bounds="[75,760][225,800]" />
</node>
</node>
</hierarchy>`;

const TOP = { package: 'com.example.pin', activity: '.MainActivity' };
const SIZE = { width: 540, height: 1200 };

/** `screencap` raw: 16-byte header, RGBA; a dark button-coloured band where the save button is. */
export function fakeScreencap({ width = 540, height = 1200, header = 16, format = 1 } = {}) {
  const bpp = format === 4 ? 2 : 4;
  const buf = Buffer.alloc(header + width * height * bpp);
  buf.writeUInt32LE(width, 0);
  buf.writeUInt32LE(height, 4);
  buf.writeUInt32LE(format, 8);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const i = header + (y * width + x) * bpp;
      const save = y >= 600 && y < 663 && x >= 21 && x < 519;
      if (bpp === 2) buf.writeUInt16LE(save ? 0x001f : 0xffff, i);
      else {
        buf[i] = save ? 37 : 248;
        buf[i + 1] = save ? 99 : 250;
        buf[i + 2] = save ? 235 : 252;
        buf[i + 3] = 255;
      }
    }
  }
  return buf;
}

const isJpeg = (b) => Buffer.isBuffer(b) && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff && b.at(-2) === 0xff && b.at(-1) === 0xd9;

describe('uiautomator dump (§14.3, #682 Android)', () => {
  it('reads nodes in order with their parents, bounds and decoded text', () => {
    const nodes = parseUiDump(UI_DUMP);
    assert.equal(nodes.length, 8);
    const title = nodes.find((n) => n.rid.endsWith('/title'));
    assert.equal(title.text, '프로필 & 설정');
    assert.equal(title.parent.rid, 'com.example.pin:id/form');
    assert.deepEqual(nodes.find((n) => n.rid.endsWith('/save')).bounds, { l: 21, t: 600, r: 519, b: 663 });
    assert.equal(nodes[0].parent, null);
  });

  it('picks the pressable view a person meant, named by what it says', () => {
    const nodes = parseUiDump(UI_DUMP);
    let picked = pickNode(nodes, 270, 630);
    assert.equal(picked.node.cls, 'com.example.pin.ui.FancyButton');
    assert.equal(picked.text, '저장');
    // A label inside a pressable container (Compose): the container, named by the label.
    picked = pickNode(nodes, 150, 780);
    assert.equal(picked.node.cls, 'android.view.View');
    assert.equal(picked.text, '더 보기');
    // A password field is named, never read.
    picked = pickNode(nodes, 270, 230);
    assert.equal(picked.text, '');
    assert.equal(pickNode(nodes, 600, 50), null);
  });

  it('describes the view: Activity, class, id, ratio bounds, ancestors and the app’s own classes', () => {
    const r = describeAndroidPoint(parseUiDump(UI_DUMP), { probeId: 'p1', x: 0.5, y: 0.525 }, SIZE, TOP);
    assert.deepEqual(r, {
      probeId: 'p1',
      ok: true,
      screen: { label: 'com.example.pin/.MainActivity', package: 'com.example.pin', activity: '.MainActivity' },
      point: { x: 0.5, y: 0.525 },
      element: { type: 'FancyButton', text: '저장', id: 'save', bounds: { x: 0.0389, y: 0.5, w: 0.9222, h: 0.0525 } },
      // android:id/content is the platform's, not the app's: left out.
      ancestors: [{ type: 'LinearLayout', id: 'form', text: '프로필 양식' }],
      components: [{ name: 'FancyButton' }, { name: 'MainActivity' }],
    });
    assert.doesNotMatch(JSON.stringify(describeAndroidPoint(parseUiDump(UI_DUMP), { probeId: 'p2', x: 0.5, y: 0.19 }, SIZE, TOP)), /hunter2/);
  });

  it('says NO_ELEMENT where only the window is', () => {
    const r = describeAndroidPoint(parseUiDump(UI_DUMP), { probeId: 'p3', x: 0.5, y: 0.95 }, SIZE, TOP);
    assert.equal(r.ok, false);
    assert.equal(r.reason, 'NO_ELEMENT');
    assert.equal(r.screen.label, 'com.example.pin/.MainActivity');
  });

  it('reads the Activity in front', () => {
    assert.deepEqual(
      parseForegroundActivity('  topResumedActivity=ActivityRecord{2b0f u0 com.example.pin/.MainActivity t12}'),
      TOP
    );
    assert.equal(parseForegroundActivity('nothing'), null);
  });
});

describe('screencap → JPEG without dependencies', () => {
  it('reads raw screencap with either header size and the common pixel formats', () => {
    for (const header of [12, 16]) {
      const shot = parseScreencap(fakeScreencap({ width: 40, height: 30, header }));
      assert.equal(shot.width, 40);
      assert.equal(shot.height, 30);
      const px = [0, 0, 0];
      shot.pixel(0, 0, px);
      assert.deepEqual(px, [248, 250, 252]);
    }
    const r565 = parseScreencap(fakeScreencap({ width: 4, height: 4, format: 4 }));
    const px = [0, 0, 0];
    r565.pixel(0, 0, px);
    assert.deepEqual(px.map(Math.round), [255, 255, 255]);
    for (const bad of [Buffer.alloc(8), fakeScreencap({ width: 4, height: 4 }).subarray(0, 50), null]) {
      assert.equal(parseScreencap(bad), null);
    }
  });

  it('writes a baseline JPEG of the requested size', () => {
    const shot = parseScreencap(fakeScreencap({ width: 60, height: 50 }));
    const jpeg = encodeJpeg(cropRgb(shot, { x: 0, y: 0, width: 60, height: 50 }, 100), 80);
    assert.ok(isJpeg(jpeg));
    const sof = jpeg.indexOf(Buffer.from([0xff, 0xc0]));
    assert.ok(sof > 0);
    assert.equal(jpeg.readUInt16BE(sof + 5), 50); // height
    assert.equal(jpeg.readUInt16BE(sof + 7), 60); // width
  });

  it('crops, clamps to the screen, and shrinks to the longer side', () => {
    const shot = parseScreencap(fakeScreencap());
    const crop = cropRgb(shot, { x: -10, y: 590, width: 600, height: 90 }, 300);
    assert.equal(crop.width, 300);
    assert.equal(crop.height, 50);
    assert.equal(cropRgb(shot, { x: 600, y: 0, width: 10, height: 10 }, 100), null);
    assert.equal(jpegUnder(shot, { x: 0, y: 0, width: 540, height: 1200 }, { maxSide: 800, maxBytes: 10 }), null);
  });
});

describe('pinning a view on the emulator (resolveAndroidPin)', () => {
  let project;
  beforeEach(() => {
    clearListings();
    project = realpathSync(mkdtempSync(join(tmpdir(), 'qeploy-android-pin-')));
    const file = (rel, body) => {
      mkdirSync(join(project, rel, '..'), { recursive: true });
      writeFileSync(join(project, rel), body);
    };
    file('settings.gradle', 'include ":app"\n');
    file(
      'app/src/main/res/layout/activity_main.xml',
      '<LinearLayout android:id="@+id/form">\n  <com.example.pin.ui.FancyButton\n    android:id="@+id/save"\n    android:text="@string/save_label" />\n</LinearLayout>\n'
    );
    file('app/src/main/res/values/strings.xml', '<resources>\n  <string name="save_label">저장</string>\n</resources>\n');
    file('app/src/main/java/com/example/pin/MainActivity.kt', 'package com.example.pin\n\nclass MainActivity : AppCompatActivity()\n');
    file('app/src/main/java/com/example/pin/ui/FancyButton.kt', 'package com.example.pin.ui\n\nclass FancyButton(context: Context) : MaterialButton(context)\n');
    file('app/build/intermediates/x.xml', '<x android:id="@+id/save">저장</x>\n');
  });
  afterEach(() => clearListings());

  const fakeAdb = (overrides = {}) => ({
    uiDump: async () => UI_DUMP,
    screencapRaw: async () => fakeScreencap(),
    foregroundActivity: async () => TOP,
    ...overrides,
  });

  it('answers with the view, the layout and code that make it, a crop and the screen', async () => {
    const { result, crop, screen } = await resolveAndroidPin({ adb: fakeAdb(), probe: { probeId: 'p7', x: 0.5, y: 0.525 }, project });
    assert.equal(result.ok, true);
    assert.equal(result.element.id, 'save');
    assert.deepEqual(result.sourceHints, [
      { file: 'app/src/main/java/com/example/pin/ui/FancyButton.kt', line: 3, reason: 'COMPONENT' },
      { file: 'app/src/main/java/com/example/pin/MainActivity.kt', line: 3, reason: 'COMPONENT' },
      { file: 'app/src/main/res/layout/activity_main.xml', line: 3, reason: 'ID' },
      { file: 'app/src/main/res/layout/activity_main.xml', line: 4, reason: 'TEXT' },
      { file: 'app/src/main/res/values/strings.xml', line: 2, reason: 'TEXT' },
    ]);
    assert.ok(isJpeg(crop), 'crop is a JPEG');
    assert.ok(isJpeg(screen), 'screen is a JPEG');
    assert.ok(crop.length < screen.length);
  });

  it('answers the whole screen with the Activity and a screenshot, without reading the tree (§15.6)', async () => {
    let dumps = 0;
    const adb = fakeAdb({ uiDump: async () => (dumps++, UI_DUMP) });
    const { result, crop, screen } = await resolveAndroidPin({ adb, probe: { probeId: 'p10', screen: true }, project });
    assert.deepEqual(result, {
      probeId: 'p10',
      ok: true,
      screen: { label: 'com.example.pin/.MainActivity', package: 'com.example.pin', activity: '.MainActivity' },
      scopes: [{ kind: 'screen', type: 'screen', bounds: { x: 0, y: 0, w: 1, h: 1 } }],
      defaultScope: 0,
    });
    assert.equal(crop, null);
    assert.ok(isJpeg(screen));
    assert.equal(dumps, 0);

    const blind = await resolveAndroidPin({
      adb: fakeAdb({ foregroundActivity: async () => { throw new Error('x'); }, screencapRaw: async () => { throw new Error('x'); } }),
      probe: { probeId: 'p11', screen: true },
      project,
    });
    assert.deepEqual(blind.result, { probeId: 'p11', ok: true, scopes: [{ kind: 'screen', type: 'screen', bounds: { x: 0, y: 0, w: 1, h: 1 } }], defaultScope: 0 });
    assert.equal(blind.screen, null);
  });

  it('still sends the point and the picture when the tree is empty or the dump fails', async () => {
    let out = await resolveAndroidPin({ adb: fakeAdb({ uiDump: async () => '<hierarchy rotation="0"></hierarchy>' }), probe: { probeId: 'p8', x: 0.5, y: 0.5 }, project });
    assert.deepEqual(out.result, {
      probeId: 'p8',
      ok: false,
      reason: 'UNSUPPORTED',
      point: { x: 0.5, y: 0.5 },
      screen: { label: 'com.example.pin/.MainActivity', package: 'com.example.pin', activity: '.MainActivity' },
    });
    assert.ok(isJpeg(out.crop));
    assert.ok(isJpeg(out.screen));

    out = await resolveAndroidPin({
      adb: fakeAdb({ uiDump: async () => { throw new Error('could not get idle state'); }, screencapRaw: async () => { throw new Error('x'); } }),
      probe: { probeId: 'p9', x: 0.5, y: 0.5 },
      project,
    });
    assert.equal(out.result.reason, 'UNSUPPORTED');
    assert.equal(out.crop, null);
    assert.equal(out.screen, null);
  });
});
