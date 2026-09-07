/**
 * The tool set, kept separate from transport wiring so it can be tested without stdio.
 *
 * Split into read and write. Reads are always available; writes are opt-in via
 * `QEPLOY_ENABLE_WRITES=true`, because the failure mode of an over-eager agent is not a wrong
 * answer but a real deployment. Both layers below the flag still apply: a READ-scope token is
 * refused server-side on any write, and the approval gates that guard deployment and
 * infrastructure changes are untouched by this path.
 *
 * Irreversible operations are never exposed at all, flag or not — project/server deletion,
 * repository disconnect, approval decisions, budget changes. Those stay in the web UI where a
 * person does them, because an agent that guesses wrong there cannot undo it.
 */

/** @typedef {{name: string, title: string, description: string, inputSchema: object, run: Function}} Tool */

const projectId = {
  type: 'integer',
  description: 'Qeploy 프로젝트 ID. qeploy_list_projects 로 확인할 수 있다.',
};

/** @param {import('@qeploy/client').QeployClient} client */
function readTools(client) {
  return [
    {
      name: 'qeploy_list_projects',
      title: '프로젝트 목록',
      description:
        '이 토큰 소유자의 Qeploy 프로젝트를 모두 반환한다. 다른 도구에 필요한 projectId 를 여기서 얻는다.',
      inputSchema: { type: 'object', properties: {} },
      run: () => client.listProjects(),
    },
    {
      name: 'qeploy_get_project',
      title: '프로젝트 개요',
      description:
        '프로젝트 하나의 현재 상태를 반환한다 — 연결된 저장소, 최근 배포, 도메인, 서버를 한 번에 본다. 상태를 물어보면 먼저 이것을 쓴다.',
      inputSchema: { type: 'object', properties: { projectId }, required: ['projectId'] },
      run: (a) => client.getProjectOverview(a.projectId),
    },
    {
      name: 'qeploy_list_deployments',
      title: '배포 이력',
      description: '프로젝트의 배포 이력을 최신순으로 반환한다.',
      inputSchema: { type: 'object', properties: { projectId }, required: ['projectId'] },
      run: (a) => client.listDeployments(a.projectId),
    },
    {
      name: 'qeploy_deployment_status',
      title: '배포 상태',
      description: '배포 하나의 현재 상태와 결과를 반환한다.',
      inputSchema: {
        type: 'object',
        properties: { deploymentId: { type: 'integer', description: '배포 ID' } },
        required: ['deploymentId'],
      },
      run: (a) => client.getDeployment(a.deploymentId),
    },
    {
      name: 'qeploy_deployment_logs',
      title: '배포 로그',
      description:
        '배포 로그를 반환한다. 배포가 실패한 이유를 조사할 때 qeploy_deployment_failure_analysis 와 함께 쓴다.',
      inputSchema: {
        type: 'object',
        properties: { deploymentId: { type: 'integer', description: '배포 ID' } },
        required: ['deploymentId'],
      },
      run: (a) => client.getDeploymentLogs(a.deploymentId),
    },
    {
      name: 'qeploy_deployment_failure_analysis',
      title: '배포 실패 분석',
      description:
        '실패한 배포에 대해 서버가 이미 만들어 둔 원인 분석을 반환한다. 없으면 비어 있다 — 로그를 직접 읽어 판단한다.',
      inputSchema: {
        type: 'object',
        properties: { deploymentId: { type: 'integer', description: '배포 ID' } },
        required: ['deploymentId'],
      },
      run: (a) => client.getDeploymentFailureAnalysis(a.deploymentId),
    },
    {
      name: 'qeploy_preview_session',
      title: '프리뷰 세션 조회',
      description:
        '프로젝트의 현재 프리뷰 세션을 반환한다(없으면 비어 있다). 조회만 하며 새로 만들지 않는다.',
      inputSchema: { type: 'object', properties: { projectId }, required: ['projectId'] },
      run: (a) => client.getPreviewSession(a.projectId),
    },
    {
      name: 'qeploy_list_env',
      title: '환경변수 목록',
      description:
        '환경변수의 키와 스코프를 반환한다. secret 로 표시된 변수의 값은 서버가 내려주지 않으므로 이 도구로 읽을 수 없다.',
      inputSchema: { type: 'object', properties: { projectId }, required: ['projectId'] },
      run: (a) => client.listEnvironmentVariables(a.projectId),
    },
    {
      name: 'qeploy_list_domains',
      title: '도메인 목록',
      description: '프로젝트에 연결된 도메인과 각각의 검증·HTTPS 상태를 반환한다.',
      inputSchema: { type: 'object', properties: { projectId }, required: ['projectId'] },
      run: (a) => client.listDomains(a.projectId),
    },
    {
      name: 'qeploy_list_servers',
      title: '서버 목록',
      description: '프로젝트에 프로비저닝된 서버와 상태를 반환한다.',
      inputSchema: { type: 'object', properties: { projectId }, required: ['projectId'] },
      run: (a) => client.listServers(a.projectId),
    },
    {
      name: 'qeploy_server_logs',
      title: '서버 로그',
      description: '배포된 서버의 로그를 반환한다. 앱이 떠 있는데 동작이 이상할 때 본다.',
      inputSchema: {
        type: 'object',
        properties: { serverId: { type: 'integer', description: '서버 ID' } },
        required: ['serverId'],
      },
      run: (a) => client.getServerLogs(a.serverId),
    },
  ];
}

