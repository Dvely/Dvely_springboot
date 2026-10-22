/**
 * Qeploy REST client.
 *
 * Shared by the MCP server and the CLI so there is one place that knows the envelope shape, the
 * auth header, and how an error becomes a message. Both surfaces are thin wrappers over this.
 */

/** Thrown for any non-2xx response, carrying enough to explain it without a stack trace. */
export class QeployApiError extends Error {
  constructor(status, code, message, path) {
    super(message);
    this.name = 'QeployApiError';
    this.status = status;
    this.code = code;
    this.path = path;
  }

  /** What an agent should read: the cause and, where possible, what to do about it. */
  toAgentMessage() {
    if (this.status === 401) {
      // Not "QEPLOY_TOKEN": most people never set it — their token came from `qeploy login`.
      return 'Qeploy 인증에 실패했습니다. 토큰이 만료·폐기되었거나 잘못되었습니다. 새 토큰을 발급해 주세요.';
    }
    if (this.status === 403 && this.code === 'FORBIDDEN') {
      return '이 토큰은 읽기 전용입니다(READ 스코프). 변경 작업에는 WRITE 스코프 토큰이 필요합니다.';
    }
    if (this.status === 404) {
      return `대상을 찾을 수 없습니다: ${this.path}`;
    }
    return `Qeploy API 오류 ${this.status}${this.code ? ` (${this.code})` : ''}: ${this.message}`;
  }
}

const LOOPBACK = new Set(['localhost', '127.0.0.1', '[::1]']);

/**
 * Checks the address a bearer token is about to be sent to.
 *
 * Plain http is refused outside loopback: the token is the whole credential, and nothing else in the
 * request would tell the user it went out in the clear (#482). Loopback stays open for local
 * development and the e2e scripts.
 *
 * A trailing /api/v1 is refused rather than stripped: every path below already starts with it, so
 * the request would go to /api/v1/api/v1/... — and against a fake token that is a 401 that looks
 * exactly like a bad token.
 */
export function checkBaseUrl(baseUrl) {
  let url;
  try {
    url = new URL(baseUrl);
  } catch {
    throw new Error(`API 주소가 올바른 URL 이 아닙니다: ${baseUrl}`);
  }
  const loopback = LOOPBACK.has(url.hostname) || url.hostname.endsWith('.localhost');
  if (url.protocol !== 'https:' && !(url.protocol === 'http:' && loopback)) {
    throw new Error(`https 가 아닌 주소로는 토큰을 보내지 않습니다: ${baseUrl}`);
  }
  if (/\/api\/v1\/?$/.test(url.pathname)) {
    throw new Error(`API 주소에는 /api/v1 을 붙이지 않습니다: ${baseUrl} → ${url.origin}`);
  }
  return baseUrl.replace(/\/+$/, '');
}

/**
 * An id that goes into a URL path.
 *
 * MCP tool arguments arrive as whatever the model sent — the low-level Server does not enforce the
 * inputSchema — so "../api-tokens?x=" would otherwise turn a read tool into a request for any
 * endpoint the token can reach (#482). Validated here rather than in each surface so the CLI, the
 * MCP server and any direct caller share one gate.
 */
function pathId(name, value) {
  const ok =
    (typeof value === 'number' && Number.isSafeInteger(value) && value > 0) ||
    (typeof value === 'string' && /^[1-9][0-9]{0,15}$/.test(value));
  if (!ok) throw new TypeError(`${name} 는 양의 정수여야 합니다: ${JSON.stringify(value)}`);
  return String(value);
}

export class QeployClient {
  /**
   * @param {{baseUrl: string, token: string, fetchImpl?: typeof fetch, timeoutMs?: number}} options
   */
  constructor({ baseUrl, token, fetchImpl = fetch, timeoutMs = 30000 }) {
    if (!baseUrl) throw new Error('QEPLOY_API_URL 이 필요합니다.');
    if (!token) throw new Error('QEPLOY_TOKEN 이 필요합니다.');
    // Trailing slashes would produce "//api/v1/..." which some gateways treat as a different path.
    this.baseUrl = checkBaseUrl(baseUrl);
    this.token = token;
    this.fetchImpl = fetchImpl;
    this.timeoutMs = timeoutMs;
  }

  /**
   * Every Qeploy response is wrapped as {status, code, message, data}; callers only ever want
   * `data`, so unwrapping happens here rather than in each tool.
   */
  async request(method, path, body) {
    const controller = new AbortController();
    // Without this an unreachable server would hang the agent's tool call indefinitely.
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    let response;
    try {
      response = await this.fetchImpl(this.baseUrl + path, {
        method,
        headers: {
          Authorization: `Bearer ${this.token}`,
          ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        },
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: controller.signal,
      });
    } catch (e) {
      if (e.name === 'AbortError') {
        throw new QeployApiError(0, 'TIMEOUT', `요청이 ${this.timeoutMs}ms 안에 끝나지 않았습니다.`, path);
      }
      throw new QeployApiError(0, 'NETWORK', `Qeploy 에 연결할 수 없습니다: ${e.message}`, path);
    } finally {
      clearTimeout(timer);
    }

    // 204 has no body to parse; treating it as JSON would throw on a successful delete.
    if (response.status === 204) return null;

    const text = await response.text();
    let payload = null;
    try {
      payload = text ? JSON.parse(text) : null;
    } catch {
      // A proxy or error page can return HTML; surface the status rather than a parse error.
      if (!response.ok) {
        throw new QeployApiError(response.status, null, text.slice(0, 200), path);
      }
      throw new QeployApiError(response.status, 'BAD_RESPONSE', 'JSON 이 아닌 응답을 받았습니다.', path);
    }

    if (!response.ok) {
      throw new QeployApiError(
        response.status,
        payload?.code ?? null,
        payload?.message ?? `HTTP ${response.status}`,
        path
      );
    }
    // Unwrap the envelope when present; some endpoints may return a bare body.
    return payload && typeof payload === 'object' && 'data' in payload ? payload.data : payload;
  }

