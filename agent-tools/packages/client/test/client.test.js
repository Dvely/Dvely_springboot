import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { QeployClient, QeployApiError } from '../src/index.js';

/** Minimal fetch stub: records the call and returns a canned response. */
function stub(status, body, { capture = {} } = {}) {
  return async (url, init) => {
    capture.url = url;
    capture.init = init;
    return {
      status,
      ok: status >= 200 && status < 300,
      text: async () => (body === undefined ? '' : JSON.stringify(body)),
    };
  };
}

const opts = (fetchImpl) => ({ baseUrl: 'https://q.test', token: 'qp_x', fetchImpl });

describe('QeployClient', () => {
  it('unwraps the response envelope so callers get data directly', async () => {
    const c = new QeployClient(opts(stub(200, { status: 200, code: 'SUCCESS', data: [{ id: 1 }] })));
    assert.deepEqual(await c.listProjects(), [{ id: 1 }]);
  });

  it('sends the token as a bearer header', async () => {
    const capture = {};
    const c = new QeployClient(opts(stub(200, { data: [] }, { capture })));
    await c.listProjects();
    assert.equal(capture.init.headers.Authorization, 'Bearer qp_x');
  });

  it('strips trailing slashes from the base url', async () => {
    const capture = {};
    const c = new QeployClient({
      baseUrl: 'https://q.test///',
      token: 'qp_x',
      fetchImpl: stub(200, { data: [] }, { capture }),
    });
    await c.listProjects();
    // "//api/v1/..." is a different path to some gateways.
    assert.equal(capture.url, 'https://q.test/api/v1/projects');
  });

  it('returns null for 204 instead of trying to parse an empty body', async () => {
    const c = new QeployClient(opts(stub(204)));
    assert.equal(await c.request('DELETE', '/api/v1/api-tokens/1'), null);
  });

  it('turns 401 into an actionable message about the token', async () => {
    const c = new QeployClient(opts(stub(401, { code: 'UNAUTHORIZED', message: '인증이 필요합니다' })));
    await assert.rejects(
      () => c.listProjects(),
      (e) => {
        assert.ok(e instanceof QeployApiError);
        assert.equal(e.status, 401);
        assert.match(e.toAgentMessage(), /만료|폐기|새 토큰/);
        return true;
      }
    );
  });

  it('explains a 403 as a read-only scope rather than a generic denial', async () => {
    const c = new QeployClient(opts(stub(403, { code: 'FORBIDDEN', message: '읽기 전용' })));
    await assert.rejects(
      () => c.listProjects(),
      (e) => {
        assert.match(e.toAgentMessage(), /READ 스코프|WRITE 스코프/);
        return true;
      }
    );
  });

  it('reports a non-JSON error body by status instead of a parse failure', async () => {
    const c = new QeployClient({
      baseUrl: 'https://q.test',
      token: 'qp_x',
      fetchImpl: async () => ({ status: 502, ok: false, text: async () => '<html>bad gateway</html>' }),
    });
    await assert.rejects(() => c.listProjects(), (e) => e.status === 502);
  });

  it('fails with a timeout rather than hanging the agent', async () => {
    const c = new QeployClient({
      baseUrl: 'https://q.test',
      token: 'qp_x',
      timeoutMs: 20,
      fetchImpl: (_u, init) =>
        new Promise((_res, rej) =>
          init.signal.addEventListener('abort', () => {
            const e = new Error('aborted');
            e.name = 'AbortError';
            rej(e);
          })
        ),
    });
    await assert.rejects(() => c.listProjects(), (e) => e.code === 'TIMEOUT');
  });

  it('requires a base url and a token', () => {
    assert.throws(() => new QeployClient({ token: 'qp_x' }), /QEPLOY_API_URL/);
    assert.throws(() => new QeployClient({ baseUrl: 'https://q.test' }), /QEPLOY_TOKEN/);
  });

  it('builds the documented paths', async () => {
    const capture = {};
    const c = new QeployClient(opts(stub(200, { data: {} }, { capture })));
    await c.getProjectOverview(12);
    assert.equal(capture.url, 'https://q.test/api/v1/projects/12/overview');
    await c.getDeploymentLogs(5);
    assert.equal(capture.url, 'https://q.test/api/v1/deployments/5/logs');
    await c.getServerLogs(9);
    assert.equal(capture.url, 'https://q.test/api/v1/servers/9/logs');
  });
});
