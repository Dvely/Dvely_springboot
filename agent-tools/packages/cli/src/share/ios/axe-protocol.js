/**
 * What `qeploy share ios` reads from AXe (docs/device-preview-share-design.md §13.1), kept pure so
 * each rule is testable without a simulator. Shapes are those of AXe 2.0.4, measured on an iOS 26.5
 * simulator:
 *
 *   - `stream-video --format raw`: `[u32 BE length][JPEG]` repeated, split by RawJpegDemuxer
 *   - a JPEG's size, read from its SOF segment (the config needs it; AXe does not say it)
 *   - `--output json <command>`: one result envelope (parseAxeJson)
 *   - `displays`: the screen size in points; `describe-ui`: the app in front (its name and pid) and
 *     which keyboard is in use
 */

/**
 * Larger than any screen AXe can capture as one JPEG (a 2048×2732 iPad at quality 100 is a few MiB).
 * A length past it means the byte stream lost its framing, not that a frame is big.
 */
export const MAX_RAW_FRAME_BYTES = 32 * 1024 * 1024;

/**
 * Splits `stream-video --format raw` output into JPEGs, however the pipe chunks it.
 *
 * Chunks are held as a list and joined only once a whole length or frame is there, so a 100 KB frame
 * arriving in 64 KB reads is copied once, not once per read.
 */
export class RawJpegDemuxer {
  /**
   * @param {(jpeg: Buffer) => void} onFrame
   * @param {{maxFrameBytes?: number}} [options]
   */
  constructor(onFrame, { maxFrameBytes = MAX_RAW_FRAME_BYTES } = {}) {
    this.onFrame = onFrame;
    this.maxFrameBytes = maxFrameBytes;
    this.chunks = [];
    this.size = 0;
    this.frameLength = null; // null while reading a length prefix
  }

  /** @throws {Error} when a length prefix is impossible — the stream is not raw format */
  push(chunk) {
    if (!chunk?.length) return;
    this.chunks.push(chunk);
    this.size += chunk.length;
    for (;;) {
      if (this.frameLength === null) {
        if (this.size < 4) return;
        const length = this.take(4).readUInt32BE(0);
        if (length > this.maxFrameBytes) {
          throw new Error(`stream-video 출력이 형식에 맞지 않습니다 (한 장 ${length}바이트).`);
        }
        this.frameLength = length;
      }
      if (this.size < this.frameLength) return;
      const frame = this.take(this.frameLength);
      this.frameLength = null;
      if (frame.length > 0) this.onFrame(frame);
    }
  }

  take(n) {
    const all = this.chunks.length === 1 ? this.chunks[0] : Buffer.concat(this.chunks, this.size);
    const rest = all.subarray(n);
    this.chunks = rest.length ? [rest] : [];
    this.size = rest.length;
    return all.subarray(0, n);
  }
}

/**
 * SOFn markers carry the frame size. C4 (DHT), C8 (JPG) and CC (DAC) share the range but are not
 * frame headers. AXe's encoder writes baseline SOF0; the others are accepted because they are laid
 * out the same way.
 */
function isStartOfFrame(marker) {
  return marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc;
}

/**
 * The pixel size of a JPEG, from its first SOF segment.
 *
 * @param {Buffer} jpeg
 * @returns {{width: number, height: number}|null} null when it is not a JPEG or has no SOF before
 *   the scan data
 */
export function jpegSize(jpeg) {
  if (!Buffer.isBuffer(jpeg) || jpeg.length < 4 || jpeg[0] !== 0xff || jpeg[1] !== 0xd8) return null;
  let i = 2;
  while (i + 3 < jpeg.length) {
    if (jpeg[i] !== 0xff) return null;
    const marker = jpeg[i + 1];
    if (marker === 0xff) {
      i += 1; // a fill byte before the marker
      continue;
    }
    // Standalone markers have no length: TEM, RSTn and a stray SOI.
    if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd8)) {
      i += 2;
      continue;
    }
    if (marker === 0xd9 || marker === 0xda) return null; // EOI or the scan: no SOF came first
    const length = jpeg.readUInt16BE(i + 2);
    if (length < 2) return null;
    if (isStartOfFrame(marker)) {
      // [FF Cn][length 2][precision 1][height 2][width 2]…
      if (i + 9 > jpeg.length) return null;
      const height = jpeg.readUInt16BE(i + 5);
      const width = jpeg.readUInt16BE(i + 7);
      return width > 0 && height > 0 ? { width, height } : null;
    }
    i += 2 + length;
  }
  return null;
}

