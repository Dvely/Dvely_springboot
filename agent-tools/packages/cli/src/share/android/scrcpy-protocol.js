/**
 * The scrcpy server's wire format, as of scrcpy 4.0–5.0 (its doc/develop.md "Protocol", and
 * server/…/control/ControlMessageReader.java). scrcpy says this protocol is internal and changes
 * between versions — the server refuses a client whose version string differs — so everything that
 * depends on it is here, next to the versions it was checked against.
 *
 * Video socket (after the dummy byte):
 *   codec id (u32), then per capture session a 12-byte session packet
 *   [1 | 30 zero bits | client-resized bit][width u32][height u32],
 *   then media packets: [0 | config | key | pts (61 bits)][size u32][payload].
 */
import { codecString, hasSps } from './h264.js';

export const SUPPORTED_MAJOR_VERSIONS = [4, 5];

export const CODEC_H264 = 0x68323634; // "h264"

/** Android constants the control messages carry. */
export const MOTION = Object.freeze({ DOWN: 0, UP: 1, MOVE: 2 });
export const KEY_ACTION = Object.freeze({ DOWN: 0, UP: 1 });
/** scrcpy's POINTER_ID_GENERIC_FINGER: a touchscreen finger, not a mouse. */
export const POINTER_FINGER = -2n;

const TYPE = Object.freeze({
  INJECT_KEYCODE: 0,
  INJECT_TEXT: 1,
  INJECT_TOUCH_EVENT: 2,
  INJECT_SCROLL_EVENT: 3,
  SET_CLIPBOARD: 9,
  RESET_VIDEO: 17,
});

/** ControlMessageReader.INJECT_TEXT_MAX_LENGTH, in UTF-8 bytes. */
export const INJECT_TEXT_MAX_BYTES = 300;
/** Far below the 256 KiB message limit; the viewer sends at most 5000 characters anyway. */
export const CLIPBOARD_MAX_BYTES = 64 * 1024;

const SESSION_FLAG = 0x80;
const CONFIG_FLAG = 0x40;
const KEY_FLAG = 0x20;
const HEADER = 12;
/** A frame larger than this is not something scrcpy would send; treat it as a broken stream. */
const MAX_PACKET_BYTES = 16 * 1024 * 1024;

/**
 * Splits the video socket's bytes into the codec id, session packets and media packets.
 * Feed it with push(); it calls back synchronously.
 */
export class VideoDemuxer {
  /**
   * @param {object} h
   * @param {(codecId: number) => void} h.onCodec
   * @param {(size: {width: number, height: number}) => void} h.onSession
   * @param {(packet: {config: boolean, key: boolean, data: Buffer}) => void} h.onPacket
   */
  constructor({ onCodec, onSession, onPacket }) {
    Object.assign(this, { onCodec, onSession, onPacket });
    this.buffer = Buffer.alloc(0);
    this.codecId = null;
  }

  push(chunk) {
    this.buffer = this.buffer.length ? Buffer.concat([this.buffer, chunk]) : Buffer.from(chunk);
    for (;;) {
      if (this.codecId === null) {
        if (this.buffer.length < 4) return;
        this.codecId = this.buffer.readUInt32BE(0);
        this.buffer = this.buffer.subarray(4);
        this.onCodec(this.codecId);
        continue;
      }
      if (this.buffer.length < HEADER) return;
      const first = this.buffer[0];
      if (first & SESSION_FLAG) {
        const width = this.buffer.readUInt32BE(4);
        const height = this.buffer.readUInt32BE(8);
        this.buffer = this.buffer.subarray(HEADER);
        this.onSession({ width, height });
        continue;
      }
      const size = this.buffer.readUInt32BE(8);
      if (size > MAX_PACKET_BYTES) throw new Error(`scrcpy 영상 패킷 크기가 이상합니다: ${size}바이트`);
      if (this.buffer.length < HEADER + size) return;
      const data = Buffer.from(this.buffer.subarray(HEADER, HEADER + size));
      this.buffer = this.buffer.subarray(HEADER + size);
      this.onPacket({ config: Boolean(first & CONFIG_FLAG), key: Boolean(first & KEY_FLAG), data });
    }
  }
}

/**
 * Turns scrcpy's packets into self-contained frames for the tunnel: the SPS/PPS config packet is put
 * in front of every key frame, so a decoder can start from any key frame (§12.1). Android encoders
 * send the config once per capture session, not before the periodic key frames.
 */
export class FrameAssembler {
  /**
   * @param {(frame: {key: boolean, data: Buffer}) => void} onFrame
   * @param {(codec: string) => void} onCodecString
   */
  constructor(onFrame, onCodecString) {
    this.onFrame = onFrame;
    this.onCodecString = onCodecString;
    this.config = null;
  }

  /** A new capture session (rotation, reset): its config packet comes next. */
  reset() {
    this.config = null;
  }

  packet({ config, key, data }) {
    if (config) {
      this.config = data;
      const codec = codecString(data);
      if (codec) this.onCodecString(codec);
      return;
    }
    if (key && this.config && !hasSps(data)) {
      this.onFrame({ key: true, data: Buffer.concat([this.config, data]) });
      return;
    }
    // A delta before any config cannot be decoded by anyone; neither can a key frame without one.
    if (!this.config && !hasSps(data)) return;
    this.onFrame({ key, data });
  }
}

