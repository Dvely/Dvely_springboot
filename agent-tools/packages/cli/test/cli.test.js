import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { QeployApiError } from '@qeploy/client';
import { run, parseArgs, EXIT, helpText } from '../src/index.js';
import { confirm, UsageError } from '../src/confirm.js';
import { renderTable } from '../src/render.js';
import { Readable, Writable } from 'node:stream';

/** Runs the CLI with no process, no socket and no terminal — everything outside is injected. */
async function cli(argv, { client = {}, confirmAnswer = true } = {}) {
  const out = [];
  const err = [];
  const code = await run(argv, {
    createClient: () => client,
    confirm: async () => confirmAnswer,
    out: (t) => out.push(t),
    err: (t) => err.push(t),
  });
  return { code, out: out.join('\n'), err: err.join('\n') };
}

describe('argument parsing', () => {
  it('separates positionals from flags', () => {
    const { args, flags } = parseArgs(['deploy', '12', '--yes', '--json']);
    assert.deepEqual(args, ['deploy', '12']);
    assert.equal(flags.yes, true);
    assert.equal(flags.json, true);
  });

  it('reads a valued flag in both forms', () => {
    assert.equal(parseArgs(['env:set', '--scope', 'PREVIEW']).flags.scope, 'PREVIEW');
    assert.equal(parseArgs(['env:set', '--scope=PREVIEW']).flags.scope, 'PREVIEW');
  });

  it('converts kebab flags to camelCase', () => {
    assert.equal(parseArgs(['deploy', '--target-version', 'v1.2.0']).flags.targetVersion, 'v1.2.0');
  });

  it('refuses a valued flag whose value is the next flag', () => {
    // Swallowing it would leave --scope set to "--yes" and fail much later, much less clearly.
    assert.throws(() => parseArgs(['env:set', '--scope', '--yes']), UsageError);
  });

  it('treats -h and -y as flags, not positionals', () => {
    assert.deepEqual(parseArgs(['-h']).args, []);
    assert.equal(parseArgs(['-h']).flags.help, true);
    assert.equal(parseArgs(['-y']).flags.assumeYes, true);
  });

  it('stops parsing flags after --', () => {
    const { args } = parseArgs(['env:set', '1', '--', '--weird=value']);
    assert.deepEqual(args, ['env:set', '1', '--weird=value']);
  });
});

describe('exit codes', () => {
  it('uses 2 for an unknown command', async () => {
    const { code, err } = await cli(['nope']);
    assert.equal(code, EXIT.USAGE);
    assert.match(err, /알 수 없는 명령/);
  });

  it('uses 2 for a missing or malformed id', async () => {
    assert.equal((await cli(['project'])).code, EXIT.USAGE);
    assert.equal((await cli(['project', 'abc'])).code, EXIT.USAGE);
  });

  it('uses 3 for an auth failure so CI can tell it from a build failure', async () => {
    const client = {
      listProjects: async () => {
        throw new QeployApiError(401, 'UNAUTHORIZED', '인증 필요', '/api/v1/projects');
      },
    };
    const { code, err } = await cli(['projects'], { client });
    assert.equal(code, EXIT.AUTH);
    assert.match(err, /새 토큰/);
  });

  it('uses 3 when a READ token is refused on a write', async () => {
    const client = {
      listProjects: async () => [{ projectId: 1, name: 'demo' }],
      deploy: async () => {
        throw new QeployApiError(403, 'FORBIDDEN', '읽기 전용', '/api/v1/projects/1/deployments');
      },
    };
    const { code, err } = await cli(['deploy', '1', '--yes'], { client });
    assert.equal(code, EXIT.AUTH);
    assert.match(err, /읽기 전용|WRITE/);
  });

  it('uses 1 for an ordinary API failure', async () => {
    const client = {
      listProjects: async () => {
        throw new QeployApiError(500, null, '서버 오류', '/api/v1/projects');
      },
    };
    assert.equal((await cli(['projects'], { client })).code, EXIT.ERROR);
  });

  it('prints a version for bare --version', async () => {
    const { code, out } = await cli(['--version']);
    assert.equal(code, EXIT.OK);
    assert.match(out, /^\d+\.\d+\.\d+$/);
  });
});

