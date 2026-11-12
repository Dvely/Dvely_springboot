import assert from 'node:assert/strict';
import { afterEach, describe, it } from 'node:test';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { WebSocketServer } from 'ws';
import { KIND, MAX_PAYLOAD_BYTES, decodeFrame, encodeJsonFrame, parseJsonPayload } from '../src/share/protocol.js';
import { ShareSession } from '../src/share/session.js';
import { CdpConnection, attachFirstPage } from '../src/share/cdp.js';
import { run, EXIT } from '../src/index.js';

/** Not a real JPEG — the session never decodes the bytes, it only has to deliver them unchanged. */
const JPEG = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x01, 0x02, 0x03, 0xff, 0xd9]);
const JPEG2 = Buffer.from([0xff, 0xd8, 0x09, 0x08, 0xff, 0xd9]);

const cleanups = [];
afterEach(async () => {
  while (cleanups.length) await cleanups.pop()();
});

async function until(predicate, ms = 3000) {
  const end = Date.now() + ms;
  while (!predicate()) {
    if (Date.now() > end) throw new Error('timed out waiting for a condition');
    await new Promise((r) => setTimeout(r, 5));
  }
}

/**
 * Stands in for Chrome: records every CDP command, answers the few whose result the session reads,
 * and lets a test fire events at the session.
 */
function fakeCdp() {
  const calls = [];
  const waiters = [];
  const listeners = { browser: new Map(), page: new Map() };
  const history = {
    currentIndex: 2,
    entries: [
      { id: 1, url: 'about:blank', title: '' },
      { id: 2, url: 'http://localhost:5173/', title: '' },
      { id: 3, url: 'http://localhost:5173/about', title: '' },
    ],
  };
  const results = {
    'Page.getFrameTree': () => ({ frameTree: { frame: { id: 'MAIN', url: 'about:blank' } } }),
    'Page.getLayoutMetrics': () => ({ cssLayoutViewport: { clientWidth: 1280, clientHeight: 800 } }),
    'Page.navigate': () => ({ frameId: 'MAIN' }),
    'Page.getNavigationHistory': () => structuredClone(history),
  };
  const scope = (name) => ({
    send: async (method, params = {}) => {
      const call = { scope: name, method, params };
      calls.push(call);
      for (const w of [...waiters]) {
        if (w.match(call)) {
          waiters.splice(waiters.indexOf(w), 1);
          w.resolve(call);
        }
      }
      return results[method]?.(params) ?? {};
    },
    on: (event, fn) => {
      if (!listeners[name].has(event)) listeners[name].set(event, []);
      listeners[name].get(event).push(fn);
    },
  });
  return {
    browser: scope('browser'),
    page: scope('page'),
    history,
    calls,
    emit: (name, event, params) => (listeners[name].get(event) ?? []).forEach((fn) => fn(params)),
    /** The first call (past or future) of `method` whose params satisfy `match`. */
    waitFor(method, match = () => true) {
      const hit = calls.find((c) => c.method === method && match(c.params));
      if (hit) return Promise.resolve(hit);
      return new Promise((resolve) => waiters.push({ match: (c) => c.method === method && match(c.params), resolve }));
    },
  };
}

/** A WebSocket server playing the Qeploy side of the device tunnel. */
async function tunnelServer() {
  const wss = new WebSocketServer({ host: '127.0.0.1', port: 0 });
  await once(wss, 'listening');
  const arrived = [];
  const waiting = [];
  wss.on('connection', (socket, req) => {
    const frames = [];
    const readers = [];
    socket.on('message', (data, isBinary) => {
      const frame = decodeFrame(data);
      frame.binary = isBinary;
      frame.json = parseJsonPayload(frame.payload);
      if (readers.length) readers.shift()(frame);
      else frames.push(frame);
    });
    const conn = {
      socket,
      req,
      next: () => (frames.length ? Promise.resolve(frames.shift()) : new Promise((r) => readers.push(r))),
      send: (kind, value) => socket.send(encodeJsonFrame(kind, value)),
    };
    if (waiting.length) waiting.shift()(conn);
    else arrived.push(conn);
  });
  const server = {
    url: `ws://127.0.0.1:${wss.address().port}/api/v1/device-shares/7/tunnel?v=1`,
    nextConnection: () => (arrived.length ? Promise.resolve(arrived.shift()) : new Promise((r) => waiting.push(r))),
    close: async () => {
      for (const client of wss.clients) client.terminate();
      await new Promise((r) => wss.close(r));
    },
  };
  cleanups.push(server.close);
  return server;
}