/** [flags][Annex B] — the SCREEN_FRAME payload of §12.2. */
export function screenFramePayload({ key, data }) {
  return Buffer.concat([Buffer.from([key ? 1 : 0]), data]);
}

// ── control messages (client → device) ─────────────────────────────────────

function clampInt(v, lo, hi) {
  return Math.min(hi, Math.max(lo, Math.round(v)));
}

function writePosition(buf, offset, { x, y, width, height }) {
  buf.writeInt32BE(clampInt(x, 0, width), offset);
  buf.writeInt32BE(clampInt(y, 0, height), offset + 4);
  buf.writeUInt16BE(clampInt(width, 0, 0xffff), offset + 8);
  buf.writeUInt16BE(clampInt(height, 0, 0xffff), offset + 10);
}

/** float [0, 1] → u16 fixed point (1.0 is 0xffff), like scrcpy's sc_float_to_u16fp. */
function u16fp(f) {
  return Math.min(0xffff, Math.max(0, Math.floor(f * 0x10000)));
}

/** float [-1, 1] → i16 fixed point (1.0 is 0x7fff), like scrcpy's sc_float_to_i16fp. */
function i16fp(f) {
  return Math.min(0x7fff, Math.max(-0x8000, Math.trunc(f * 0x8000)));
}

/**
 * @param {{action: number, x: number, y: number, width: number, height: number, pressure?: number}} e
 *   x/y in video pixels; width/height the current video size (scrcpy drops events for another size)
 */
export function encodeTouch({ action, x, y, width, height, pressure = action === MOTION.UP ? 0 : 1 }) {
  const buf = Buffer.alloc(32);
  buf[0] = TYPE.INJECT_TOUCH_EVENT;
  buf[1] = action;
  buf.writeBigInt64BE(POINTER_FINGER, 2);
  writePosition(buf, 10, { x, y, width, height });
  buf.writeUInt16BE(u16fp(pressure), 22);
  buf.writeUInt32BE(0, 24); // action button
  buf.writeUInt32BE(0, 28); // buttons: none for a finger
  return buf;
}

/** @param {{x, y, width, height, hscroll: number, vscroll: number}} e scroll in notches, [-16, 16] */
export function encodeScroll({ x, y, width, height, hscroll, vscroll }) {
  const buf = Buffer.alloc(21);
  buf[0] = TYPE.INJECT_SCROLL_EVENT;
  writePosition(buf, 1, { x, y, width, height });
  const norm = (v) => Math.min(1, Math.max(-1, v / 16));
  buf.writeInt16BE(i16fp(norm(hscroll)), 13);
  buf.writeInt16BE(i16fp(norm(vscroll)), 15);
  buf.writeUInt32BE(0, 17);
  return buf;
}

export function encodeKeycode({ action, keycode, repeat = 0, metaState = 0 }) {
  const buf = Buffer.alloc(14);
  buf[0] = TYPE.INJECT_KEYCODE;
  buf[1] = action;
  buf.writeUInt32BE(keycode, 2);
  buf.writeUInt32BE(repeat, 6);
  buf.writeUInt32BE(metaState >>> 0, 10);
  return buf;
}

/** One message per ≤300-byte piece, split on character boundaries. */
export function encodeText(text) {
  const out = [];
  let piece = '';
  for (const ch of text) {
    if (Buffer.byteLength(piece + ch) > INJECT_TEXT_MAX_BYTES) {
      out.push(textMessage(piece));
      piece = '';
    }
    piece += ch;
  }
  if (piece) out.push(textMessage(piece));
  return out;
}

function textMessage(text) {
  const bytes = Buffer.from(text, 'utf8');
  const buf = Buffer.alloc(5 + bytes.length);
  buf[0] = TYPE.INJECT_TEXT;
  buf.writeUInt32BE(bytes.length, 1);
  bytes.copy(buf, 5);
  return buf;
}

/** Sets the device clipboard and, with `paste`, presses PASTE — how text the keyboard map lacks gets in. */
export function encodeSetClipboard(text, { paste = true } = {}) {
  let bytes = Buffer.from(text, 'utf8');
  if (bytes.length > CLIPBOARD_MAX_BYTES) bytes = Buffer.from(cutUtf8(text, CLIPBOARD_MAX_BYTES), 'utf8');
  const buf = Buffer.alloc(14 + bytes.length);
  buf[0] = TYPE.SET_CLIPBOARD;
  buf.writeBigUInt64BE(0n, 1); // sequence 0: no acknowledgement wanted
  buf[9] = paste ? 1 : 0;
  buf.writeUInt32BE(bytes.length, 10);
  bytes.copy(buf, 14);
  return buf;
}

function cutUtf8(text, maxBytes) {
  let out = '';
  for (const ch of text) {
    if (Buffer.byteLength(out + ch) > maxBytes) break;
    out += ch;
  }
  return out;
}

/** Restarts capture: a new session packet, config and key frame follow. */
export function encodeResetVideo() {
  return Buffer.from([TYPE.RESET_VIDEO]);
}

/** "scrcpy 5.0.1 <https://…>" → "5.0.1". */
export function parseScrcpyVersion(output) {
  const m = /scrcpy\s+v?(\d+\.\d+(?:\.\d+)?)/i.exec(String(output ?? ''));
  return m ? m[1] : null;
}

export function isSupportedVersion(version) {
  const major = Number(String(version ?? '').split('.')[0]);
  return SUPPORTED_MAJOR_VERSIONS.includes(major);
}