/**
 * The JSON `axe --output json <command>` prints: one `axe.output.command-result` envelope, also when
 * the command failed (then `didError` is true, `error.code` says why, and the exit status is nonzero).
 *
 * @param {string} text the command's whole stdout
 * @returns {{command?: string, didError: boolean, data?: any, error?: {code?: string, message?: string}}|null}
 */
export function parseAxeJson(text) {
  let value;
  try {
    value = JSON.parse(String(text ?? '').trim());
  } catch {
    return null;
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  return typeof value.didError === 'boolean' ? value : null;
}

/** The first `major.minor[.patch]` in `axe --version` output (`v2.0.4`), or null. */
export function parseAxeVersion(output) {
  const m = /(\d+)\.(\d+)(?:\.(\d+))?/.exec(String(output ?? ''));
  return m ? m[0] : null;
}

/** §13.1: AXe 2.x, the major release this was measured against (2.0.4). */
export function isSupportedAxeVersion(version) {
  return /^2\./.test(String(version ?? ''));
}

/**
 * The screen size in points from `displays`: `data.result.activeScreen` = `{width, height, scale,
 * orientation, rotation, …}` (AXe 2.0.4). Points in the current orientation are what tap, swipe and
 * draw take.
 *
 * @returns {{width: number, height: number}|null}
 */
export function activeScreenSize(reply) {
  const screen = reply?.data?.result?.activeScreen;
  const width = Number(screen?.width);
  const height = Number(screen?.height);
  return width > 0 && height > 0 ? { width, height } : null;
}

const MAX_TREE_NODES = 20000;

/**
 * The app in front, from `describe-ui`: the first element, breadth first, whose `type` is
 * Application. In AXe 2.0.4 the answer is the envelope with the tree as an array in `data.result`
 * (without `--output json`, the bare array); the root is
 * `{type: "Application", role: "AXApplication", AXLabel: "Safari", pid: 34990, frame, children: […]}`.
 *
 * The pid is what tells apps apart. The home screen (SpringBoard) comes back as an Application too,
 * with a blank label (" ") and SpringBoard's pid, measured after `axe button home`.
 *
 * @returns {{label: string|null, pid: number|null}|null} null when there is no Application element;
 *   a blank label is null
 */
export function foregroundApp(reply) {
  const queue = [reply && typeof reply === 'object' && !Array.isArray(reply) && 'data' in reply ? reply.data?.result : reply];
  for (let i = 0; i < queue.length && i < MAX_TREE_NODES; i++) {
    const node = queue[i];
    if (!node || typeof node !== 'object') continue;
    if (Array.isArray(node)) {
      for (const child of node) queue.push(child);
      continue;
    }
    if (node.type === 'Application' || node.role === 'AXApplication') {
      return {
        label: typeof node.AXLabel === 'string' && node.AXLabel.trim() ? node.AXLabel.trim() : null,
        pid: Number.isInteger(node.pid) && node.pid > 0 ? node.pid : null,
      };
    }
    if (Array.isArray(node.children)) queue.push(node.children);
  }
  return null;
}

/**
 * Which keyboard the simulator is typing with, read off `describe-ui`. The on-screen keyboard stays in
 * the tree while a field has focus, even when a hardware keyboard hides it (measured, iOS 26.5:
 * below the screen at y ≥ 952), and its keys are labelled for the input source in use.
 *
 * @returns {'ko'|'en'|'emoji'|null} null when no keyboard is up (no field has focus)
 */
export function keyboardMode(reply) {
  const queue = [reply && typeof reply === 'object' && !Array.isArray(reply) && 'data' in reply ? reply.data?.result : reply];
  const labels = new Set();
  for (let i = 0; i < queue.length && i < MAX_TREE_NODES; i++) {
    const node = queue[i];
    if (!node || typeof node !== 'object') continue;
    if (Array.isArray(node)) {
      for (const child of node) queue.push(child);
      continue;
    }
    if (node.type === 'Button' && typeof node.AXLabel === 'string') labels.add(node.AXLabel);
    if (Array.isArray(node.children)) queue.push(node.children);
  }
  // ㅁ and a sit on the middle row, unchanged by Shift (ㅂ becomes ㅃ, q becomes Q).
  if (labels.has('ㅁ')) return 'ko';
  if (labels.has('a') || labels.has('A')) return 'en';
  if ([...labels].some((label) => /\p{Extended_Pictographic}/u.test(label))) return 'emoji';
  return null;
}