function newSession(cdp, overrides = {}) {
  const logs = [];
  const asked = [];
  const session = new ShareSession({
    target: new URL('http://localhost:5173'),
    targetLabel: 'http://localhost:5173',
    viewport: { width: 390, height: 844, mobile: true },
    browser: cdp.browser,
    page: cdp.page,
    targetId: 'T1',
    tunnelUrl: 'ws://127.0.0.1:1/unused?v=1',
    token: 'qp_test',
    cliVersion: '9.9.9',
    askControl: async (question) => {
      asked.push(question);
      return true;
    },
    log: (text) => logs.push(text),
    pingIntervalMs: 0,
    platform: 'linux',
    ...overrides,
  });
  cleanups.push(async () => session.stop('test-cleanup'));
  return { session, logs, asked };
}

const frameEvent = (bytes, sessionId, size = { deviceWidth: 390, deviceHeight: 844 }) => ({
  data: bytes.toString('base64'),
  metadata: size,
  sessionId,
});

describe('share session over a real tunnel socket', () => {
  it('says HELLO first, sends SCREEN_CONFIG before frames, and turns tunnel frames into CDP calls', { timeout: 10000 }, async () => {
    const server = await tunnelServer();
    const cdp = fakeCdp();
    const { session, logs, asked } = newSession(cdp, { tunnelUrl: server.url });
    const done = session.run();

    const conn = await server.nextConnection();
    assert.equal(conn.req.headers.authorization, 'Bearer qp_test');
    assert.match(conn.req.url, /[?&]v=1/);

    // Produced before the server accepted us: held, not sent, and not yet acknowledged.
    cdp.emit('page', 'Page.screencastFrame', frameEvent(JPEG, 1));

    const hello = await conn.next();
    assert.equal(hello.kind, KIND.HELLO);
    assert.equal(hello.streamId, 0);
    assert.equal(hello.binary, true);
    assert.deepEqual(hello.json, { cliVersion: '9.9.9', kind: 'WEB', target: 'http://localhost:5173' });
    assert.ok(!cdp.calls.some((c) => c.method === 'Page.screencastFrameAck'), 'no ack before WELCOME');

    conn.send(KIND.WELCOME, { shareId: 7, expiresAt: '2026-11-12T13:00:00Z' });
    const config = await conn.next();
    assert.equal(config.kind, KIND.SCREEN_CONFIG);
    assert.deepEqual(config.json, {
      format: 'jpeg',
      width: 390,
      height: 844,
      url: 'http://localhost:5173/',
      title: '',
    });
    const frame = await conn.next();
    assert.equal(frame.kind, KIND.SCREEN_FRAME);
    assert.deepEqual(frame.payload, JPEG);
    await cdp.waitFor('Page.screencastFrameAck', (p) => p.sessionId === 1);

    conn.send(KIND.INPUT, { type: 'pointer', action: 'down', x: 0.5, y: 0.25, button: 'left', clickCount: 1 });
    const press = await cdp.waitFor('Input.dispatchMouseEvent', (p) => p.type === 'mousePressed');
    assert.equal(press.scope, 'page');
    assert.deepEqual(press.params, {
      type: 'mousePressed',
      x: 195,
      y: 211,
      button: 'left',
      buttons: 1,
      clickCount: 1,
      modifiers: 0,
    });

    conn.send(KIND.INPUT, { type: 'text', text: '안녕하세요' });
    await cdp.waitFor('Input.insertText', (p) => p.text === '안녕하세요');

    conn.send(KIND.INPUT, { type: 'nav', action: 'back' });
    await cdp.waitFor('Page.navigateToHistoryEntry', (p) => p.entryId === 2);

    conn.send(KIND.CONTROL_REQUEST, { viewerId: 'v3', name: '민지' });
    const decision = await conn.next();
    assert.equal(decision.kind, KIND.CONTROL_DECISION);
    assert.deepEqual(decision.json, { viewerId: 'v3', granted: true, ttlSeconds: 600 });
    assert.match(asked[0], /민지/);

    conn.send(KIND.PRESENCE, { viewers: 2, controller: { viewerId: 'v3', name: '민지' } });
    await until(() => logs.some((l) => l.includes('보는 사람 2명') && l.includes('조작 중: 민지')));

    // A title change (read from history once the page loads) and a URL change re-announce the screen.
    cdp.history.entries[2].title = 'Vite App';
    cdp.emit('page', 'Page.loadEventFired', {});
    assert.equal((await conn.next()).json.title, 'Vite App');
    cdp.emit('page', 'Page.navigatedWithinDocument', { frameId: 'MAIN', url: 'http://localhost:5173/about' });
    assert.equal((await conn.next()).json.url, 'http://localhost:5173/about');

    // A new size is announced before the frame drawn at that size.
    cdp.emit('page', 'Page.screencastFrame', frameEvent(JPEG2, 2, { deviceWidth: 844, deviceHeight: 390 }));
    const resized = await conn.next();
    assert.equal(resized.kind, KIND.SCREEN_CONFIG);
    assert.equal(resized.json.width, 844);
    assert.deepEqual((await conn.next()).payload, JPEG2);

    conn.send(KIND.GOODBYE, { reason: 'revoked' });
    assert.deepEqual(await done, { reason: 'revoked' });
  });

  it('reconnects after an unexpected close and sends the screen again', { timeout: 10000 }, async () => {
    const server = await tunnelServer();
    const cdp = fakeCdp();
    const attempts = [];
    const { session, logs } = newSession(cdp, {
      tunnelUrl: server.url,
      backoff: (attempt) => {
        attempts.push(attempt);
        return 10;
      },
    });
    const done = session.run();

    let conn = await server.nextConnection();
    await conn.next(); // HELLO
    conn.send(KIND.WELCOME, {});
    cdp.emit('page', 'Page.screencastFrame', frameEvent(JPEG, 1));
    assert.equal((await conn.next()).kind, KIND.SCREEN_CONFIG);
    assert.equal((await conn.next()).kind, KIND.SCREEN_FRAME);

    // A GOODBYE that is not terminal, then the drop: reconnect, do not stop.
    conn.send(KIND.GOODBYE, { reason: 'restarting' });
    conn.socket.terminate();

    conn = await server.nextConnection();
    assert.equal((await conn.next()).kind, KIND.HELLO);
    conn.send(KIND.WELCOME, {});
    assert.equal((await conn.next()).kind, KIND.SCREEN_CONFIG, 'a new connection gets the config again');
    const again = await conn.next();
    assert.equal(again.kind, KIND.SCREEN_FRAME);
    assert.deepEqual(again.payload, JPEG, 'and the last frame, since a still page sends no new ones');

    assert.deepEqual(attempts, [0]);
    assert.ok(logs.some((l) => l.includes('다시 연결합니다')));
    assert.ok(logs.some((l) => l.includes('다시 연결했습니다')));

    // The backoff restarts after a successful WELCOME.
    conn.socket.terminate();
    conn = await server.nextConnection();
    assert.deepEqual(attempts, [0, 0]);

    session.stop();
    assert.deepEqual(await done, { reason: 'interrupt' });
  });

  it('drops a frame over 2 MiB but still acknowledges it, so the screencast keeps going', { timeout: 10000 }, async () => {
    const server = await tunnelServer();
    const cdp = fakeCdp();
    const { session, logs } = newSession(cdp, { tunnelUrl: server.url });
    session.run();
    const conn = await server.nextConnection();
    await conn.next();
    conn.send(KIND.WELCOME, {});
    await until(() => logs.some((l) => l.includes('연결했습니다')));

    cdp.emit('page', 'Page.screencastFrame', frameEvent(Buffer.alloc(MAX_PAYLOAD_BYTES + 1, 1), 1));
    await cdp.waitFor('Page.screencastFrameAck', (p) => p.sessionId === 1);
    cdp.emit('page', 'Page.screencastFrame', frameEvent(JPEG, 2));

    assert.equal((await conn.next()).kind, KIND.SCREEN_CONFIG);
    const next = await conn.next();
    assert.equal(next.kind, KIND.SCREEN_FRAME);
    assert.deepEqual(next.payload, JPEG, 'the oversized frame never went out');
    assert.ok(logs.some((l) => l.includes('2MiB')));
  });

  for (const [status, reason] of [
    [410, 'gone'],
    [404, 'gone'],
    [401, 'auth'],
    [403, 'auth'],
    [400, 'rejected'],
  ]) {
    it(`stops without retrying when the tunnel handshake answers ${status}`, { timeout: 10000 }, async () => {
      let hits = 0;
      const http = createServer();
      http.on('upgrade', (_req, socket) => {
        hits++;
        socket.end(`HTTP/1.1 ${status} No\r\nContent-Length: 0\r\nConnection: close\r\n\r\n`);
      });
      http.listen(0, '127.0.0.1');
      await once(http, 'listening');
      cleanups.push(() => new Promise((r) => http.close(r)));

      const cdp = fakeCdp();
      const { session } = newSession(cdp, {
        tunnelUrl: `ws://127.0.0.1:${http.address().port}/api/v1/device-shares/7/tunnel?v=1`,
        backoff: () => 10,
      });
      assert.deepEqual(await session.run(), { reason, status });
      await new Promise((r) => setTimeout(r, 50));
      assert.equal(hits, 1);
    });
  }
});

