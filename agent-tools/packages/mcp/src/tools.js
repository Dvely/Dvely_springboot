/**
 * The tool set, kept separate from transport wiring so it can be tested without stdio.
 *
 * Read-only on purpose for this first cut. An agent that can read deployment state, logs and
 * failure analysis is already useful — it can explain why a deploy failed — and it cannot break
 * anything while we find out whether the shape is right. Write tools come after that, and the
 * irreversible operations (project/server deletion, approvals, budget changes) are never exposed
 * regardless: those stay in the web UI where a person does them.
 */

/** @typedef {{name: string, title: string, description: string, inputSchema: object, run: Function}} Tool */

const projectId = {
  type: 'integer',
  description: 'Qeploy 프로젝트 ID. qeploy_list_projects 로 확인할 수 있다.',
};

/** @param {import('@qeploy/client').QeployClient} client */
export function buildTools(client) {
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
