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

const server = createServer(new QeployClient({ baseUrl, token }));
await server.connect(new StdioServerTransport());
