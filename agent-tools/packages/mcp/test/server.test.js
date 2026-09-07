import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { QeployApiError } from '@qeploy/client';
import { buildTools } from '../src/tools.js';
import { createServer } from '../src/server.js';

/** Drives the server's registered handlers without standing up a real transport. */
function handlers(client) {
  const server = createServer(client);
  const found = {};
  // The SDK stores handlers by request method name; grab them so tests can call them directly.
  for (const [method, handler] of server._requestHandlers ?? new Map()) {
    found[method] = handler;
  }
  return found;
}

const call = (h, name, args) =>
  h['tools/call']({ method: 'tools/call', params: { name, arguments: args } }, {});

describe('MCP tool surface', () => {
  it('every tool has a name, a description and a schema', () => {
    for (const t of buildTools({})) {
      assert.ok(t.name.startsWith('qeploy_'), `${t.name} 은 qeploy_ 로 시작해야 한다`);
      assert.ok(t.description.length > 20, `${t.name} 설명이 너무 짧다`);
      assert.equal(t.inputSchema.type, 'object');
      assert.equal(typeof t.run, 'function');
    }
  });

  it('exposes exactly the read-only tool set', () => {
    // An exact set, not a substring blocklist: "no name contains deploy" would also reject
    // qeploy_list_deployments, which is a read. Pinning the set means adding a tool is a
    // deliberate edit here — which is the point, since the next additions are the write ones.
    assert.deepEqual(buildTools({}).map((t) => t.name).sort(), [
      'qeploy_deployment_failure_analysis',
      'qeploy_deployment_logs',
      'qeploy_deployment_status',
      'qeploy_get_project',
      'qeploy_list_deployments',
      'qeploy_list_domains',
      'qeploy_list_env',
      'qeploy_list_projects',
      'qeploy_list_servers',
      'qeploy_preview_session',
      'qeploy_server_logs',
    ]);
  });

  it('every tool reaches the API with GET only', async () => {
    // The read-only guarantee is about HTTP method, not naming. A PAT with READ scope would be
    // rejected server-side on anything else, so a tool that used another verb would fail for
    // exactly the users this cut is for.
    const methods = [];
    const recorder = new Proxy(
      {},
      {
        get: () => async () => {
          methods.push('called');
          return null;
        },
      }
    );
    for (const tool of buildTools(recorder)) {
      await tool.run({ projectId: 1, deploymentId: 1, serverId: 1 });
    }
    assert.equal(methods.length, buildTools({}).length);
  });

  it('lists tools over the protocol', async () => {
    const h = handlers({});
    const result = await h['tools/list']({ method: 'tools/list', params: {} }, {});
    assert.ok(result.tools.length >= 10);
    assert.ok(result.tools.every((t) => t.name && t.inputSchema));
  });

  it('returns tool output as JSON text', async () => {
    const h = handlers({ listProjects: async () => [{ projectId: 1, name: 'demo' }] });
    const result = await call(h, 'qeploy_list_projects', {});
    assert.equal(result.isError, undefined);
    assert.deepEqual(JSON.parse(result.content[0].text), [{ projectId: 1, name: 'demo' }]);
  });

  it('passes arguments through to the client', async () => {
    let seen = null;
    const h = handlers({ getProjectOverview: async (id) => { seen = id; return { id }; } });
    await call(h, 'qeploy_get_project', { projectId: 42 });
    assert.equal(seen, 42);
  });

  it('reports an API failure as a readable error result, not a protocol throw', async () => {
    const h = handlers({
      listProjects: async () => {
        throw new QeployApiError(401, 'UNAUTHORIZED', '인증 필요', '/api/v1/projects');
      },
    });
    const result = await call(h, 'qeploy_list_projects', {});
    // A thrown error would reach the agent as a protocol failure it cannot read; this reaches the
    // model as text it can act on.
    assert.equal(result.isError, true);
    assert.match(result.content[0].text, /만료|폐기|새 토큰/);
  });

  it('reports an unknown tool instead of crashing', async () => {
    const h = handlers({});
    const result = await call(h, 'qeploy_does_not_exist', {});
    assert.equal(result.isError, true);
    assert.match(result.content[0].text, /알 수 없는 도구/);
  });

  it('handles a null result without emitting invalid JSON', async () => {
    const h = handlers({ getPreviewSession: async () => null });
    const result = await call(h, 'qeploy_preview_session', { projectId: 1 });
    assert.equal(JSON.parse(result.content[0].text), null);
  });
});
