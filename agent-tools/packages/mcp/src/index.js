#!/usr/bin/env node
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { QeployClient } from '@qeploy/client';
import { resolveApiUrl, resolveToken } from '@qeploy/client/config';
import { createServer } from './server.js';

/**
 * stdio entrypoint. Configuration comes from the environment because that is what both Claude Code
 * and Codex hand an MCP server; there is no config file of our own to get out of sync.
 */
const baseUrl = resolveApiUrl({}, process.env);
// Falls back to what `qeploy login` stored. An agent launched as `npx @qeploy/mcp` inherits whatever
// environment the agent had, which usually has nothing in it — and telling the user to paste a
// credential into their agent's config file would put it in one more place it does not need to be.
const { token } = resolveToken(process.env);

if (!token) {
  // stderr, not stdout: stdout is the protocol channel and anything printed there corrupts it.
  process.stderr.write(
    'Qeploy 토큰이 없습니다.\n' +
      '  npx @qeploy/cli login\n' +
      '을 한 번 실행하거나, 환경변수로 넣어주세요.\n' +
      '  export QEPLOY_TOKEN=qp_...\n'
  );
  process.exit(1);
}

// Writes are opt-in. The failure mode of an over-eager agent here is not a wrong answer but a
// real deployment, so the default is that it cannot start one.
const enableWrites = process.env.QEPLOY_ENABLE_WRITES === 'true';

const server = createServer(new QeployClient({ baseUrl, token }), { enableWrites });
await server.connect(new StdioServerTransport());
