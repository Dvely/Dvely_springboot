import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { QeployClient, QeployApiError, checkBaseUrl } from '../src/index.js';

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

describe('what may reach the URL (#482)', () => {
  it('refuses an id that would rewrite the path, before any request is made', async () => {
    // A read tool must not become a request for an arbitrary endpoint: the model's arguments are
    // not schema-checked on the way in.
    let called = false;
    const c = new QeployClient(opts(async () => { called = true; }));
    for (const bad of ['../api-tokens?x=', '1/../../servers/7/logs#', '%2e%2e', '1#', '0', -1, 1.5, '', null, undefined]) {
      await assert.rejects(() => c.getProjectOverview(bad), TypeError, `${JSON.stringify(bad)} 는 거절돼야 한다`);
    }
    await assert.rejects(() => c.updateEnvironmentVariable(1, '../../../api-tokens#', { value: 'x' }), TypeError);
    assert.equal(called, false);
  });

  it('accepts a positive integer as a number or a digit string', async () => {
    const capture = {};
    const c = new QeployClient(opts(stub(200, { data: {} }, { capture })));
    await c.getProjectOverview(12);
    assert.equal(capture.url, 'https://q.test/api/v1/projects/12/overview');
    await c.getProjectOverview('34');
    assert.equal(capture.url, 'https://q.test/api/v1/projects/34/overview');
  });

  it('refuses plain http outside loopback, so the token never goes out in the clear', () => {
    assert.throws(() => checkBaseUrl('http://qeploy.com'), /https/);
    assert.throws(() => checkBaseUrl('http://54.251.165.129'), /https/);
    assert.throws(() => new QeployClient({ baseUrl: 'http://evil.test', token: 'qp_x' }), /https/);
    assert.throws(() => checkBaseUrl('ftp://qeploy.com'), /https/);
    assert.throws(() => checkBaseUrl('not a url'), /URL/);
  });

  it('keeps loopback http open for local development', () => {
    assert.equal(checkBaseUrl('http://localhost:8099'), 'http://localhost:8099');
    assert.equal(checkBaseUrl('http://127.0.0.1:8080/'), 'http://127.0.0.1:8080');
    assert.equal(checkBaseUrl('http://[::1]:8080'), 'http://[::1]:8080');
    assert.equal(checkBaseUrl('https://qeploy.com'), 'https://qeploy.com');
  });

  it('refuses a base url that already ends in /api/v1 instead of doubling it', () => {
    assert.throws(() => checkBaseUrl('https://qeploy.com/api/v1'), /\/api\/v1/);
    assert.throws(() => checkBaseUrl('https://qeploy.com/api/v1/'), /\/api\/v1/);
  });

  it('does not tell someone who never set QEPLOY_TOKEN that QEPLOY_TOKEN is wrong', () => {
    const msg = new QeployApiError(401, 'UNAUTHORIZED', 'x', '/api/v1/projects').toAgentMessage();
    assert.ok(!msg.includes('QEPLOY_TOKEN'), msg);
    assert.match(msg, /토큰/);
  });
});

describe('while the service is suspended', () => {
  const NOTICE = {
    status: 'suspended',
    since: '2026-10-30',
    title: 'Qeploy 서비스 일시 중단',
    message: '로그인, 프로젝트 생성·관리, 배포, 도메인 연결, MCP·CLI 연동이 동작하지 않습니다.',
  };

  /**
   * Answers the way qeploy.com does with no API behind it: the static host's HTML 404 for reads, a
   * bare 405 for writes, and /status.json as given (undefined = no such file).
   */
  function staticHost(status) {
    const calls = [];
    const fetchImpl = async (url, init = {}) => {
      calls.push({ url, init });
      if (url.endsWith('/status.json')) {
        if (status === undefined) {
          return { status: 404, ok: false, text: async () => '<!doctype html><title>404</title>' };
        }
        return { status: 200, ok: true, text: async () => JSON.stringify(status) };
      }
      if ((init.method ?? 'GET') === 'GET') {
        return { status: 404, ok: false, text: async () => '<!doctype html><div id="root"></div>' };
      }
      return { status: 405, ok: false, text: async () => '' };
    };
    return { fetchImpl, calls };
  }

  it('says the service is suspended instead of "not found" on a read', async () => {
    const { fetchImpl } = staticHost(NOTICE);
    const c = new QeployClient(opts(fetchImpl));
    await assert.rejects(
      () => c.listProjects(),
      (e) => {
        assert.ok(e instanceof QeployApiError);
        assert.equal(e.code, 'SERVICE_SUSPENDED');
        assert.equal(
          e.toAgentMessage(),
          'Qeploy 서비스 일시 중단 (2026-10-30부터)\n' +
            '로그인, 프로젝트 생성·관리, 배포, 도메인 연결, MCP·CLI 연동이 동작하지 않습니다.'
        );
        return true;
      }
    );
  });

  it('says the same on a write that the static host answers with a bare 405', async () => {
    const { fetchImpl } = staticHost(NOTICE);
    const c = new QeployClient(opts(fetchImpl));
    await assert.rejects(() => c.issueApiToken({ scope: 'READ' }), (e) => e.code === 'SERVICE_SUSPENDED');
  });

  it('reads the notice from the site root without sending the token', async () => {
    const { fetchImpl, calls } = staticHost(NOTICE);
    const c = new QeployClient(opts(fetchImpl));
    await assert.rejects(() => c.listProjects());
    const lookup = calls.find((call) => call.url.endsWith('/status.json'));
    assert.equal(lookup.url, 'https://q.test/status.json');
    assert.equal(lookup.init.headers?.Authorization, undefined);
  });

  it('keeps the original error when there is no status file', async () => {
    const { fetchImpl } = staticHost(undefined);
    const c = new QeployClient(opts(fetchImpl));
    await assert.rejects(
      () => c.listProjects(),
      (e) => e.status === 404 && /대상을 찾을 수 없습니다/.test(e.toAgentMessage())
    );
  });

  it('keeps the original error when the status file says the service is up', async () => {
    const { fetchImpl } = staticHost({ status: 'operational' });
    const c = new QeployClient(opts(fetchImpl));
    await assert.rejects(() => c.issueApiToken({ scope: 'READ' }), (e) => e.status === 405 && e.code === null);
  });

  it('checks the status file when the connection itself fails', async () => {
    const c = new QeployClient(
      opts(async (url) => {
        if (url.endsWith('/status.json')) return { status: 200, ok: true, text: async () => JSON.stringify(NOTICE) };
        throw new Error('ECONNREFUSED');
      })
    );
    await assert.rejects(() => c.listProjects(), (e) => e.code === 'SERVICE_SUSPENDED');
  });

  it('does not look anything up for an error the API itself returned', async () => {
    const { fetchImpl, calls } = staticHost(NOTICE);
    const c = new QeployClient(
      opts(async (url, init) =>
        url.endsWith('/status.json')
          ? fetchImpl(url, init)
          : { status: 404, ok: false, text: async () => JSON.stringify({ code: 'NOT_FOUND', message: '없음' }) }
      )
    );
    await assert.rejects(() => c.getProjectOverview(1), (e) => e.code === 'NOT_FOUND');
    assert.equal(calls.length, 0);
  });

  it('uses only the string fields of the notice', async () => {
    const { fetchImpl } = staticHost({ status: 'suspended', title: { html: '<b>x</b>' }, message: 42 });
    const c = new QeployClient(opts(fetchImpl));
    await assert.rejects(() => c.listProjects(), (e) => e.toAgentMessage() === 'Qeploy 서비스 일시 중단');
  });
});
