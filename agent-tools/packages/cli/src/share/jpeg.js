/**
 * Just enough imaging for pins on devices that only hand out raw pixels (§14.3, #682): read
 * `screencap`'s raw output, cut and shrink a region, and write it as a baseline JPEG.
 *
 * The CLI keeps a single dependency, and the server only takes JPEG (it checks the FF D8 FF
 * signature), so this is written out: a baseline encoder with the standard tables, 4:4:4.
 */

// ── raw screencap (`adb exec-out screencap`, no -p) ─────────────────────────

/** Android PixelFormat → bytes per pixel. 1 RGBA_8888, 2 RGBX_8888, 3 RGB_888, 4 RGB_565, 5 BGRA_8888. */
const BYTES_PER_PIXEL = { 1: 4, 2: 4, 3: 3, 4: 2, 5: 4 };
const MAX_SIDE = 10000;

/**
 * `[width u32][height u32][format u32]([dataspace u32])[pixels]`, little-endian. The fourth word
 * came with Android 9; its presence is told by the size.
 *
 * @returns {{width: number, height: number, pixel: (x: number, y: number, out: number[]) => void}|null}
 */
export function parseScreencap(buf) {
  if (!Buffer.isBuffer(buf) || buf.length < 12) return null;
  const width = buf.readUInt32LE(0);
  const height = buf.readUInt32LE(4);
  const format = buf.readUInt32LE(8);
  const bpp = BYTES_PER_PIXEL[format];
  if (!bpp || width <= 0 || height <= 0 || width > MAX_SIDE || height > MAX_SIDE) return null;
  const offset = buf.length - width * height * bpp;
  if (offset !== 12 && offset !== 16) return null;
  const at = (x, y) => offset + (y * width + x) * bpp;
  let pixel;
  if (format === 5) {
    pixel = (x, y, out) => {
      const i = at(x, y);
      out[0] = buf[i + 2];
      out[1] = buf[i + 1];
      out[2] = buf[i];
    };
  } else if (format === 4) {
    pixel = (x, y, out) => {
      const v = buf.readUInt16LE(at(x, y));
      out[0] = ((v >> 11) & 0x1f) * 255 / 31;
      out[1] = ((v >> 5) & 0x3f) * 255 / 63;
      out[2] = (v & 0x1f) * 255 / 31;
    };
  } else {
    pixel = (x, y, out) => {
      const i = at(x, y);
      out[0] = buf[i];
      out[1] = buf[i + 1];
      out[2] = buf[i + 2];
    };
  }
  return { width, height, pixel };
}

/**
 * One region of the screen as RGB, shrunk (box average) so its longer side is at most `maxSide`.
 *
 * @param {{width: number, height: number, pixel: Function}} screen from parseScreencap
 * @param {{x: number, y: number, width: number, height: number}} rect device pixels; clamped
 * @returns {{width: number, height: number, rgb: Uint8Array}|null}
 */
export function cropRgb(screen, rect, maxSide) {
  const x0 = Math.max(0, Math.floor(rect.x));
  const y0 = Math.max(0, Math.floor(rect.y));
  const x1 = Math.min(screen.width, Math.ceil(rect.x + rect.width));
  const y1 = Math.min(screen.height, Math.ceil(rect.y + rect.height));
  const sw = x1 - x0;
  const sh = y1 - y0;
  if (sw <= 0 || sh <= 0) return null;
  const scale = Math.min(1, maxSide / Math.max(sw, sh));
  const width = Math.max(1, Math.round(sw * scale));
  const height = Math.max(1, Math.round(sh * scale));
  const rgb = new Uint8Array(width * height * 3);
  const px = [0, 0, 0];
  for (let y = 0; y < height; y++) {
    const sy0 = y0 + Math.floor((y * sh) / height);
    const sy1 = Math.max(sy0 + 1, y0 + Math.floor(((y + 1) * sh) / height));
    for (let x = 0; x < width; x++) {
      const sx0 = x0 + Math.floor((x * sw) / width);
      const sx1 = Math.max(sx0 + 1, x0 + Math.floor(((x + 1) * sw) / width));
      let r = 0;
      let g = 0;
      let b = 0;
      let n = 0;
      // At most a 3×3 sample of the box: enough for a picture of a screen, and fast.
      const stepX = Math.max(1, Math.floor((sx1 - sx0) / 3));
      const stepY = Math.max(1, Math.floor((sy1 - sy0) / 3));
      for (let yy = sy0; yy < sy1; yy += stepY) {
        for (let xx = sx0; xx < sx1; xx += stepX) {
          screen.pixel(xx, yy, px);
          r += px[0];
          g += px[1];
          b += px[2];
          n++;
        }
      }
      const o = (y * width + x) * 3;
      rgb[o] = r / n;
      rgb[o + 1] = g / n;
      rgb[o + 2] = b / n;
    }
  }
  return { width, height, rgb };
}

