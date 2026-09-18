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
      return 'Qeploy 인증에 실패했습니다. QEPLOY_TOKEN 이 만료·폐기되었거나 잘못되었습니다. 새 토큰을 발급해 주세요.';
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

export class QeployClient {
  /**
   * @param {{baseUrl: string, token: string, fetchImpl?: typeof fetch, timeoutMs?: number}} options
   */
  constructor({ baseUrl, token, fetchImpl = fetch, timeoutMs = 30000 }) {
    if (!baseUrl) throw new Error('QEPLOY_API_URL 이 필요합니다.');
    if (!token) throw new Error('QEPLOY_TOKEN 이 필요합니다.');
    // Trailing slashes would produce "//api/v1/..." which some gateways treat as a different path.
    this.baseUrl = baseUrl.replace(/\/+$/, '');
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

  revokeApiToken(apiTokenId) {
    return this.request('DELETE', `/api/v1/api-tokens/${apiTokenId}`);
  }

  // ── Projects ──────────────────────────────────────────────────────────────
  listProjects() {
    return this.get('/api/v1/projects');
  }

  getProjectOverview(projectId) {
    return this.get(`/api/v1/projects/${projectId}/overview`);
  }

  // ── Deployments ───────────────────────────────────────────────────────────
  listDeployments(projectId) {
    return this.get(`/api/v1/projects/${projectId}/deployments`);
  }

  getDeployment(deploymentId) {
    return this.get(`/api/v1/deployments/${deploymentId}`);
  }

  getDeploymentLogs(deploymentId) {
    return this.get(`/api/v1/deployments/${deploymentId}/logs`);
  }

  getDeploymentFailureAnalysis(deploymentId) {
    return this.get(`/api/v1/deployments/${deploymentId}/failure-analysis`);
  }

  /**
   * Starts a deployment. Server-side approval gates still apply, so a successful call can mean
   * "queued, pending approval" rather than "deploying" — callers must read the returned status
   * rather than assume.
   */
  deploy(projectId, { deployTargetType = 'LATEST', versionName, frontendHostingType } = {}) {
    return this.post(`/api/v1/projects/${projectId}/deployments`, {
      deployTargetType,
      ...(versionName ? { versionName } : {}),
      ...(frontendHostingType ? { frontendHostingType } : {}),
    });
  }

  retryDeployment(deploymentId) {
    return this.post(`/api/v1/deployments/${deploymentId}/retry`);
  }

  // ── Preview ───────────────────────────────────────────────────────────────
  getPreviewSession(projectId) {
    return this.get(`/api/v1/projects/${projectId}/preview-session`);
  }

  // ── Environment ───────────────────────────────────────────────────────────
  listEnvironmentVariables(projectId) {
    // The server returns value=null for secrets; the client never sees a secret value.
    return this.get(`/api/v1/projects/${projectId}/environment-variables`);
  }

  createEnvironmentVariable(projectId, { key, value, scope, secret = false }) {
    return this.post(`/api/v1/projects/${projectId}/environment-variables`, {
      key,
      value,
      scope,
      secret,
    });
  }

  updateEnvironmentVariable(projectId, variableId, { value, secret }) {
    return this.patch(`/api/v1/projects/${projectId}/environment-variables/${variableId}`, {
      ...(value === undefined ? {} : { value }),
      ...(secret === undefined ? {} : { secret }),
    });
  }

  // ── Domains ───────────────────────────────────────────────────────────────
  listDomains(projectId) {
    return this.get(`/api/v1/projects/${projectId}/domains`);
  }

  /**
   * Binding is asynchronous: the response carries a taskId, not a finished domain. Custom domains
   * additionally need the user to add a DNS record before verification can pass.
   */
  bindDomain(projectId, { type, label, hostname, verificationMethod, hostingTarget }) {
    return this.post(`/api/v1/projects/${projectId}/domains`, {
      type,
      ...(label ? { label } : {}),
      ...(hostname ? { hostname } : {}),
      ...(verificationMethod ? { verificationMethod } : {}),
      ...(hostingTarget ? { hostingTarget } : {}),
    });
  }

  getDomainVerificationGuide(domainId) {
    return this.get(`/api/v1/domains/${domainId}/verification-guide`);
  }

  // ── Servers ───────────────────────────────────────────────────────────────
  listServers(projectId) {
    return this.get(`/api/v1/projects/${projectId}/servers`);
  }

  getServerLogs(serverId) {
    return this.get(`/api/v1/servers/${serverId}/logs`);
  }
}
