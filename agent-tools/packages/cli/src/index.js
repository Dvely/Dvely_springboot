import { QeployApiError } from '@qeploy/client';
import { commands } from './commands.js';
import { UsageError } from './confirm.js';

export const VERSION = '0.1.0';

/**
 * Exit codes are this CLI's real interface in CI, where nobody reads stderr until something breaks.
 * Separating auth from a general failure lets a pipeline tell "the token expired, rotate it" from
 * "the deploy failed, look at the build" without parsing messages.
 */
export const EXIT = { OK: 0, ERROR: 1, USAGE: 2, AUTH: 3 };

/** Flags that take a value; everything else is a boolean switch. */
const VALUED = new Set(['target-version', 'scope', 'api-url', 'expires-in-days']);

const camel = (name) => name.replace(/-([a-z])/g, (_, c) => c.toUpperCase());

/**
 * Splits argv into positional args and flags. Written out rather than pulled from a dependency:
 * the whole grammar is a dozen lines, and a published CLI with one dependency installs faster than
 * one with twenty.
 */
export function parseArgs(argv) {
  const args = [];
  const flags = {};
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i];
    if (token === '--') {
      args.push(...argv.slice(i + 1));
      break;
    }
    if (token === '-y') {
      flags.yes = true;
      continue;
    }
    if (token === '-h') {
      flags.help = true;
      continue;
    }
    if (!token.startsWith('--')) {
      args.push(token);
      continue;
    }
    const body = token.slice(2);
    const eq = body.indexOf('=');
    if (eq !== -1) {
      flags[camel(body.slice(0, eq))] = body.slice(eq + 1);
      continue;
    }
    if (VALUED.has(body)) {
      const value = argv[++i];
      // Consuming the next token blindly would swallow the following flag and leave the user with
      // "--scope" set to "--yes", which fails much later and much less clearly.
      if (value === undefined || value.startsWith('--')) {
        throw new UsageError(`--${body} 에 값이 필요합니다.`);
      }
      flags[camel(body)] = value;
      continue;
    }
    flags[camel(body)] = true;
  }
  flags.assumeYes = flags.yes === true;
  return { args, flags };
}

export function helpText() {
  // Two lines per command rather than an aligned column: `env:set` requires --scope, and a required
  // flag that only appears in the README is a flag users get wrong. Alignment would push every
  // summary off the right edge to make room for it.
  const rows = Object.values(commands)
    .map((c) => `  ${c.usage}\n      ${c.summary}`)
    .join('\n');
  return `qeploy ${VERSION} — Qeploy 커맨드라인

사용법:
  qeploy <명령> [인자] [옵션]

명령:
${rows}

공통 옵션:
  --json               응답을 그대로 JSON 으로 출력 (CI·스크립트용)
  --yes, -y            확인 없이 진행. 비대화형 환경에서는 필수
  --api-url <url>      API 주소. 기본값은 QEPLOY_API_URL
  --help, -h           이 도움말
  --version            버전

환경변수:
  QEPLOY_TOKEN         개인 액세스 토큰. 없으면 qeploy login 이 저장한 것을 씁니다
  QEPLOY_API_URL       API 주소
  QEPLOY_CONFIG_HOME   설정 위치 (기본: XDG_CONFIG_HOME 또는 ~/.config)

처음이라면 qeploy login 을 한 번 실행하세요. 토큰을 ~/.config/qeploy/config.json 에
0600 으로 저장하므로, 이후에는 환경변수를 매번 내보낼 필요가 없습니다.

토큰은 옵션으로 받지 않습니다 — 명령행 인자는 셸 히스토리와 프로세스 목록에 남습니다.

종료 코드: 0 성공 · 1 실패 · 2 사용법 오류 · 3 인증 실패`;
}

/**
 * @param {string[]} argv arguments after the program name
 * @param {{createClient: Function, confirm: Function, out: Function, err: Function}} deps
 * @returns {Promise<number>} exit code
 */
export async function run(argv, deps) {
  const { out, err } = deps;

  let parsed;
  try {
    parsed = parseArgs(argv);
  } catch (e) {
    err(e.message);
    return EXIT.USAGE;
  }
  const { args, flags } = parsed;

  const name = args[0];

  // Checked before help so that bare `qeploy --version` prints a version rather than a usage error.
  if (flags.version && !name) {
    out(VERSION);
    return EXIT.OK;
  }
  if (!name) {
    out(helpText());
    // Asking for help is not a usage error; running with no arguments at all is.
    return flags.help ? EXIT.OK : EXIT.USAGE;
  }

  const command = commands[name];
  if (flags.help) {
    // `qeploy deploy --help` should answer about deploy, not print the whole catalogue again.
    out(command ? `${command.usage}\n  ${command.summary}` : helpText());
    return EXIT.OK;
  }
  if (!command) {
    err(`알 수 없는 명령입니다: ${name}\nqeploy --help 로 사용 가능한 명령을 볼 수 있습니다.`);
    return EXIT.USAGE;
  }

  let client = null;
  if (command.auth !== false) {
    // `login` runs before there is anything to authenticate with, so it builds its own client from
    // the credential the user is in the middle of providing.
    try {
      client = deps.createClient(flags);
    } catch (e) {
      err(e.message);
      return EXIT.USAGE;
    }
  }

  try {
    const result = await command.run({
      client,
      args: args.slice(1),
      flags,
      confirm: deps.confirm,
      deps,
      out,
      err,
    });
    // A cancelled write is not a failure of the command, but it is not the requested outcome
    // either; CI should be able to notice that nothing happened.
    if (result.cancelled) {
      err(result.text);
      return EXIT.ERROR;
    }
    out(flags.json ? JSON.stringify(result.data ?? null, null, 2) : result.text);
    return EXIT.OK;
  } catch (e) {
    if (e instanceof UsageError) {
      err(`${e.message}\n사용법: ${command.usage}`);
      return EXIT.USAGE;
    }
    if (e instanceof QeployApiError) {
      err(e.toAgentMessage());
      return e.status === 401 || e.status === 403 ? EXIT.AUTH : EXIT.ERROR;
    }
    err(`실패했습니다: ${e.message}`);
    return EXIT.ERROR;
  }
}
