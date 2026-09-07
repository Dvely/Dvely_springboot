#!/usr/bin/env node
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { QeployClient } from '@qeploy/client';
import { createServer } from './server.js';

/**
 * stdio entrypoint. Configuration comes from the environment because that is what both Claude Code
 * and Codex hand an MCP server; there is no config file of our own to get out of sync.
 */
const baseUrl = process.env.QEPLOY_API_URL ?? 'https://qeploy.com';
const token = process.env.QEPLOY_TOKEN;

if (!token) {
  // stderr, not stdout: stdout is the protocol channel and anything printed there corrupts it.
  process.stderr.write(
    'QEPLOY_TOKEN 이 설정되지 않았습니다.\n' +
      'Qeploy 웹에서 개인 액세스 토큰을 발급한 뒤 환경변수로 넣어주세요.\n' +
      '  export QEPLOY_TOKEN=qp_...\n'
  );
  process.exit(1);
}

// Writes are opt-in. The failure mode of an over-eager agent here is not a wrong answer but a
// real deployment, so the default is that it cannot start one.
const enableWrites = process.env.QEPLOY_ENABLE_WRITES === 'true';

const server = createServer(new QeployClient({ baseUrl, token }), { enableWrites });
await server.connect(new StdioServerTransport());
