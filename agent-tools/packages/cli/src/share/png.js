/**
 * Reads a PNG into the same `{width, height, pixel}` shape `parseScreencap` gives, so a web pin can
 * crop a full-viewport screenshot itself (§14.3, #682).
 *
 * Why not CDP's `clip`: `Page.captureScreenshot` with a clip changes the page's device metrics for
 * the capture, and the running screencast sends that zoomed-in frame to every viewer. On a still
 * page it stayed there until something repainted (measured on Chrome 157, 2026-11-17). A capture
 * without a clip leaves the view alone.
 *
 * Only what Chrome produces is handled: 8-bit RGB or RGBA, not interlaced.
 */
import { inflateSync } from 'node:zlib';

const SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const MAX_SIDE = 16384;

/** @returns {{width: number, height: number, pixel: (x: number, y: number, out: number[]) => void}|null} */
export function parsePng(buf) {
  if (!Buffer.isBuffer(buf) || buf.length < 33 || !buf.subarray(0, 8).equals(SIGNATURE)) return null;
  let width = 0;
  let height = 0;
  let channels = 0;
  const idat = [];
  let at = 8;
  while (at + 8 <= buf.length) {
    const length = buf.readUInt32BE(at);
    const type = buf.toString('latin1', at + 4, at + 8);
    const data = buf.subarray(at + 8, at + 8 + length);
    if (data.length !== length) return null;
    if (type === 'IHDR') {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      const depth = data[8];
      const colour = data[9];
      const interlace = data[12];
      if (depth !== 8 || interlace !== 0 || (colour !== 2 && colour !== 6)) return null;
      channels = colour === 6 ? 4 : 3;
    } else if (type === 'IDAT') {
      idat.push(data);
    } else if (type === 'IEND') {
      break;
    }
    at += 12 + length;
  }
  if (!width || !height || width > MAX_SIDE || height > MAX_SIDE || !channels || !idat.length) return null;

  let raw;
  try {
    raw = inflateSync(Buffer.concat(idat));
  } catch {
    return null;
  }
  const stride = width * channels;
  if (raw.length < height * (stride + 1)) return null;

  // Undo the per-row filters (PNG spec §9) into plain pixel rows.
  const pixels = Buffer.alloc(height * stride);
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)];
    const row = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1));
    const out = y * stride;
    const prev = out - stride;
    for (let i = 0; i < stride; i++) {
      const a = i >= channels ? pixels[out + i - channels] : 0;
      const b = y > 0 ? pixels[prev + i] : 0;
      const c = y > 0 && i >= channels ? pixels[prev + i - channels] : 0;
      let v;
      switch (filter) {
        case 0:
          v = row[i];
          break;
        case 1:
          v = row[i] + a;
          break;
        case 2:
          v = row[i] + b;
          break;
        case 3:
          v = row[i] + ((a + b) >> 1);
          break;
        case 4: {
          const p = a + b - c;
          const pa = Math.abs(p - a);
          const pb = Math.abs(p - b);
          const pc = Math.abs(p - c);
          v = row[i] + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c);
          break;
        }
        default:
          return null;
      }
      pixels[out + i] = v & 0xff;
    }
  }
  return {
    width,
    height,
    pixel: (x, y, px) => {
      const i = (y * width + x) * channels;
      px[0] = pixels[i];
      px[1] = pixels[i + 1];
      px[2] = pixels[i + 2];
    },
  };
}
