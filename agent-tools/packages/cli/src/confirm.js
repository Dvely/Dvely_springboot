import { createInterface } from 'node:readline/promises';

/**
 * Asks before a write.
 *
 * The non-interactive case is the one that matters: a prompt written to a CI log has nobody to
 * answer it and would hang the job until the runner's timeout kills it — a slow, confusing failure
 * for what is really a missing flag. Refusing immediately says exactly what to add.
 *
 * @returns {Promise<boolean>} whether to proceed
 */
export async function confirm(question, { assumeYes, input = process.stdin, output = process.stderr }) {
  if (assumeYes) return true;

  if (!input.isTTY) {
    throw new UsageError(
      '확인이 필요한 작업인데 입력이 터미널이 아닙니다. CI 등 비대화형 환경에서는 --yes 를 붙여주세요.'
    );
  }

  // The prompt goes to stderr so that piping stdout to a file or to jq stays clean.
  const rl = createInterface({ input, output });
  try {
    // Two ways a prompt ends without an answer, and both must mean "no":
    //   - Ctrl+D on a live terminal rejects the question,
    //   - stdin closing under us (a disconnected terminal, a wrapper closing the pipe) settles
    //     nothing at all, so racing the close event is what keeps the process from hanging forever.
    const answer = await Promise.race([
      rl.question(`${question} [y/N] `),
      new Promise((resolve) => rl.once('close', () => resolve(null))),
    ]);
    if (answer === null) {
      output.write('\n');
      return false;
    }
    return /^y(es)?$/i.test(answer.trim());
  } catch {
    output.write('\n');
    return false;
  } finally {
    rl.close();
  }
}

export class UsageError extends Error {
  constructor(message) {
    super(message);
    this.name = 'UsageError';
  }
}