// ── baseline JPEG ──────────────────────────────────────────────────────────

const ZIGZAG = [
  0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
  35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55,
  62, 63,
];

const LUMA_Q = [
  16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87,
  80, 62, 18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92, 49, 64, 78, 87, 103, 121, 120, 101, 72, 92,
  95, 98, 112, 100, 103, 99,
];
const CHROMA_Q = [
  17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99, 24, 26, 56, 99, 99, 99, 99, 99, 47, 66, 99, 99, 99, 99,
  99, 99, ...new Array(32).fill(99),
];

// ITU T.81 Annex K standard Huffman tables: code counts per length 1..16, then the symbols.
const DC_LUMA = { bits: [0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0], vals: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11] };
const DC_CHROMA = { bits: [0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0], vals: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11] };
const AC_LUMA = {
  bits: [0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d],
  vals: hex(
    '01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9fa'
  ),
};
const AC_CHROMA = {
  bits: [0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77],
  vals: hex(
    '000102031104052131061241510761711322328108144291a1b1c109233352f0156272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a636465666768696a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9fa'
  ),
};

function hex(s) {
  return Array.from({ length: s.length / 2 }, (_, i) => parseInt(s.slice(i * 2, i * 2 + 2), 16));
}

/** symbol → [code, length], canonical as T.81 C.2 builds it. */
function codes({ bits, vals }) {
  const out = new Map();
  let code = 0;
  let k = 0;
  for (let len = 1; len <= 16; len++) {
    for (let i = 0; i < bits[len - 1]; i++) out.set(vals[k++], [code++, len]);
    code <<= 1;
  }
  return out;
}

const TABLES = { dcY: codes(DC_LUMA), acY: codes(AC_LUMA), dcC: codes(DC_CHROMA), acC: codes(AC_CHROMA) };

function scaledTable(base, quality) {
  const q = quality < 50 ? 5000 / quality : 200 - quality * 2;
  return base.map((v) => Math.min(255, Math.max(1, Math.floor((v * q + 50) / 100))));
}

/** cos((2x+1)uπ/16) · C(u)/2, so a row or column transform is one dot product. */
const DCT = (() => {
  const t = new Float64Array(64);
  for (let u = 0; u < 8; u++) {
    for (let x = 0; x < 8; x++) t[u * 8 + x] = (u === 0 ? Math.SQRT1_2 : 1) * Math.cos(((2 * x + 1) * u * Math.PI) / 16) / 2;
  }
  return t;
})();

class BitWriter {
  constructor() {
    this.bytes = [];
    this.acc = 0;
    this.n = 0;
  }

  write(code, len) {
    for (let i = len - 1; i >= 0; i--) {
      this.acc = (this.acc << 1) | ((code >> i) & 1);
      if (++this.n === 8) {
        this.bytes.push(this.acc);
        if (this.acc === 0xff) this.bytes.push(0); // byte stuffing
        this.acc = 0;
        this.n = 0;
      }
    }
  }

  flush() {
    if (this.n > 0) this.write((1 << (8 - this.n)) - 1, 8 - this.n);
  }
}

const category = (v) => (v === 0 ? 0 : 32 - Math.clz32(Math.abs(v)));

function encodeBlock(block, quant, prevDc, dc, ac, out) {
  const tmp = new Float64Array(64);
  // rows
  for (let y = 0; y < 8; y++) {
    for (let u = 0; u < 8; u++) {
      let s = 0;
      for (let x = 0; x < 8; x++) s += DCT[u * 8 + x] * block[y * 8 + x];
      tmp[y * 8 + u] = s;
    }
  }
  const coef = new Int32Array(64);
  // columns, then quantise
  for (let u = 0; u < 8; u++) {
    for (let v = 0; v < 8; v++) {
      let s = 0;
      for (let y = 0; y < 8; y++) s += DCT[v * 8 + y] * tmp[y * 8 + u];
      coef[v * 8 + u] = Math.round(s / quant[v * 8 + u]);
    }
  }
  const diff = coef[0] - prevDc;
  const dcCat = category(diff);
  out.write(...dc.get(dcCat));
  if (dcCat) out.write(diff < 0 ? diff + (1 << dcCat) - 1 : diff, dcCat);
  let run = 0;
  for (let k = 1; k < 64; k++) {
    const c = coef[ZIGZAG[k]];
    if (c === 0) {
      run++;
      continue;
    }
    while (run > 15) {
      out.write(...ac.get(0xf0));
      run -= 16;
    }
    const cat = category(c);
    out.write(...ac.get((run << 4) | cat));
    out.write(c < 0 ? c + (1 << cat) - 1 : c, cat);
    run = 0;
  }
  if (run > 0) out.write(...ac.get(0x00));
  return coef[0];
}

