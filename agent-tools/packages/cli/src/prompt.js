import { UsageError } from './confirm.js';

/** Named so the control bytes are legible in source — a literal one is invisible in an editor. */
const ETX = '\u0003'; // Ctrl+C
const EOT = '\u0004'; // Ctrl+D
const DEL = '\u007f'; // Backspace

/**
 * Reads a secret without echoing it.
 *
 * A pasted credential that lands in the terminal scrollback outlives the command — it stays in the
 * buffer, in a screen-sharing recording, and in whatever the terminal writes to disk. Echoing is
 * the default, so suppressing it has to be deliberate.
 *
 * Piped input is read whole and untouched, which is what makes `qeploy login < token.txt` and
 * `pbpaste | qeploy login` work.
 */
export async function readSecret(promptText, { input = process.stdin, output = process.stderr } = {}) {
  if (!input.isTTY) {
    let data = '';
    input.setEncoding('utf8');
    for await (const chunk of input) data += chunk;
    return data.trim();
  }

  output.write(promptText);
  input.setEncoding('utf8');
  input.setRawMode(true);
  input.resume();

  return new Promise((resolve, reject) => {
    let buffer = '';

    const cleanup = () => {
      input.setRawMode(false);
      input.pause();
      input.off('data', onData);
    };

    const onData = (chunk) => {
      // Raw mode delivers a paste as one chunk, not a character at a time, so this has to walk the
      // chunk — treating it as a single keypress would drop all but the first character of a token.
      for (const ch of chunk) {
        if (ch === '\r' || ch === '\n' || ch === EOT) {
          cleanup();
          output.write('\n');
          resolve(buffer.trim());
          return;
        }
        if (ch === ETX) {
          cleanup();
          output.write('\n');
          reject(new UsageError('취소했습니다.'));
          return;
        }
        if (ch === DEL || ch === '\b') {
          buffer = buffer.slice(0, -1);
          continue;
        }
        // Everything below space is a control sequence (arrow keys arrive as escape sequences);
        // letting those into the buffer would corrupt a token in ways that are invisible on screen.
        if (ch >= ' ') buffer += ch;
      }
    };

    input.on('data', onData);
  });
}
