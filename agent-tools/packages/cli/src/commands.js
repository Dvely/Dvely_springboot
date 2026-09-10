import { hostname } from 'node:os';
import { renderTable, renderDetail } from './render.js';
import { UsageError } from './confirm.js';
import { clearConfig, configPath, readConfig, resolveApiUrl, writeConfig } from '@qeploy/client/config';
import { readSecret } from './prompt.js';

/**
 * The command table.
 *
 * Each command returns `{data, text}`: `data` is what `--json` prints, `text` is what a human sees.
 * Keeping both means the machine output is the API response verbatim — no field lost to formatting —
 * while the terminal output stays readable.
 */

const projectColumns = [
  { key: 'projectId', label: 'ID' },
  { key: 'name', label: '이름' },
  { key: 'deployStatus', label: '상태' },
  { key: 'currentUrl', label: 'URL' },
  { key: 'updatedAtRelativeText', label: '수정' },
];

const deploymentColumns = [
  { key: 'historyId', label: 'ID' },
  { key: 'status', label: '상태' },
  { key: 'deployTargetType', label: '대상' },
  { key: 'versionLabel', label: '버전' },
  { key: 'deployedUrl', label: 'URL' },
  { key: 'triggeredAt', label: '시작' },
];

function requireId(args, what) {
  const raw = args[0];
  if (raw === undefined) throw new UsageError(`${what} ID 가 필요합니다.`);
  const id = Number(raw);
  // A non-numeric id would reach the server as a path segment and come back 400 or 404; catching it
  // here says which argument was wrong instead of which endpoint failed.
  if (!Number.isInteger(id) || id <= 0) throw new UsageError(`${what} ID 는 양의 정수여야 합니다: ${raw}`);
  return id;
}