describe('confirmation', () => {
  it('refuses instead of prompting when input is not a terminal', async () => {
    // A prompt in CI has nobody to answer it and would hang the job until the runner times out.
    await assert.rejects(
      () => confirm('진행할까요?', { assumeYes: false, input: { isTTY: false } }),
      (e) => e instanceof UsageError && /--yes/.test(e.message)
    );
  });

  it('skips the prompt entirely with --yes', async () => {
    assert.equal(await confirm('진행할까요?', { assumeYes: true, input: { isTTY: false } }), true);
  });

  it('reports a cancelled write as a non-zero exit', async () => {
    let deployed = false;
    const client = {
      listProjects: async () => [{ projectId: 1, name: 'demo' }],
      deploy: async () => { deployed = true; },
    };
    const { code, err } = await cli(['deploy', '1'], { client, confirmAnswer: false });
    assert.equal(deployed, false, '취소했는데 배포가 호출되면 안 된다');
    assert.equal(code, EXIT.ERROR);
    assert.match(err, /취소/);
  });
});

describe('deploy', () => {
  it('names the project in the prompt so a wrong target is visible', async () => {
    let asked = '';
    const client = {
      // Shaped like the real list response: the overview endpoint has no name field at all, and a
      // mock that invented one is what let a broken prompt pass review once already.
      listProjects: async () => [{ projectId: 7, name: 'my-shop' }],
      deploy: async () => ({ deploymentId: 3, status: 'PENDING' }),
    };
    await run(['deploy', '7'], {
      createClient: () => client,
      confirm: async (q) => { asked = q; return true; },
      out: () => {},
      err: () => {},
    });
    assert.match(asked, /my-shop/);
    assert.match(asked, /7/);
  });

  it('still asks when the name lookup fails', async () => {
    let asked = null;
    const client = {
      listProjects: async () => { throw new Error('목록 조회 실패'); },
      deploy: async () => ({ deploymentId: 3, status: 'PENDING' }),
    };
    await run(['deploy', '7'], {
      createClient: () => client,
      confirm: async (q) => { asked = q; return true; },
      out: () => {},
      err: () => {},
    });
    // Losing the confirmation would be worse than losing the decoration.
    assert.ok(asked !== null && /7/.test(asked));
  });

  it('says "승인 대기" rather than claiming a deploy started', async () => {
    const client = {
      listProjects: async () => [{ projectId: 1, name: 'demo' }],
      deploy: async () => ({ deploymentId: 3, status: 'PENDING', approvalIds: [9] }),
    };
    const { code, out } = await cli(['deploy', '1', '--yes'], { client });
    assert.equal(code, EXIT.OK);
    assert.match(out, /승인 대기/);
    assert.ok(!/배포했습니다|배포 완료/.test(out), '승인 대기인데 완료라고 말하면 안 된다');
  });

  it('passes the target version through', async () => {
    let seen = null;
    const client = {
      listProjects: async () => [{ projectId: 1, name: 'demo' }],
      deploy: async (id, opts) => { seen = { id, opts }; return { deploymentId: 1, status: 'PENDING' }; },
    };
    await cli(['deploy', '5', '--target-version', 'v2.0.0', '--yes'], { client });
    assert.equal(seen.id, 5);
    assert.equal(seen.opts.deployTargetType, 'VERSION');
    assert.equal(seen.opts.versionName, 'v2.0.0');
  });

  it('emits the raw response with --json', async () => {
    const client = {
      listProjects: async () => [{ projectId: 1, name: 'demo' }],
      deploy: async () => ({ deploymentId: 3, status: 'PENDING', approvalIds: [] }),
    };
    const { out } = await cli(['deploy', '1', '--yes', '--json'], { client });
    assert.deepEqual(JSON.parse(out), { deploymentId: 3, status: 'PENDING', approvalIds: [] });
  });
});

