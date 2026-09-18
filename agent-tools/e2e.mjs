/**
 * Drives the real MCP server over stdio against a running Qeploy, exactly as Claude Code or Codex
 * would. Mock tests only prove what we send; this proves the wire works.
 *
 * Deliberately does not start a real deployment — it checks that the authorization layers refuse
 * one, which is the property that matters and the one a mock cannot establish.
 *
 *   QEPLOY_E2E_URL=http://localhost:8099 \
 *   QEPLOY_E2E_READ_TOKEN=qp_... QEPLOY_E2E_WRITE_TOKEN=qp_... node e2e.mjs
 */
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const ENTRY = join(HERE, 'packages/mcp/src/index.js');
const URL_ = process.env.QEPLOY_E2E_URL ?? 'http://localhost:8099';
const READ = process.env.QEPLOY_E2E_READ_TOKEN;
const WRITE = process.env.QEPLOY_E2E_WRITE_TOKEN;

const results = [];
const check = (label, ok, detail = '') => {
  results.push(ok);
  console.log(`${ok ? 'OK ' : '!! '}${label}${detail ? ` — ${detail}` : ''}`);
};

async function connect(token, enableWrites) {
  const transport = new StdioClientTransport({
    command: 'node',
    args: [ENTRY],
    env: {
      ...process.env,
      QEPLOY_API_URL: URL_,
      QEPLOY_TOKEN: token,
      QEPLOY_ENABLE_WRITES: enableWrites ? 'true' : 'false',
    },
  });
  const client = new Client({ name: 'e2e', version: '0.0.1' }, { capabilities: {} });
  await client.connect(transport);
  return client;
}

// ── 읽기 전용 (기본) ────────────────────────────────────────────────────────
let c = await connect(READ, false);
check('stdio 연결 + initialize', true);

let tools = (await c.listTools()).tools;
check('기본은 읽기 도구만', tools.length === 11 && !tools.some((t) => t.name === 'qeploy_deploy'),
  `${tools.length}개`);
check('읽기 도구에 readOnlyHint', tools.every((t) => t.annotations?.readOnlyHint === true));

const projects = await c.callTool({ name: 'qeploy_list_projects', arguments: {} });
check('실 API 왕복', !projects.isError, projects.content[0].text.slice(0, 40));

const missing = await c.callTool({ name: 'qeploy_get_project', arguments: { projectId: 999999 } });
check('없는 리소스는 에러 결과(프로토콜 예외 아님)', missing.isError === true);
await c.close();

// ── 쓰기 활성 + READ 스코프 토큰 → 서버가 막아야 한다 ──────────────────────
c = await connect(READ, true);
tools = (await c.listTools()).tools;
check('쓰기 활성 시 도구 17개', tools.length === 17, `${tools.length}개`);

const deployTool = tools.find((t) => t.name === 'qeploy_deploy');
check('deploy 에 destructiveHint', deployTool.annotations?.destructiveHint === true);

const refused = await c.callTool({ name: 'qeploy_deploy', arguments: { projectId: 1 } });
check('READ 토큰의 배포 시도는 서버가 거부', refused.isError === true,
  refused.content[0].text.slice(0, 60));
check('거부 사유가 스코프임을 알려준다', /스코프|읽기 전용/.test(refused.content[0].text));
await c.close();

// ── 쓰기 활성 + WRITE 스코프 토큰 → 인가는 통과, 대상이 없어 404 ───────────
if (WRITE) {
  c = await connect(WRITE, true);
  const notFound = await c.callTool({ name: 'qeploy_deploy', arguments: { projectId: 999999 } });
  // 인가에서 막히지 않고 "대상 없음"까지 갔다는 것이 확인 포인트다. 실제 배포는 트리거하지 않는다.
  check('WRITE 토큰은 인가를 통과해 대상 없음까지 도달', notFound.isError === true,
    notFound.content[0].text.slice(0, 60));
  check('403 이 아니라 404 로 실패', !/스코프|읽기 전용/.test(notFound.content[0].text));
  await c.close();
}

// ── 기동 가드 ──────────────────────────────────────────────────────────────
const noToken = spawnSync('node', [ENTRY], {
  env: { ...process.env, QEPLOY_TOKEN: '', QEPLOY_API_URL: URL_ },
  encoding: 'utf8',
  timeout: 10000,
});
check('토큰 없으면 종료코드 1 + stderr 안내',
  noToken.status === 1 && /QEPLOY_TOKEN/.test(noToken.stderr));
check('안내가 stdout(프로토콜 채널)을 오염시키지 않음', noToken.stdout === '');

console.log();
console.log(results.every(Boolean) ? '전체 통과' : '실패 있음');
process.exit(results.every(Boolean) ? 0 : 1);