/**
 * Write tools. Each carries `destructiveHint` so a client can prompt before running it — that is
 * the protocol's own mechanism for "ask the human first", and it works without every agent having
 * to parse our prose.
 *
 * @param {import('@qeploy/client').QeployClient} client
 */
function writeTools(client) {
  return [
    {
      name: 'qeploy_deploy',
      title: '배포 시작',
      description:
        '프로젝트를 배포한다. 실제 사용자 환경에 반영되므로 실행 전 사용자에게 확인을 받아라. ' +
        '승인 게이트가 걸린 프로젝트는 바로 배포되지 않고 승인 대기 상태로 응답한다 — ' +
        '응답의 상태를 반드시 읽고 "배포됨"으로 단정하지 마라. ' +
        'frontendHostingType 을 지정하면 프로젝트 설정 자체가 바뀌어 이후 배포도 그 값을 따른다.',
      inputSchema: {
        type: 'object',
        properties: {
          projectId,
          deployTargetType: {
            type: 'string',
            enum: ['LATEST', 'VERSION'],
            description: 'LATEST = 기본 브랜치 HEAD, VERSION = 특정 git tag. 생략 시 LATEST',
          },
          versionName: { type: 'string', description: 'deployTargetType=VERSION 일 때 git tag. 예: v1.0.0' },
          frontendHostingType: {
            type: 'string',
            enum: ['GITHUB_PAGES', 'S3', 'EC2'],
            description: '생략 권장. 지정하면 프로젝트 설정을 영구히 바꾼다.',
          },
        },
        required: ['projectId'],
      },
      annotations: { destructiveHint: true, idempotentHint: false, readOnlyHint: false },
      run: (a) =>
        client.deploy(a.projectId, {
          deployTargetType: a.deployTargetType,
          versionName: a.versionName,
          frontendHostingType: a.frontendHostingType,
        }),
    },
    {
      name: 'qeploy_retry_deployment',
      title: '배포 재시도',
      description:
        '실패한 배포를 다시 시도한다. 새 배포를 시작하는 것과 같으므로 실행 전 확인을 받아라. ' +
        '먼저 qeploy_deployment_logs 와 qeploy_deployment_failure_analysis 로 원인을 확인하라 — ' +
        '원인이 코드나 설정이면 고치지 않은 재시도는 같은 실패를 반복한다.',
      inputSchema: {
        type: 'object',
        properties: { deploymentId: { type: 'integer', description: '재시도할 배포 ID' } },
        required: ['deploymentId'],
      },
      annotations: { destructiveHint: true, idempotentHint: false, readOnlyHint: false },
      run: (a) => client.retryDeployment(a.deploymentId),
    },
    {
      name: 'qeploy_create_env',
      title: '환경변수 생성',
      description:
        '환경변수를 새로 만든다. 같은 (프로젝트, scope, key) 가 이미 있으면 409 다 — ' +
        '기존 값을 바꾸려면 qeploy_update_env 를 쓴다. ' +
        'secret=true 로 만들면 값은 이후 어떤 응답에도 나오지 않고 false 로 되돌릴 수 없다. ' +
        '비밀값이면 반드시 secret=true 로 만들어라.',
      inputSchema: {
        type: 'object',
        properties: {
          projectId,
          key: { type: 'string', description: 'POSIX 환경변수 이름. 대소문자 구분, 최대 128자' },
          value: { type: 'string', description: '값. 빈 문자열도 유효하다' },
          scope: { type: 'string', enum: ['PREVIEW', 'PRODUCTION'], description: '적용 환경' },
          secret: { type: 'boolean', description: '비밀 여부. true 로 만들면 되돌릴 수 없다' },
        },
        required: ['projectId', 'key', 'value', 'scope'],
      },
      annotations: { destructiveHint: false, idempotentHint: false, readOnlyHint: false },
      run: (a) =>
        client.createEnvironmentVariable(a.projectId, {
          key: a.key,
          value: a.value,
          scope: a.scope,
          secret: a.secret ?? false,
        }),
    },
    {
      name: 'qeploy_update_env',
      title: '환경변수 수정',
      description:
        '기존 환경변수의 값을 바꾼다. 배포된 앱의 동작이 바뀔 수 있으므로 확인을 받아라. ' +
        'key 와 scope 는 바꿀 수 없다(바꾸려면 삭제 후 재생성인데, 삭제는 이 도구로 제공하지 않는다). ' +
        'secret 은 false→true 만 가능하다.',
      inputSchema: {
        type: 'object',
        properties: {
          projectId,
          variableId: { type: 'integer', description: 'qeploy_list_env 로 확인한 변수 ID' },
          value: { type: 'string', description: '새 값' },
          secret: { type: 'boolean', description: 'true 로만 올릴 수 있다' },
        },
        required: ['projectId', 'variableId'],
      },
      annotations: { destructiveHint: true, idempotentHint: true, readOnlyHint: false },
      run: (a) =>
        client.updateEnvironmentVariable(a.projectId, a.variableId, {
          value: a.value,
          secret: a.secret,
        }),
    },
    {
      name: 'qeploy_bind_domain',
      title: '도메인 연결',
      description:
        '프로젝트에 도메인을 연결한다. 비동기라 응답은 완료된 도메인이 아니라 taskId 다 — ' +
        '연결 여부는 qeploy_list_domains 로 다시 확인하라. ' +
        'custom_domain 은 사용자가 DNS 레코드를 직접 추가해야 검증이 통과하므로, ' +
        '연결 후 qeploy_domain_verification_guide 로 안내 내용을 사용자에게 전달하라.',
      inputSchema: {
        type: 'object',
        properties: {
          projectId,
          type: {
            type: 'string',
            enum: ['managed_subdomain', 'custom_domain'],
            description: 'managed_subdomain = {label}.qeploy.com, custom_domain = 보유 도메인',
          },
          label: { type: 'string', description: 'managed_subdomain 일 때 서브도메인 라벨' },
          hostname: { type: 'string', description: 'custom_domain 일 때 전체 hostname' },
          verificationMethod: { type: 'string', enum: ['CNAME', 'A'], description: '생략 시 CNAME' },
          hostingTarget: {
            type: 'string',
            enum: ['GITHUB_PAGES', 'AWS', 'AWS_EC2_FRONTEND', 'AWS_S3_FRONTEND', 'GCP'],
            description: '생략 시 GITHUB_PAGES',
          },
        },
        required: ['projectId', 'type'],
      },
      annotations: { destructiveHint: true, idempotentHint: false, readOnlyHint: false },
      run: (a) =>
        client.bindDomain(a.projectId, {
          type: a.type,
          label: a.label,
          hostname: a.hostname,
          verificationMethod: a.verificationMethod,
          hostingTarget: a.hostingTarget,
        }),
    },
    {
      name: 'qeploy_domain_verification_guide',
      title: '도메인 검증 안내',
      description:
        '사용자가 DNS 에 추가해야 할 레코드를 반환한다. 조회만 하지만 도메인 연결 흐름의 일부라 여기에 둔다.',
      inputSchema: {
        type: 'object',
        properties: { domainId: { type: 'integer', description: '도메인 ID' } },
        required: ['domainId'],
      },
      annotations: { readOnlyHint: true },
      run: (a) => client.getDomainVerificationGuide(a.domainId),
    },
  ];
}

/**
 * @param {import('@qeploy/client').QeployClient} client
 * @param {{enableWrites?: boolean}} options writes are off unless explicitly enabled
 */
export function buildTools(client, { enableWrites = false } = {}) {
  const reads = readTools(client).map((t) => ({
    ...t,
    annotations: { ...(t.annotations ?? {}), readOnlyHint: true },
  }));
  return enableWrites ? [...reads, ...writeTools(client)] : reads;
}