/**
 * @param {{width: number, height: number, rgb: Uint8Array}} image
 * @param {number} [quality] 1..100
 * @returns {Buffer} a baseline JFIF
 */
export function encodeJpeg({ width, height, rgb }, quality = 75) {
  const q = Math.min(100, Math.max(1, Math.round(quality)));
  const qY = scaledTable(LUMA_Q, q);
  const qC = scaledTable(CHROMA_Q, q);
  const bits = new BitWriter();
  const Y = new Float64Array(64);
  const Cb = new Float64Array(64);
  const Cr = new Float64Array(64);
  let dcY = 0;
  let dcCb = 0;
  let dcCr = 0;
  for (let by = 0; by < height; by += 8) {
    for (let bx = 0; bx < width; bx += 8) {
      for (let y = 0; y < 8; y++) {
        const sy = Math.min(by + y, height - 1);
        for (let x = 0; x < 8; x++) {
          const i = (sy * width + Math.min(bx + x, width - 1)) * 3;
          const r = rgb[i];
          const g = rgb[i + 1];
          const b = rgb[i + 2];
          const k = y * 8 + x;
          Y[k] = 0.299 * r + 0.587 * g + 0.114 * b - 128;
          Cb[k] = -0.168736 * r - 0.331264 * g + 0.5 * b;
          Cr[k] = 0.5 * r - 0.418688 * g - 0.081312 * b;
        }
      }
      dcY = encodeBlock(Y, qY, dcY, TABLES.dcY, TABLES.acY, bits);
      dcCb = encodeBlock(Cb, qC, dcCb, TABLES.dcC, TABLES.acC, bits);
      dcCr = encodeBlock(Cr, qC, dcCr, TABLES.dcC, TABLES.acC, bits);
    }
  }
  bits.flush();

  const seg = (marker, body) => {
    const head = Buffer.alloc(4);
    head.writeUInt16BE(marker, 0);
    head.writeUInt16BE(body.length + 2, 2);
    return Buffer.concat([head, Buffer.from(body)]);
  };
  const dht = (cls, id, t) => [(cls << 4) | id, ...t.bits, ...t.vals];
  return Buffer.concat([
    Buffer.from([0xff, 0xd8]),
    seg(0xffe0, [0x4a, 0x46, 0x49, 0x46, 0, 1, 1, 0, 0, 1, 0, 1, 0, 0]),
    seg(0xffdb, [0, ...ZIGZAG.map((i) => qY[i]), 1, ...ZIGZAG.map((i) => qC[i])]),
    seg(0xffc0, [8, height >> 8, height & 0xff, width >> 8, width & 0xff, 3, 1, 0x11, 0, 2, 0x11, 1, 3, 0x11, 1]),
    seg(0xffc4, [...dht(0, 0, DC_LUMA), ...dht(1, 0, AC_LUMA), ...dht(0, 1, DC_CHROMA), ...dht(1, 1, AC_CHROMA)]),
    seg(0xffda, [3, 1, 0x00, 2, 0x11, 3, 0x11, 0, 63, 0]),
    Buffer.from(bits.bytes),
    Buffer.from([0xff, 0xd9]),
  ]);
}

/** A JPEG under `maxBytes`, trying lower quality and then a smaller picture; null if it never fits. */
export function jpegUnder(screen, rect, { maxSide, maxBytes, quality = 75 }) {
  for (const [side, q] of [
    [maxSide, quality],
    [maxSide, 50],
    [Math.round(maxSide / 2), 50],
  ]) {
    const image = cropRgb(screen, rect, side);
    if (!image) return null;
    const jpeg = encodeJpeg(image, q);
    if (jpeg.length <= maxBytes) return jpeg;
  }
  return null;
}
