import { createInterface } from 'node:readline/promises';

/**
 * Asks the owner whether a viewer may take control (§5).
 *
 * Every way of not answering means no: not a terminal (nobody is there to approve, and `--yes`
 * deliberately does not apply — handing a stranger the mouse is never a default), the time running
 * out, Ctrl+D, or the terminal going away.
 *
 * @param {{input?: object, output?: object, timeoutMs?: number, onInterrupt?: Function}} [options]
 *   onInterrupt runs when Ctrl+C is pressed at the prompt — readline takes the key, so the
 *   process would not otherwise see SIGINT and the share could not be ended while a question is up
 * @returns {Promise<boolean>}
 */
export async function askControl(
  question,
  { input = process.stdin, output = process.stderr, timeoutMs = 30000, onInterrupt } = {}
) {
  if (!input.isTTY) {
    output.write(`${question}\n  → 터미널이 아니어서 거절했습니다.\n`);
    return false;
  }

  const rl = createInterface({ input, output });
  const timeout = new AbortController();
  const timer = setTimeout(() => timeout.abort(), timeoutMs);
  try {
    const answer = await Promise.race([
      rl.question(`${question} (${Math.round(timeoutMs / 1000)}초 안에 답이 없으면 거절) [y/N] `, {
        signal: timeout.signal,
      }),
      new Promise((resolve) => rl.once('close', () => resolve(null))),
      new Promise((resolve) =>
        rl.once('SIGINT', () => {
          resolve(null);
          onInterrupt?.();
        })
      ),
    ]);
    if (answer === null) {
      output.write('\n');
      return false;
    }
    return /^y(es)?$/i.test(answer.trim());
  } catch {
    // The abort from the timer, or Ctrl+D rejecting the question.
    output.write(timeout.signal.aborted ? '\n  → 답이 없어 거절했습니다.\n' : '\n');
    return false;
  } finally {
    clearTimeout(timer);
    rl.close();
  }
}
