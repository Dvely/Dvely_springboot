/**
 * Just enough H.264 to relay scrcpy's stream to WebCodecs (docs/device-preview-share-design.md §12):
 * find NAL units in Annex B bytes, and turn the SPS into the codec string `VideoDecoder` wants.
 */

export const NAL_SPS = 7;
export const NAL_PPS = 8;

/**
 * NAL units of an Annex B byte stream.
 *
 * @param {Buffer} buf
 * @returns {{type: number, start: number, end: number}[]} `start` is the first byte after the start code
 */
export function nalUnits(buf) {
  const starts = [];
  for (let i = 0; i + 2 < buf.length; i++) {
    if (buf[i] === 0 && buf[i + 1] === 0 && buf[i + 2] === 1) {
      starts.push(i + 3);
      i += 2;
    }
  }
  return starts.map((start, n) => {
    let end = n + 1 < starts.length ? starts[n + 1] - 3 : buf.length;
    // A four-byte start code leaves one more zero behind the previous unit.
    if (n + 1 < starts.length && end > start && buf[end - 1] === 0) end -= 1;
    return { type: buf[start] & 0x1f, start, end };
  });
}

/**
 * `avc1.PPCCLL` from the first SPS: profile_idc, the constraint flags byte and level_idc, as hex.
 *
 * @param {Buffer} config scrcpy's config packet (SPS and PPS, Annex B)
 * @returns {string|null}
 */
export function codecString(config) {
  const sps = nalUnits(config).find((u) => u.type === NAL_SPS);
  if (!sps || sps.end - sps.start < 4) return null;
  const hex = (b) => b.toString(16).padStart(2, '0').toUpperCase();
  return `avc1.${hex(config[sps.start + 1])}${hex(config[sps.start + 2])}${hex(config[sps.start + 3])}`;
}

/** Whether the bytes already carry an SPS (some encoders repeat it before every key frame). */
export function hasSps(buf) {
  return nalUnits(buf).some((u) => u.type === NAL_SPS);
}
