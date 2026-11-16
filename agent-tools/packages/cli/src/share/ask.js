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
export async function askControl(question, options = {}) {
  const { output = process.stderr, timeoutMs = 30000 } = options;
  const answer = await ask(question, `(${Math.round(timeoutMs / 1000)}초 안에 답이 없으면 거절)`, {
    ...options,
    timeoutMs,
    notTty: () => output.write(`${question}\n  → 터미널이 아니어서 거절했습니다.\n`),
    onTimeout: () => output.write('\n  → 답이 없어 거절했습니다.\n'),
  });
  return answer === true;
}

/**
 * Asks the owner whether to take a guest's feedback (§14.4) — taking it is what lets the coding
 * agent read it.
 *
 * Unlike control, not answering is not "no": the feedback stays waiting and can be taken or
 * turned down on the web. Only an explicit answer decides.
 *
 * @returns {Promise<boolean|null>} null when nobody answered (not a terminal, time ran out, Ctrl+D)
 */
export async function askFeedback(question, options = {}) {
  const { output = process.stderr, timeoutMs = 60000 } = options;
  return ask(question, `(${Math.round(timeoutMs / 1000)}초 안에 답이 없으면 그대로 둡니다)`, {
    ...options,
    timeoutMs,
    notTty: () => {},
    onTimeout: () => output.write('\n  → 답이 없어 그대로 둡니다. 웹에서 받을 수 있습니다.\n'),
  });
}

/** @returns {Promise<boolean|null>} true for y/yes, false for anything else typed, null for no answer */
async function ask(question, wait, { input = process.stdin, output = process.stderr, timeoutMs, onInterrupt, notTty, onTimeout }) {
  if (!input.isTTY) {
    notTty();
    return null;
  }

  const rl = createInterface({ input, output });
  const timeout = new AbortController();
  const timer = setTimeout(() => timeout.abort(), timeoutMs);
  try {
    const answer = await Promise.race([
      rl.question(`${question} ${wait} [y/N] `, { signal: timeout.signal }),
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
      return null;
    }
    return /^y(es)?$/i.test(answer.trim());
  } catch {
    // The abort from the timer, or Ctrl+D rejecting the question.
    if (timeout.signal.aborted) onTimeout();
    else output.write('\n');
    return null;
  } finally {
    clearTimeout(timer);
    rl.close();
  }
}
