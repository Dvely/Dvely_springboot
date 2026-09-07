#!/usr/bin/env node
import { QeployClient } from '@qeploy/client';
import { run } from './index.js';
import { confirm } from './confirm.js';

/**
 * Wires the real process to run(). Everything the CLI touches from the outside world is passed in
 * here so the command logic can be tested without a process, a socket, or a terminal.
 */
const code = await run(process.argv.slice(2), {
  createClient: (flags) =>
    new QeployClient({
      baseUrl: flags.apiUrl ?? process.env.QEPLOY_API_URL ?? 'https://qeploy.com',
      token: requireToken(),
    }),
  confirm,
  out: (text) => process.stdout.write(`${text}\n`),
  err: (text) => process.stderr.write(`${text}\n`),
});

process.exit(code);

function requireToken() {
  const token = process.env.QEPLOY_TOKEN;
  if (!token) {
    throw new Error(
      'QEPLOY_TOKEN 이 설정되지 않았습니다.\n' +
        'Qeploy 웹에서 개인 액세스 토큰을 발급한 뒤 환경변수로 넣어주세요.\n' +
        '  export QEPLOY_TOKEN=qp_...'
    );
  }
  return token;
}