describe('share session: what the shared Chrome may do (§8)', () => {
  it('denies downloads and file choosers, guards navigation and starts the screencast, then navigates', async () => {
    const cdp = fakeCdp();
    const { session } = newSession(cdp);
    await session.setupPage();

    const methods = cdp.calls.map((c) => c.method);
    const call = (m) => cdp.calls.find((c) => c.method === m);
    assert.deepEqual(call('Browser.setDownloadBehavior').params, { behavior: 'deny' });
    assert.equal(call('Browser.setDownloadBehavior').scope, 'browser');
    assert.deepEqual(call('Page.setInterceptFileChooserDialog').params, { enabled: true });
    assert.deepEqual(call('Fetch.enable').params, {
      patterns: [{ resourceType: 'Document', requestStage: 'Request' }],
    });
    assert.deepEqual(call('Emulation.setDeviceMetricsOverride').params, {
      width: 390,
      height: 844,
      deviceScaleFactor: 2,
      mobile: true,
    });
    assert.deepEqual(call('Page.navigate').params, { url: 'http://localhost:5173/' });
    assert.deepEqual(call('Page.startScreencast').params, { format: 'jpeg', quality: 70, maxWidth: 1280, maxHeight: 1280 });
    assert.ok(methods.indexOf('Page.enable') < methods.indexOf('Page.setInterceptFileChooserDialog'));
    assert.ok(methods.indexOf('Fetch.enable') < methods.indexOf('Page.navigate'), 'guard before the first navigation');
    // Real Chrome refuses startScreencast for a moment after leaving about:blank, so it goes first.
    assert.ok(methods.indexOf('Page.startScreencast') < methods.indexOf('Page.navigate'));
    assert.equal(methods.at(-1), 'Page.navigate');
  });

  it('lets only the shared origin load as the top-level document', async () => {
    const cdp = fakeCdp();
    const { session, logs } = newSession(cdp);
    await session.setupPage();

    const paused = (requestId, url, frameId = 'MAIN') =>
      cdp.emit('page', 'Fetch.requestPaused', { requestId, request: { url }, frameId, resourceType: 'Document' });
    paused('ok', 'http://localhost:5173/about');
    paused('port', 'http://localhost:3000/admin');
    paused('lan', 'http://192.168.0.1/');
    paused('iframe', 'https://www.youtube.com/embed/x', 'CHILD');

    await cdp.waitFor('Fetch.continueRequest', (p) => p.requestId === 'ok');
    await cdp.waitFor('Fetch.continueRequest', (p) => p.requestId === 'iframe');
    for (const id of ['port', 'lan']) {
      const blocked = await cdp.waitFor('Fetch.failRequest', (p) => p.requestId === id);
      assert.equal(blocked.params.errorReason, 'BlockedByClient');
    }
    assert.ok(logs.some((l) => l.includes('http://localhost:3000') && !l.includes('/admin')));
  });

  it('sends the tab back when something reached another address anyway', async () => {
    const cdp = fakeCdp();
    const { session } = newSession(cdp);
    await session.setupPage();
    const before = cdp.calls.filter((c) => c.method === 'Page.navigate').length;
    cdp.emit('page', 'Page.frameNavigated', { frame: { id: 'MAIN', url: 'file:///etc/passwd' } });
    await until(() => cdp.calls.filter((c) => c.method === 'Page.navigate').length === before + 1);
    // Our own block's error page is left alone — it is what tells the viewer the move was refused.
    cdp.emit('page', 'Page.frameNavigated', {
      frame: { id: 'MAIN', url: 'chrome-error://chromewebdata/', unreachableUrl: 'http://localhost:3000/' },
    });
    await new Promise((r) => setTimeout(r, 20));
    assert.equal(cdp.calls.filter((c) => c.method === 'Page.navigate').length, before + 1);
  });

  it('closes any other tab the page opens', async () => {
    const cdp = fakeCdp();
    const { session } = newSession(cdp);
    await session.setupPage();
    cdp.emit('browser', 'Target.targetCreated', { targetInfo: { targetId: 'T1', type: 'page' } });
    cdp.emit('browser', 'Target.targetCreated', { targetInfo: { targetId: 'POPUP', type: 'page', openerId: 'T1' } });
    const closed = await cdp.waitFor('Target.closeTarget');
    assert.deepEqual(closed.params, { targetId: 'POPUP' });
    assert.equal(cdp.calls.filter((c) => c.method === 'Target.closeTarget').length, 1);
  });

  it('does not follow history to another origin', async () => {
    const cdp = fakeCdp();
    const { session } = newSession(cdp);
    await session.setupPage();
    cdp.page.send = (() => {
      const original = cdp.page.send;
      return async (method, params) =>
        method === 'Page.getNavigationHistory'
          ? { currentIndex: 1, entries: [{ id: 1, url: 'http://localhost:3000/' }, { id: 2, url: 'http://localhost:5173/' }] }
          : original(method, params);
    })();
    await session.navigate('back');
    assert.ok(!cdp.calls.some((c) => c.method === 'Page.navigateToHistoryEntry'));
  });
});

