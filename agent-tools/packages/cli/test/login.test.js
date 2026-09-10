import assert from 'node:assert/strict';
import { describe, it, beforeEach, afterEach } from 'node:test';
import { mkdtempSync, rmSync, statSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Readable, Writable } from 'node:stream';
import { configPath, readConfig, resolveToken, resolveApiUrl, writeConfig } from '@qeploy/client/config';
import { run, EXIT } from '../src/index.js';
import { readSecret } from '../src/prompt.js';

let home;
let env;

beforeEach(() => {
  home = mkdtempSync(join(tmpdir(), 'qeploy-cli-'));
  env = { QEPLOY_CONFIG_HOME: home };
});
afterEach(() => rmSync(home, { recursive: true, force: true }));

/** Runs the CLI with an isolated config directory and no real process behind it. */
async function cli(argv, { client = {}, secret = 'jwt-value', envOverride } = {}) {
  const out = [];
  const err = [];
  const code = await run(argv, {
    env: envOverride ?? env,
    readSecret: async () => secret,
    createClient: (flags, tokenOverride) => {
      client.__token = tokenOverride;
      return client;
    },
    confirm: async () => true,
    out: (t) => out.push(t),
    err: (t) => err.push(t),
  });
  return { code, out: out.join('\n'), err: err.join('\n'), client };
}

describe('config file', () => {
  it('stores the token with owner-only permissions', () => {
    // A credential written with the default umask is world-readable on a shared machine.
    const path = writeConfig({ token: 'qp_secret' }, env);
    assert.equal(statSync(path).mode & 0o777, 0o600);
  });

  it('tightens permissions on a file that already existed', () => {
    // writeFileSync's mode only applies at creation, so a file written before this rule existed
    // would silently keep its old permissions.
    const path = configPath(env);
    mkdirSync(join(home, 'qeploy'), { recursive: true });
    writeFileSync(path, '{}', { mode: 0o644 });
    writeConfig({ token: 'qp_secret' }, env);
    assert.equal(statSync(path).mode & 0o777, 0o600);
  });

  it('treats a corrupt file as "not logged in" rather than crashing', () => {
    mkdirSync(join(home, 'qeploy'), { recursive: true });
    writeFileSync(configPath(env), 'not json at all');
    assert.deepEqual(readConfig(env), {});
    assert.equal(resolveToken(env).token, null);
  });

  it('lets the environment win over the stored token', () => {
    // So a CI job that sets QEPLOY_TOKEN is not silently overridden by a developer's login file.
    writeConfig({ token: 'qp_from_file' }, env);
    assert.equal(resolveToken({ ...env, QEPLOY_TOKEN: 'qp_from_env' }).token, 'qp_from_env');
    assert.equal(resolveToken(env).token, 'qp_from_file');
  });

  it('resolves the api url by flag, then env, then file, then default', () => {
    writeConfig({ apiUrl: 'https://from-file' }, env);
    assert.equal(resolveApiUrl({ apiUrl: 'https://from-flag' }, env), 'https://from-flag');
    assert.equal(resolveApiUrl({}, { ...env, QEPLOY_API_URL: 'https://from-env' }), 'https://from-env');
    assert.equal(resolveApiUrl({}, env), 'https://from-file');
    assert.equal(resolveApiUrl({}, { QEPLOY_CONFIG_HOME: join(home, 'empty') }), 'https://qeploy.com');
  });
});

describe('login', () => {
  const issuing = () => ({
    issueApiToken: async (opts) => ({
      token: 'qp_issued_value',
      info: { apiTokenId: 7, scope: opts.scope, expiresAt: '2026-12-09T00:00:00' },
    }),
  });

  it('trades the browser token for a PAT and stores it', async () => {
    const client = issuing();
    const { code } = await cli(['login'], { client, secret: 'eyJhbGciOi.jwt.value' });
    assert.equal(code, EXIT.OK);
    assert.equal(client.__token, 'eyJhbGciOi.jwt.value', 'JWT 로 발급을 호출해야 한다');
    assert.equal(readConfig(env).token, 'qp_issued_value');
    assert.equal(readConfig(env).apiTokenId, 7);
  });

  it('never prints the token it just saved', async () => {
    // It is already stored; echoing it would put a 90-day credential in the scrollback for nothing.
    const { out, err } = await cli(['login'], { client: issuing() });
    assert.ok(!out.includes('qp_issued_value'), 'stdout 에 토큰이 있으면 안 된다');
    assert.ok(!err.includes('qp_issued_value'), 'stderr 에 토큰이 있으면 안 된다');
  });

  it('defaults to READ', async () => {
    let seen = null;
    await cli(['login'], { client: { issueApiToken: async (o) => { seen = o; return { token: 'qp_x', info: {} }; } } });
    assert.equal(seen.scope, 'READ');
  });

  it('labels the token with the machine so a list is later readable', async () => {
    let seen = null;
    await cli(['login'], { client: { issueApiToken: async (o) => { seen = o; return { token: 'qp_x', info: {} }; } } });
    assert.match(seen.label, /qeploy CLI \(.+\)/);
  });

  it('rejects a PAT pasted where the browser token belongs', async () => {
    // It would "work" until it expired, and then fail for a reason the user could not deduce.
    const { code, err } = await cli(['login'], { client: issuing(), secret: 'qp_already_a_token' });
    assert.equal(code, EXIT.USAGE);
    assert.match(err, /개인 액세스 토큰|accessToken/);
  });

  it('rejects an empty paste', async () => {
    const { code } = await cli(['login'], { client: issuing(), secret: '   ' });
    assert.equal(code, EXIT.USAGE);
  });

  it('rejects an unknown scope instead of guessing', async () => {
    const { code } = await cli(['login', '--scope', 'ADMIN'], { client: issuing() });
    assert.equal(code, EXIT.USAGE);
  });

  it('does not require an existing token to run', async () => {
    // The whole point is that it runs when nothing is configured yet.
    const { code } = await cli(['login'], { client: issuing() });
    assert.equal(code, EXIT.OK);
  });
});

describe('logout', () => {
  it('removes the stored token and says the server copy is still alive', async () => {
    writeConfig({ token: 'qp_x', apiTokenId: 12 }, env);
    const { code, out } = await cli(['logout']);
    assert.equal(code, EXIT.OK);
    assert.equal(resolveToken(env).token, null);
    // Silently leaving a live credential the user believes is gone would be worse than not offering
    // logout at all.
    assert.match(out, /#12/);
    assert.match(out, /폐기/);
  });

  it('is not an error when nothing was stored', async () => {
    const { code, out } = await cli(['logout']);
    assert.equal(code, EXIT.OK);
    assert.match(out, /저장된 토큰이 없습니다/);
  });
});

describe('secret prompt', () => {
  it('reads piped input whole so a paste from a file works', async () => {
    const input = Readable.from(['eyJ.jwt.value\n']);
    input.isTTY = false;
    assert.equal(await readSecret('x: ', { input, output: new Writable({ write(c, e, cb) { cb(); } }) }),
      'eyJ.jwt.value');
  });

  it('does not echo what it reads', async () => {
    const written = [];
    const input = Readable.from(['secret-value\n']);
    input.isTTY = false;
    await readSecret('accessToken: ', {
      input,
      output: new Writable({ write(c, e, cb) { written.push(String(c)); cb(); } }),
    });
    assert.ok(!written.join('').includes('secret-value'));
  });
});