export const commands = {
  login: {
    usage: 'qeploy login [--scope READ|WRITE] [--expires-in-days N]',
    summary: '브라우저 토큰으로 개인 액세스 토큰을 발급받아 저장한다',
    // Runs before there is anything to authenticate with, so it builds its own client.
    auth: false,
    async run({ deps, flags, out, err }) {
      const apiUrl = resolveApiUrl(flags, deps.env);
      const scope = String(flags.scope ?? 'READ').toUpperCase();
      if (scope !== 'READ' && scope !== 'WRITE') {
        throw new UsageError('--scope 는 READ 또는 WRITE 여야 합니다.');
      }

      err(`Qeploy: ${apiUrl}`);
      err('');
      err('1. 브라우저에서 Qeploy 에 로그인합니다.');
      err('2. 개발자도구 → Application → Local Storage → accessToken 값을 복사합니다.');
      err('3. 아래에 붙여넣습니다. 입력은 화면에 표시되지 않습니다.');
      err('');

      // The browser JWT, not the PAT. It lives about an hour, which is plenty to trade it for a
      // long-lived token and means a leaked paste stops mattering quickly.
      // Trimmed here rather than trusting the prompt to have done it: copying a token often picks
      // up a trailing newline, and a whitespace-only paste must fail as "empty", not as a 401.
      const jwt = String(await deps.readSecret('accessToken: ') ?? '').trim();
      if (!jwt) throw new UsageError('토큰이 비어 있습니다.');
      if (jwt.startsWith('qp_')) {
        // Pasting a PAT here would "work" only until it expired, and the user would have no idea
        // why, because nothing was issued and nothing is refreshable.
        throw new UsageError(
          '이건 이미 발급된 개인 액세스 토큰(qp_...)입니다. 브라우저의 accessToken 을 붙여넣어 주세요.'
        );
      }

      const client = deps.createClient({ ...flags, apiUrl }, jwt);
      const issued = await client.issueApiToken({
        scope,
        // Naming it after the machine is what makes the token list usable later — "which of these
        // five do I revoke" has no answer if they are all called "CLI".
        label: `qeploy CLI (${hostname()})`,
        expiresInDays: flags.expiresInDays ? Number(flags.expiresInDays) : undefined,
      });

      const token = issued?.token;
      if (!token) throw new Error('서버가 토큰을 반환하지 않았습니다.');

      const path = writeConfig(
        { ...readConfig(deps.env), token, apiUrl, apiTokenId: issued?.info?.apiTokenId ?? null },
        deps.env
      );

      // Deliberately not printed. It is already saved, and echoing it would put a 90-day credential
      // into the scrollback for the sake of information the user does not need.
      return {
        data: { scope, expiresAt: issued?.info?.expiresAt ?? null, configPath: path },
        text:
          `로그인했습니다. ${scope} 스코프 토큰을 ${path} 에 저장했습니다(0600).\n` +
          `만료: ${issued?.info?.expiresAt ?? '알 수 없음'}\n` +
          '확인: qeploy projects',
      };
    },
  },

  logout: {
    usage: 'qeploy logout',
    summary: '저장된 토큰을 지운다 (서버에서 폐기하지는 않는다)',
    auth: false,
    async run({ deps }) {
      const { apiTokenId } = readConfig(deps.env);
      const path = clearConfig(deps.env);
      if (!path) {
        return { data: null, text: `저장된 토큰이 없습니다 (${configPath(deps.env)}).` };
      }
      // Revoking would need a write-scoped call, and a READ token — the default — cannot make one.
      // Saying so is better than silently leaving a live credential the user thinks is gone.
      const hint = apiTokenId
        ? `서버에서도 무효화하려면 웹 UI 에서 토큰 #${apiTokenId} 을 폐기하세요.`
        : '서버에서도 무효화하려면 웹 UI 에서 해당 토큰을 폐기하세요.';
      return { data: null, text: `${path} 를 지웠습니다.\n${hint}` };
    },
  },
  projects: {
    usage: 'qeploy projects',
    summary: '내 프로젝트 목록',
    async run({ client }) {
      const data = await client.listProjects();
      return { data, text: renderTable(data, projectColumns) };
    },
  },

  project: {
    usage: 'qeploy project <projectId>',
    summary: '프로젝트 개요',
    async run({ client, args }) {
      const data = await client.getProjectOverview(requireId(args, '프로젝트'));
      return {
        data,
        // Only what the overview endpoint actually returns — it carries no id or name, so listing
        // those would print nothing and read like the project had none.
        text: renderDetail(data, [
          { key: 'deployStatus', label: '배포 상태' },
          { key: 'currentUrl', label: 'URL' },
          { key: 'currentVersion', label: '배포된 버전' },
          { key: 'repositoryVersion', label: '저장소 버전' },
        ]),
      };
    },
  },

  deployments: {
    usage: 'qeploy deployments <projectId>',
    summary: '배포 이력',
    async run({ client, args }) {
      const data = await client.listDeployments(requireId(args, '프로젝트'));
      return { data, text: renderTable(data, deploymentColumns) };
    },
  },

  status: {
    usage: 'qeploy status <deploymentId>',
    summary: '배포 상태',
    async run({ client, args }) {
      const data = await client.getDeployment(requireId(args, '배포'));
      return {
        data,
        text: renderDetail(data, [
          { key: 'historyId', label: '배포 ID' },
          { key: 'status', label: '상태' },
          { key: 'buildStatus', label: '빌드' },
          { key: 'buildConclusion', label: '빌드 결과' },
          { key: 'deployedUrl', label: 'URL' },
          { key: 'errorCode', label: '실패 분류' },
          { key: 'errorMessage', label: '실패 사유' },
          { key: 'triggeredAt', label: '시작' },
          { key: 'updatedAt', label: '갱신' },
        ]),
      };
    },
  },

  logs: {
    usage: 'qeploy logs <deploymentId>',
    summary: '배포 로그',
    async run({ client, args }) {
      const data = await client.getDeploymentLogs(requireId(args, '배포'));
      // The log body is the whole point of this command, so it is printed raw rather than boxed
      // into a field — piping it to grep is the expected use.
      const text = data?.logText
        ? data.logText
        : renderTable(data?.jobs ?? [], [
            { key: 'name', label: '작업' },
            { key: 'status', label: '상태' },
            { key: 'conclusion', label: '결과' },
          ]);
      return { data, text };
    },
  },

  env: {
    usage: 'qeploy env <projectId>',
    summary: '환경변수 목록 (secret 은 값이 비어 있다)',
    async run({ client, args }) {
      const data = await client.listEnvironmentVariables(requireId(args, '프로젝트'));
      const rows = (Array.isArray(data) ? data : []).map((v) => ({
        ...v,
        // The server sends null for a secret's value; showing "(secret)" says that is deliberate
        // rather than leaving a dash that reads like a missing value.
        value: v?.value === null ? '(secret)' : v?.value,
      }));
      return {
        data,
        text: renderTable(rows, [
          { key: 'environmentVariableId', label: 'ID' },
          { key: 'scope', label: '스코프' },
          { key: 'key', label: '키' },
          { key: 'value', label: '값' },
        ]),
      };
    },
  },

  domains: {
    usage: 'qeploy domains <projectId>',
    summary: '연결된 도메인',
    async run({ client, args }) {
      const data = await client.listDomains(requireId(args, '프로젝트'));
      return {
        data,
        text: renderTable(data, [
          { key: 'domainId', label: 'ID' },
          { key: 'hostname', label: '호스트' },
          { key: 'status', label: '상태' },
          { key: 'certificateStatus', label: '인증서' },
          { key: 'dnsTarget', label: 'DNS 대상' },
        ]),
      };
    },
  },

  servers: {
    usage: 'qeploy servers <projectId>',
    summary: '서버 목록',
    async run({ client, args }) {
      const data = await client.listServers(requireId(args, '프로젝트'));
      return {
        data,
        text: renderTable(data, [
          { key: 'serverId', label: 'ID' },
          { key: 'status', label: '상태' },
          { key: 'instanceType', label: '타입' },
          { key: 'host', label: '호스트' },
          { key: 'healthy', label: '정상' },
        ]),
      };
    },
  },

  deploy: {
    usage: 'qeploy deploy <projectId> [--target-version <name>] [--yes]',
    summary: '배포 실행 (WRITE 스코프 토큰 필요)',
    write: true,
    async run({ client, args, flags, confirm }) {
      const projectId = requireId(args, '프로젝트');

      // Naming the project in the prompt is the point of the prompt. "프로젝트 12 를 배포할까요?"
      // gives nothing to check against; a name lets you notice you are on the wrong one.
      //
      // The name comes from the list, not the overview: the overview endpoint returns deploy state
      // only and carries no name at all. If the lookup fails we still ask — losing the confirmation
      // would be worse than losing the name.
      let label = `프로젝트 #${projectId}`;
      try {
        const projects = await client.listProjects();
        const found = (Array.isArray(projects) ? projects : []).find((p) => p?.projectId === projectId);
        if (found?.name) label = `'${found.name}' (#${projectId})`;
      } catch {
        // ignore: the confirmation matters more than the decoration
      }

      const target = flags.targetVersion ? `버전 ${flags.targetVersion} 으로` : '최신 커밋으로';
      const ok = await confirm(`${label} 를 ${target} 배포합니다. 진행할까요?`, flags);
      if (!ok) return { data: null, text: '취소했습니다.', cancelled: true };

      const data = await client.deploy(projectId, {
        deployTargetType: flags.targetVersion ? 'VERSION' : 'LATEST',
        versionName: flags.targetVersion,
      });
      return { data, text: describeDeploy(data) };
    },
  },

  retry: {
    usage: 'qeploy retry <deploymentId> [--yes]',
    summary: '실패한 배포 재시도 (WRITE 스코프 토큰 필요)',
    write: true,
    async run({ client, args, flags, confirm }) {
      const deploymentId = requireId(args, '배포');
      const ok = await confirm(`배포 #${deploymentId} 를 재시도합니다. 진행할까요?`, flags);
      if (!ok) return { data: null, text: '취소했습니다.', cancelled: true };

      const data = await client.retryDeployment(deploymentId);
      return { data, text: describeDeploy(data) };
    },
  },

  'env:set': {
    usage: 'qeploy env:set <projectId> KEY=VALUE --scope <PREVIEW|PRODUCTION> [--secret] [--yes]',
    summary: '환경변수 생성 또는 수정 (WRITE 스코프 토큰 필요)',
    write: true,
    async run({ client, args, flags, confirm }) {
      const projectId = requireId(args, '프로젝트');

      const assignment = args[1];
      if (!assignment || !assignment.includes('=')) {
        throw new UsageError('KEY=VALUE 형식이 필요합니다. 예: DATABASE_URL=postgres://...');
      }
      // Split once: a value legitimately contains '=' (connection strings, base64 padding).
      const eq = assignment.indexOf('=');
      const key = assignment.slice(0, eq);
      const value = assignment.slice(eq + 1);
      if (!key) throw new UsageError('키가 비어 있습니다.');

      const scope = String(flags.scope ?? '').toUpperCase();
      if (scope !== 'PREVIEW' && scope !== 'PRODUCTION') {
        // There is no COMMON scope by design, so guessing a default would silently write to one
        // environment while the user meant the other.
        throw new UsageError('--scope 는 PREVIEW 또는 PRODUCTION 이어야 합니다.');
      }

      // The API has no upsert: creating a duplicate (project, scope, key) is a 409. Doing the
      // lookup here is what makes "set" mean set — otherwise every caller writes this dance.
      const existing = (await client.listEnvironmentVariables(projectId)) ?? [];
      const match = (Array.isArray(existing) ? existing : []).find(
        (v) => v?.key === key && v?.scope === scope
      );

      if (match && flags.secret && match.secret === false) {
        // secret true→false is rejected by the server, so this flip is one-way.
        const ok = await confirm(
          `${key} 를 secret 으로 바꾸면 되돌릴 수 없습니다. 진행할까요?`,
          flags
        );
        if (!ok) return { data: null, text: '취소했습니다.', cancelled: true };
      }

      if (match) {
        const data = await client.updateEnvironmentVariable(projectId, match.environmentVariableId, {
          value,
          ...(flags.secret ? { secret: true } : {}),
        });
        return { data, text: `${scope} ${key} 수정했습니다.` };
      }

      const data = await client.createEnvironmentVariable(projectId, {
        key,
        value,
        scope,
        secret: Boolean(flags.secret),
      });
      return { data, text: `${scope} ${key} 생성했습니다.` };
    },
  },
};

/**
 * Reports what the server actually said.
 *
 * An approval gate means a successful call can mean "queued for a human", so printing "배포했습니다"
 * on a 200 would tell the user something untrue — and in CI, a green step for work that has not
 * started is worse than a red one.
 */
function describeDeploy(data) {
  const id = data?.deploymentId ?? data?.historyId;
  const where = id ? `배포 #${id}` : '배포';

  if (Array.isArray(data?.approvalIds) && data.approvalIds.length > 0) {
    return `${where} — 승인 대기 (승인 ${data.approvalIds.length}건). 웹에서 승인해야 진행됩니다.`;
  }
  const status = data?.status ?? '알 수 없음';
  const url = data?.pagesUrl ? `\nURL: ${data.pagesUrl}` : '';
  return `${where} — 상태 ${status}. 진행 상황은 qeploy status ${id ?? '<id>'} 로 확인하세요.${url}`;
}
