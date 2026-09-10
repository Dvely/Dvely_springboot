#!/usr/bin/env node
import { QeployClient } from '@qeploy/client';
import { run } from './index.js';
import { confirm } from './confirm.js';
import { readSecret } from './prompt.js';
import { resolveApiUrl, resolveToken } from '@qeploy/client/config';

/**
 * Wires the real process to run(). Everything the CLI touches from the outside world is passed in
 * here so the command logic can be tested without a process, a socket, or a terminal.
 */
const code = await run(process.argv.slice(2), {
  env: process.env,
  readSecret,
  /**
   * @param tokenOverride used by `login`, which holds a browser JWT rather than a stored PAT
   */
  createClient: (flags, tokenOverride) =>
    new QeployClient({
      baseUrl: resolveApiUrl(flags, process.env),
      token: tokenOverride ?? requireToken(),
    }),
  confirm,
  out: (text) => process.stdout.write(`${text}\n`),
  err: (text) => process.stderr.write(`${text}\n`),
});

process.exit(code);

function requireToken() {
  const { token } = resolveToken(process.env);
  if (!token) {
    throw new Error(
      '로그인이 필요합니다.\n' +
        '  qeploy login\n' +
        '또는 이미 토큰이 있다면 환경변수로 넣어주세요.\n' +
        '  export QEPLOY_TOKEN=qp_...'
    );
  }
  return token;
}