describe('CDP client', () => {
  it('matches results to requests, rejects errors, and routes session events', { timeout: 10000 }, async () => {
    // A WebSocket server standing in for Chrome's DevTools endpoint.
    const wss = new WebSocketServer({ host: '127.0.0.1', port: 0 });
    await once(wss, 'listening');
    cleanups.push(async () => {
      for (const c of wss.clients) c.terminate();
      await new Promise((r) => wss.close(r));
    });
    const seen = [];
    wss.on('connection', (socket) => {
      socket.on('message', (data) => {
        const msg = JSON.parse(String(data));
        seen.push(msg);
        const reply = (body) => socket.send(JSON.stringify({ id: msg.id, ...body }));
        if (msg.method === 'Target.getTargets') {
          reply({ result: { targetInfos: [{ targetId: 'B', type: 'browser' }, { targetId: 'T1', type: 'page' }] } });
        } else if (msg.method === 'Target.attachToTarget') {
          reply({ result: { sessionId: 'S1' } });
        } else if (msg.method === 'Nope.fail') {
          reply({ error: { code: -32601, message: "'Nope.fail' wasn't found" } });
        } else {
          reply({ result: {} });
          if (msg.method === 'Page.enable') {
            socket.send(JSON.stringify({ method: 'Page.frameNavigated', params: { frame: { id: 'other' } }, sessionId: 'S2' }));
            socket.send(JSON.stringify({ method: 'Page.frameNavigated', params: { frame: { id: 'MAIN' } }, sessionId: 'S1' }));
          }
        }
      });
    });

    const conn = await CdpConnection.connect(`ws://127.0.0.1:${wss.address().port}/devtools/browser/x`);
    cleanups.push(async () => conn.close());
    const { targetId, page } = await attachFirstPage(conn);
    assert.equal(targetId, 'T1');
    assert.deepEqual(seen.at(-1).params, { targetId: 'T1', flatten: true });

    const navigated = [];
    page.on('Page.frameNavigated', ({ frame }) => navigated.push(frame.id));
    await page.send('Page.enable');
    assert.equal(seen.at(-1).sessionId, 'S1');
    await until(() => navigated.length > 0);
    assert.deepEqual(navigated, ['MAIN'], 'only the attached session’s events reach the page scope');

    await assert.rejects(() => conn.send('Nope.fail'), /Nope\.fail/);
  });
});