  get(path) {
    return this.request('GET', path);
  }

  post(path, body) {
    return this.request('POST', path, body ?? {});
  }

  patch(path, body) {
    return this.request('PATCH', path, body);
  }

  // ── API tokens ────────────────────────────────────────────────────────────
  /**
   * Issues a personal access token. Called with a browser JWT, not a PAT — this is how a headless
   * client bootstraps itself.
   *
   * The plaintext comes back in this response and nowhere else; the server stores only a hash, so a
   * caller that discards it has to issue a new one.
   */
  issueApiToken({ scope = 'READ', label, expiresInDays } = {}) {
    return this.post('/api/v1/api-tokens', {
      scope,
      ...(label ? { label } : {}),
      ...(expiresInDays ? { expiresInDays } : {}),
    });
  }

  listApiTokens() {
    return this.get('/api/v1/api-tokens');
  }

  async revokeApiToken(apiTokenId) {
    return this.request('DELETE', `/api/v1/api-tokens/${pathId('apiTokenId', apiTokenId)}`);
  }

  // ── Projects ──────────────────────────────────────────────────────────────
  listProjects() {
    return this.get('/api/v1/projects');
  }

  async getProjectOverview(projectId) {
    return this.get(`/api/v1/projects/${pathId('projectId', projectId)}/overview`);
  }

  // ── Deployments ───────────────────────────────────────────────────────────
  async listDeployments(projectId) {
    return this.get(`/api/v1/projects/${pathId('projectId', projectId)}/deployments`);
  }

  async getDeployment(deploymentId) {
    return this.get(`/api/v1/deployments/${pathId('deploymentId', deploymentId)}`);
  }

  async getDeploymentLogs(deploymentId) {
    return this.get(`/api/v1/deployments/${pathId('deploymentId', deploymentId)}/logs`);
  }

  async getDeploymentFailureAnalysis(deploymentId) {
    return this.get(`/api/v1/deployments/${pathId('deploymentId', deploymentId)}/failure-analysis`);
  }

  /**
   * Starts a deployment. Server-side approval gates still apply, so a successful call can mean
   * "queued, pending approval" rather than "deploying" — callers must read the returned status
   * rather than assume.
   */
  async deploy(projectId, { deployTargetType = 'LATEST', versionName, frontendHostingType } = {}) {
    return this.post(`/api/v1/projects/${pathId('projectId', projectId)}/deployments`, {
      deployTargetType,
      ...(versionName ? { versionName } : {}),
      ...(frontendHostingType ? { frontendHostingType } : {}),
    });
  }

  async retryDeployment(deploymentId) {
    return this.post(`/api/v1/deployments/${pathId('deploymentId', deploymentId)}/retry`);
  }

  // ── Preview ───────────────────────────────────────────────────────────────
  async getPreviewSession(projectId) {
    return this.get(`/api/v1/projects/${pathId('projectId', projectId)}/preview-session`);
  }

  // ── Environment ───────────────────────────────────────────────────────────
  async listEnvironmentVariables(projectId) {
    // The server returns value=null for secrets; the client never sees a secret value.
    return this.get(`/api/v1/projects/${pathId('projectId', projectId)}/environment-variables`);
  }

  async createEnvironmentVariable(projectId, { key, value, scope, secret = false }) {
    return this.post(`/api/v1/projects/${pathId('projectId', projectId)}/environment-variables`, {
      key,
      value,
      scope,
      secret,
    });
  }

  async updateEnvironmentVariable(projectId, variableId, { value, secret }) {
    const path =
      `/api/v1/projects/${pathId('projectId', projectId)}` +
      `/environment-variables/${pathId('variableId', variableId)}`;
    return this.patch(path, {
      ...(value === undefined ? {} : { value }),
      ...(secret === undefined ? {} : { secret }),
    });
  }

  // ── Domains ───────────────────────────────────────────────────────────────
  async listDomains(projectId) {
    return this.get(`/api/v1/projects/${pathId('projectId', projectId)}/domains`);
  }

  /**
   * Binding is asynchronous: the response carries a taskId, not a finished domain. Custom domains
   * additionally need the user to add a DNS record before verification can pass.
   */
  async bindDomain(projectId, { type, label, hostname, verificationMethod, hostingTarget }) {
    return this.post(`/api/v1/projects/${pathId('projectId', projectId)}/domains`, {
      type,
      ...(label ? { label } : {}),
      ...(hostname ? { hostname } : {}),
      ...(verificationMethod ? { verificationMethod } : {}),
      ...(hostingTarget ? { hostingTarget } : {}),
    });
  }

  async getDomainVerificationGuide(domainId) {
    return this.get(`/api/v1/domains/${pathId('domainId', domainId)}/verification-guide`);
  }

  // ── Servers ───────────────────────────────────────────────────────────────
  async listServers(projectId) {
    return this.get(`/api/v1/projects/${pathId('projectId', projectId)}/servers`);
  }

  async getServerLogs(serverId) {
    return this.get(`/api/v1/servers/${pathId('serverId', serverId)}/logs`);
  }
}