describe('env:set', () => {
  const base = {
    listEnvironmentVariables: async () => [
      { environmentVariableId: 4, key: 'API_URL', scope: 'PRODUCTION', value: 'old', secret: false },
    ],
  };

  it('updates when the key already exists in that scope', async () => {
    let updated = null;
    const client = {
      ...base,
      updateEnvironmentVariable: async (p, id, body) => { updated = { p, id, body }; return {}; },
    };
    const { code, out } = await cli(
      ['env:set', '1', 'API_URL=https://new', '--scope', 'PRODUCTION', '--yes'], { client });
    assert.equal(code, EXIT.OK);
    assert.equal(updated.id, 4);
    assert.equal(updated.body.value, 'https://new');
    assert.match(out, /수정/);
  });

  it('creates when the same key exists only in the other scope', async () => {
    // scope is part of the identity — treating a PRODUCTION match as a PREVIEW hit would overwrite
    // the wrong environment.
    let created = null;
    const client = { ...base, createEnvironmentVariable: async (p, body) => { created = body; return {}; } };
    const { out } = await cli(
      ['env:set', '1', 'API_URL=https://preview', '--scope', 'PREVIEW', '--yes'], { client });
    assert.equal(created.scope, 'PREVIEW');
    assert.equal(created.key, 'API_URL');
    assert.match(out, /생성/);
  });

  it('splits on the first = so a value may contain more', async () => {
    let created = null;
    const client = {
      listEnvironmentVariables: async () => [],
      createEnvironmentVariable: async (p, body) => { created = body; return {}; },
    };
    await cli(['env:set', '1', 'DB=postgres://u:p@h/db?a=b', '--scope', 'PRODUCTION', '--yes'], { client });
    assert.equal(created.key, 'DB');
    assert.equal(created.value, 'postgres://u:p@h/db?a=b');
  });

  it('requires an explicit scope', async () => {
    // There is no COMMON scope, so a default would silently write to one environment.
    const { code, err } = await cli(['env:set', '1', 'A=b', '--yes'], { client: base });
    assert.equal(code, EXIT.USAGE);
    assert.match(err, /PREVIEW|PRODUCTION/);
  });

  it('rejects a missing KEY=VALUE', async () => {
    const { code } = await cli(['env:set', '1', 'JUSTAKEY', '--scope', 'PREVIEW', '--yes'], { client: base });
    assert.equal(code, EXIT.USAGE);
  });

  it('confirms before the one-way flip to secret', async () => {
    let asked = null;
    const client = { ...base, updateEnvironmentVariable: async () => ({}) };
    await run(['env:set', '1', 'API_URL=x', '--scope', 'PRODUCTION', '--secret'], {
      createClient: () => client,
      confirm: async (q) => { asked = q; return true; },
      out: () => {},
      err: () => {},
    });
    assert.match(asked, /되돌릴 수 없/);
  });
});

describe('rendering', () => {
  it('falls back to JSON when the response no longer matches the columns', () => {
    // A renamed field would otherwise print a grid of dashes and hide the change.
    const text = renderTable([{ totallyDifferent: 1 }], [{ key: 'projectId', label: 'ID' }]);
    assert.deepEqual(JSON.parse(text), [{ totallyDifferent: 1 }]);
  });

  it('says (없음) for an empty list', () => {
    assert.equal(renderTable([], [{ key: 'projectId', label: 'ID' }]), '(없음)');
  });

  it('aligns columns for wide characters', () => {
    const text = renderTable(
      [{ name: '한글이름' }, { name: 'ascii' }],
      [{ key: 'name', label: '이름' }]
    );
    // Every line ends at the same visual column only if Hangul counted as two.
    const lines = text.split('\n');
    assert.equal(lines[1], '─'.repeat(8));
  });

  it('marks a secret value rather than leaving it blank', async () => {
    const client = {
      listEnvironmentVariables: async () => [
        { environmentVariableId: 1, key: 'TOKEN', scope: 'PRODUCTION', value: null },
      ],
    };
    const { out } = await cli(['env', '1'], { client });
    assert.match(out, /\(secret\)/);
  });
});

describe('help', () => {
  it('shows every command, including the required flags', () => {
    const text = helpText();
    for (const name of ['projects', 'deploy', 'env:set', 'retry']) {
      assert.ok(text.includes(name), `${name} 이 도움말에 없다`);
    }
    // --scope is required; a required flag that only lives in the README is one users get wrong.
    assert.match(text, /--scope/);
  });

  it('does not offer a token flag', () => {
    // Command-line arguments land in shell history and in ps output.
    assert.ok(!/--token/.test(helpText()));
  });
});

describe('backing out of a prompt', () => {
  it('treats EOF at the prompt as "no", not as a crash', async () => {
    // Ctrl+D rejects readline's question. Reporting that as a failure would tell the user something
    // broke when they simply changed their mind. A stream that ends immediately is that EOF.
    const input = Readable.from([]);
    input.isTTY = true;
    const written = [];
    const output = new Writable({ write(chunk, _e, cb) { written.push(String(chunk)); cb(); } });

    assert.equal(await confirm('진행할까요?', { assumeYes: false, input, output }), false);
  });
});
