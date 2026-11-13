/**
 * Device tunnel frames (CLI ↔ server), docs/device-preview-share-design.md §4.1.
 *
 * One binary WebSocket message is one frame:
 *
 *   [kind: 1 byte][stream id: uint32 big-endian][payload: the rest]
 *
 * The stream id is always 0 in phase 1; it is the slot later "open directly" relaying (§10) will use
 * to multiplex HTTP streams. JSON payloads are UTF-8. These numbers are a fixed contract with the
 * server — change them only together with it.
 */

export const KIND = Object.freeze({
  HELLO: 0x41,
  WELCOME: 0x42,
  GOODBYE: 0x43,
  SCREEN_CONFIG: 0x51,
  SCREEN_FRAME: 0x52,
  INPUT: 0x61,
  CONTROL_REQUEST: 0x62,
  CONTROL_DECISION: 0x63,
  PRESENCE: 0x64,
  /** Server → device: send a fresh key frame (H.264, §12.2). */
  KEYFRAME_REQUEST: 0x65,
});

/** The version the tunnel URL announces with `?v=1` (§4.1). */
export const PROTOCOL_VERSION = '1';

export const HEADER_BYTES = 5;

/** §4.4: one screen frame is at most 2 MiB. Nothing else we send or expect comes close. */
export const MAX_PAYLOAD_BYTES = 2 * 1024 * 1024;

const MAX_STREAM_ID = 0xffffffff;

/**
 * @param {number} kind one byte
 * @param {Buffer|Uint8Array|string} payload a string is written as UTF-8
 * @param {number} [streamId=0]
 * @returns {Buffer}
 */
export function encodeFrame(kind, payload = Buffer.alloc(0), streamId = 0) {
  if (!Number.isInteger(kind) || kind < 0 || kind > 0xff) {
    throw new RangeError(`frame kind must be one byte: ${kind}`);
  }
  if (!Number.isInteger(streamId) || streamId < 0 || streamId > MAX_STREAM_ID) {
    throw new RangeError(`stream id must be a uint32: ${streamId}`);
  }
  const body = typeof payload === 'string' ? Buffer.from(payload, 'utf8') : Buffer.from(payload);
  if (body.length > MAX_PAYLOAD_BYTES) {
    throw new RangeError(`frame payload is ${body.length} bytes, over the ${MAX_PAYLOAD_BYTES} byte limit`);
  }
  const frame = Buffer.allocUnsafe(HEADER_BYTES + body.length);
  frame.writeUInt8(kind, 0);
  frame.writeUInt32BE(streamId, 1);
  body.copy(frame, HEADER_BYTES);
  return frame;
}

/**
 * Splits a frame. An unknown kind is returned as-is — the caller decides to ignore it — so a server
 * that grows a new kind does not break an older CLI.
 *
 * @param {Buffer|ArrayBuffer|Buffer[]} data what `ws` hands to a 'message' listener
 * @returns {{kind: number, streamId: number, payload: Buffer}}
 */
export function decodeFrame(data) {
  const buf = toBuffer(data);
  if (buf.length < HEADER_BYTES) {
    throw new RangeError(`frame is ${buf.length} bytes, shorter than its ${HEADER_BYTES} byte header`);
  }
  if (buf.length - HEADER_BYTES > MAX_PAYLOAD_BYTES) {
    throw new RangeError(`frame payload is over the ${MAX_PAYLOAD_BYTES} byte limit`);
  }
  return {
    kind: buf.readUInt8(0),
    streamId: buf.readUInt32BE(1),
    payload: buf.subarray(HEADER_BYTES),
  };
}

export function encodeJsonFrame(kind, value, streamId = 0) {
  return encodeFrame(kind, JSON.stringify(value), streamId);
}

/** @returns {any} the parsed payload, or null when it is not JSON */
export function parseJsonPayload(payload) {
  try {
    return JSON.parse(Buffer.from(payload).toString('utf8'));
  } catch {
    return null;
  }
}

function toBuffer(data) {
  if (Buffer.isBuffer(data)) return data;
  if (Array.isArray(data)) return Buffer.concat(data);
  if (data instanceof ArrayBuffer) return Buffer.from(data);
  if (ArrayBuffer.isView(data)) return Buffer.from(data.buffer, data.byteOffset, data.byteLength);
  throw new TypeError('frame must be binary');
}
