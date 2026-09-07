import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { CallToolRequestSchema, ListToolsRequestSchema } from '@modelcontextprotocol/sdk/types.js';
import { QeployApiError } from '@qeploy/client';
import { buildTools } from './tools.js';

/**
 * Builds the MCP server around a tool set.
 *
 * Separate from the stdio entrypoint so tests can drive the handlers directly — starting a real
 * transport just to check that a tool returns the right thing would test the SDK, not us.
 */
export function createServer(client) {
  const tools = buildTools(client);
  const byName = new Map(tools.map((t) => [t.name, t]));

  const server = new Server(
    { name: 'qeploy', version: '0.1.0' },
    { capabilities: { tools: {} } }
  );

  server.setRequestHandler(ListToolsRequestSchema, async () => ({
    tools: tools.map(({ name, title, description, inputSchema }) => ({
      name,
      title,
      description,
      inputSchema,
    })),
  }));

  server.setRequestHandler(CallToolRequestSchema, async (request) => {
    const tool = byName.get(request.params.name);
    if (!tool) {
      return errorResult(`알 수 없는 도구입니다: ${request.params.name}`);
    }
    try {
      const result = await tool.run(request.params.arguments ?? {});
      return {
        content: [{ type: 'text', text: JSON.stringify(result ?? null, null, 2) }],
      };
    } catch (e) {
      // Returned as an error result rather than thrown: a thrown error becomes a protocol failure
      // the agent cannot read, whereas this reaches the model as text it can act on — which for
      // an expired token means "tell the user to reissue" instead of retrying forever.
      return errorResult(
        e instanceof QeployApiError ? e.toAgentMessage() : `도구 실행 실패: ${e.message}`
      );
    }
  });

  return server;
}

function errorResult(text) {
  return { isError: true, content: [{ type: 'text', text }] };
}