describe('qeploy share web', () => {
  const share = {
    shareId: 7,
    viewUrl: 'http://k3x9.share.localhost:8080/',
    controlUrl: 'http://k3x9.share.localhost:8080/#c=secret',
    tunnelUrl: 'ws://localhost:8080/api/v1/device-shares/7/tunnel?v=1',
    expiresAt: '2026-11-12T13:00:00',
  };

  function fakeClient(overrides = {}) {
    const client = {
      token: 'qp_cli',
      created: null,
      revoked: [],
      createDeviceShare: async (body) => {
        client.created = body;
        return share;
      },
      revokeDeviceShare: async (id) => {
        client.revoked.push(id);
        return null;
      },
      ...overrides,
    };
    return client;
  }

  /** Everything outside the process, faked; `session` decides how the share ends. */
  function fakeRuntime({ chromePath = '/fake/chrome', end } = {}) {
    const events = [];
    const rt = {
      events,
      interrupt: null,
      chromeExit: null,
      sessionOptions: null,
      findChrome: () => chromePath,
      launchChrome: async (options) => {
        events.push(['launch', options]);
        return {
          wsUrl: 'ws://127.0.0.1:9/devtools/browser/x',
          onExit: (fn) => {
            rt.chromeExit = fn;
          },
          close: async () => events.push(['chrome.close']),
          killNow: () => events.push(['chrome.kill']),
        };
      },
      connectCdp: async (url) => {
        events.push(['cdp', url]);
        return { close: () => events.push(['cdp.close']) };
      },
      attachPage: async () => ({ targetId: 'T1', browser: {}, page: {} }),
      createSession: (options) => {
        rt.sessionOptions = options;
        let resolve;
        const done = new Promise((r) => {
          resolve = r;
        });
        return {
          run: () => {
            setImmediate(() => end?.(rt));
            return done;
          },
          stop: (reason) => resolve({ reason }),
        };
      },
      askControl: async () => false,
      onInterrupt: (fn) => {
        rt.interrupt = fn;
        events.push(['onInterrupt']);
        return () => events.push(['offInterrupt']);
      },
      forceExit: (code) => events.push(['forceExit', code]),
    };
    return rt;
  }

  async function shareCli(argv, { client = fakeClient(), runtime = fakeRuntime() } = {}) {
    const out = [];
    const err = [];
    const code = await run(argv, {
      env: {},
      createClient: () => client,
      confirm: async () => true,
      out: (t) => out.push(t),
      err: (t) => err.push(t),
      share: runtime,
    });
    return { code, out: out.join('\n'), err: err.join('\n'), client, runtime };
  }

  it('prints both links, and on Ctrl+C revokes the link, closes Chrome and exits 0', async () => {
    const runtime = fakeRuntime({ end: (rt) => rt.interrupt() });
    const { code, out, err, client } = await shareCli(
      ['share', 'web', 'http://localhost:5173', '--viewport', '390x844', '--ttl', '30'],
      { runtime }
    );
    assert.equal(code, EXIT.OK, err);
    assert.deepEqual(client.created, { kind: 'WEB', targetLabel: 'http://localhost:5173', ttlMinutes: 30 });
    assert.match(out, /보기 링크: +http:\/\/k3x9\.share\.localhost:8080\//);
    assert.match(out, /내 제어 링크: +http:\/\/k3x9\.share\.localhost:8080\/#c=secret/);
    assert.match(out, /나만 쓰는 링크/);
    assert.match(out, /30분 뒤 만료/);
    assert.deepEqual(client.revoked, [7]);

    const names = runtime.events.map((e) => e[0]);
    assert.ok(names.includes('chrome.close'));
    assert.ok(names.includes('cdp.close'));
    assert.ok(names.includes('offInterrupt'));
    assert.deepEqual(runtime.events.find((e) => e[0] === 'launch')[1], {
      executable: '/fake/chrome',
      headed: false,
      viewport: { width: 390, height: 844, mobile: true },
    });

    const o = runtime.sessionOptions;
    assert.equal(o.token, 'qp_cli');
    assert.equal(o.tunnelUrl, share.tunnelUrl);
    assert.equal(o.target.href, 'http://localhost:5173/');
    assert.equal(o.targetLabel, 'http://localhost:5173');
    assert.match(o.cliVersion, /^\d+\.\d+\.\d+$/);
    assert.match(err, /링크를 회수했습니다/);
  });

  it('prints only the API data on stdout with --json', async () => {
    const runtime = fakeRuntime({ end: (rt) => rt.interrupt() });
    const { code, out } = await shareCli(['share', 'web', 'http://localhost:5173', '--json'], { runtime });
    assert.equal(code, EXIT.OK);
    assert.deepEqual(JSON.parse(out), share);
  });

  it('launches a visible window with --headed', async () => {
    const runtime = fakeRuntime({ end: (rt) => rt.interrupt() });
    await shareCli(['share', 'web', 'http://localhost:5173', '--headed'], { runtime });
    assert.equal(runtime.events.find((e) => e[0] === 'launch')[1].headed, true);
  });

  it('leaves the link alone when the server already ended it', async () => {
    const runtime = fakeRuntime();
    runtime.createSession = () => ({ run: async () => ({ reason: 'revoked' }), stop() {} });
    const { code, client, err } = await shareCli(['share', 'web', 'http://localhost:5173'], { runtime });
    assert.equal(code, EXIT.OK);
    assert.deepEqual(client.revoked, []);
    assert.match(err, /회수되어/);
  });

  it('revokes and exits 1 when Chrome goes away', async () => {
    const runtime = fakeRuntime({ end: (rt) => rt.chromeExit() });
    const { code, client } = await shareCli(['share', 'web', 'http://localhost:5173'], { runtime });
    assert.equal(code, EXIT.ERROR);
    assert.deepEqual(client.revoked, [7]);
  });

  it('exits 3 when the tunnel refuses the token', async () => {
    const runtime = fakeRuntime();
    runtime.createSession = () => ({ run: async () => ({ reason: 'auth', status: 403 }), stop() {} });
    const { code, err } = await shareCli(['share', 'web', 'http://localhost:5173'], { runtime });
    assert.equal(code, EXIT.AUTH);
    assert.match(err, /WRITE/);
  });

  it('revokes the link it just made when Chrome fails to start', async () => {
    const runtime = fakeRuntime();
    runtime.launchChrome = async () => {
      throw new Error('Chrome 이 시작하자마자 종료됐습니다');
    };
    const { code, client, err } = await shareCli(['share', 'web', 'http://localhost:5173'], { runtime });
    assert.equal(code, EXIT.ERROR);
    assert.deepEqual(client.revoked, [7]);
    assert.match(err, /시작하자마자/);
  });

  it('refuses a plain ws:// tunnel address off loopback before sending the token there', async () => {
    const client = fakeClient({
      createDeviceShare: async () => ({ ...share, tunnelUrl: 'ws://q.test/api/v1/device-shares/7/tunnel?v=1' }),
    });
    const runtime = fakeRuntime();
    const { code, err } = await shareCli(['share', 'web', 'http://localhost:5173'], { client, runtime });
    assert.equal(code, EXIT.ERROR);
    assert.match(err, /wss/);
    assert.equal(runtime.sessionOptions, null);
    assert.deepEqual(client.revoked, [7]);
  });

  it('says how to fix a missing Chrome, before making a link', async () => {
    const runtime = fakeRuntime({ chromePath: null });
    const { code, err, client } = await shareCli(['share', 'web', 'http://localhost:5173'], { runtime });
    assert.equal(code, EXIT.ERROR);
    assert.match(err, /Chrome 을 찾지 못했습니다/);
    assert.match(err, /CHROME_PATH/);
    assert.equal(client.created, null);
  });

  it('refuses bad arguments as usage errors, before making a link', async () => {
    for (const argv of [
      ['share'],
      ['share', 'android', 'emulator-5554'],
      ['share', 'web'],
      ['share', 'web', 'file:///Users/me/index.html'],
      ['share', 'web', 'http://localhost:5173', '--viewport', 'big'],
      ['share', 'web', 'http://localhost:5173', '--ttl', '0'],
      ['share', 'web', 'http://localhost:5173', '--ttl'],
    ]) {
      const { code, client } = await shareCli(argv);
      assert.equal(code, EXIT.USAGE, argv.join(' '));
      assert.equal(client.created, null, argv.join(' '));
    }
  });

  it('a second Ctrl+C stops waiting and kills Chrome', async () => {
    const runtime = fakeRuntime({
      end: (rt) => {
        rt.interrupt();
        rt.interrupt();
      },
    });
    await shareCli(['share', 'web', 'http://localhost:5173'], { runtime });
    const names = runtime.events.map((e) => e[0]);
    assert.ok(names.includes('chrome.kill'));
    assert.deepEqual(runtime.events.find((e) => e[0] === 'forceExit'), ['forceExit', 130]);
  });
});
