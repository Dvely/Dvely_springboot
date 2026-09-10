import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { QeployApiError } from '@qeploy/client';
import { buildTools } from '../src/tools.js';
import { createServer } from '../src/server.js';

/** Drives the server's registered handlers without standing up a real transport. */
function handlers(client, options = { enableWrites: true }) {
  const server = createServer(client, options);
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

describe('write tools', () => {
  const writeNames = [
    'qeploy_bind_domain',
    'qeploy_create_env',
    'qeploy_deploy',
    'qeploy_domain_verification_guide',
    'qeploy_retry_deployment',
    'qeploy_update_env',
  ];

  it('are absent unless explicitly enabled', () => {
    // The default matters: an agent that cannot start a deployment cannot start one by mistake.
    const names = buildTools({}).map((t) => t.name);
    for (const w of writeNames) assert.ok(!names.includes(w), `${w} 가 기본에 있으면 안 된다`);
  });

  it('appear when enabled, and only these', () => {
    const all = buildTools({}, { enableWrites: true }).map((t) => t.name);
    const added = all.filter((n) => !buildTools({}).map((t) => t.name).includes(n)).sort();
    assert.deepEqual(added, writeNames);
  });

  it('never expose an irreversible operation, flag or not', () => {
    // These stay in the web UI permanently — an agent that guesses wrong cannot undo them.
    const all = buildTools({}, { enableWrites: true }).map((t) => t.name);
    for (const forbidden of [
      'qeploy_delete_project',
      'qeploy_delete_server',
      'qeploy_terminate_server',
      'qeploy_disconnect_repository',
      'qeploy_approve',
      'qeploy_set_budget',
    ]) {
      assert.ok(!all.includes(forbidden), `${forbidden} 은 노출하면 안 된다`);
    }
  });

  it('mark mutating tools destructive so a client can prompt first', () => {
    const tools = buildTools({}, { enableWrites: true });
    for (const name of ['qeploy_deploy', 'qeploy_retry_deployment', 'qeploy_bind_domain', 'qeploy_update_env']) {
      const t = tools.find((x) => x.name === name);
      assert.equal(t.annotations.destructiveHint, true, `${name} 에 destructiveHint 필요`);
      assert.equal(t.annotations.readOnlyHint, false);
    }
  });

  it('marks every read tool readOnly', () => {
    for (const t of buildTools({})) {
      assert.equal(t.annotations.readOnlyHint, true, `${t.name} 은 readOnly 여야 한다`);
    }
  });

  it('warns in the description that a deploy may only be queued for approval', () => {
    const deploy = buildTools({}, { enableWrites: true }).find((t) => t.name === 'qeploy_deploy');
    // Approval gates mean a 200 can mean "waiting", and an agent reporting "deployed" would be
    // telling the user something untrue.
    assert.match(deploy.description, /승인/);
    assert.match(deploy.description, /확인/);
  });

  it('passes deploy arguments through', async () => {
    let seen = null;
    const tools = buildTools(
      { deploy: async (id, opts) => { seen = { id, opts }; return { deploymentId: 3 }; } },
      { enableWrites: true }
    );
    await tools.find((t) => t.name === 'qeploy_deploy').run({ projectId: 8, deployTargetType: 'VERSION', versionName: 'v1.2.0' });
    assert.equal(seen.id, 8);
    assert.equal(seen.opts.deployTargetType, 'VERSION');
    assert.equal(seen.opts.versionName, 'v1.2.0');
  });

  it('surfaces the server response rather than claiming success', async () => {
    const h = handlers({ deploy: async () => ({ status: 'PENDING_APPROVAL', deploymentId: 3 }) });
    const result = await call(h, 'qeploy_deploy', { projectId: 1 });
    assert.equal(result.isError, undefined);
    assert.equal(JSON.parse(result.content[0].text).status, 'PENDING_APPROVAL');
  });
});
