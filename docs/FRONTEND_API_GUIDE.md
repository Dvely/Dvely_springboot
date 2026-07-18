# Qeploy Frontend API Guide

이 문서는 프론트엔드 개발자가 Swagger UI와 함께 참고하는 통합 작업 문서입니다. 컨트롤러·DTO·설계서(`.agent-team/04-architecture/`)의 실제 계약만을 근거로 작성했으며, 추측/날조된 필드나 동작은 없습니다. 필드 하나하나의 상세 스키마(타입, `nullable`, `example`)는 Swagger UI(`/swagger-ui/index.html`)가 항상 최신 소스이므로, 이 문서는 **"무엇을 언제 왜 호출하는가"**에 집중하고 세부 스키마는 Swagger로 위임합니다.

- **컨트롤러 수**: 14개 · **공개 엔드포인트 수**: 90개 (Swagger 그룹 11개 + 프로젝트 그룹에 포함된 Approval/Change 하위 리소스)
- **작성 기준 커밋**: main `90059a7` (U0~U7 · I45 · Cost(#58) · CloudOps(#59) 머지 완료)

---

## 1. 개요 · Base URL · 환경

| 환경 | Base URL | 비고 |
|---|---|---|
| 로컬 백엔드 | `http://localhost:8080` | `application.yaml` 기본 포트. FE 정본(`Dvely_FE_test`)의 `VITE_API_BASE` 기본값과 동일 |
| Swagger UI | `http://localhost:8080/swagger-ui/index.html` | 도메인별 그룹 드롭다운(auth/project/chat/agent/deployment/domainbinding/cloudconnection/preview/environment/webhook/user)으로 필터링 가능 |
| OpenAPI JSON | `http://localhost:8080/v3/api-docs` (그룹별: `/v3/api-docs/{group}`) | 코드 생성기(openapi-generator 등)에 바로 사용 가능 |

모든 API 경로는 `/api/v1`으로 시작합니다(GitHub webhook·preview 프록시·auth 콜백류 제외 없음 — 전부 `/api/v1/**` 하위).

---

## 2. 인증 플로우

Qeploy는 **GitHub OAuth로 로그인**하고 **서비스 자체 JWT**를 발급받아 사용합니다. GitHub App 설치는 별도 단계이며, 리포지토리 접근·PR 생성 등 GitHub API 호출이 필요한 기능(프로젝트 생성, 배포 등)에 필수입니다.

### 2.1 인증 필요 여부

`SecurityConfig`가 명시적으로 허용하는 예외를 빼면 **모든 API는 `Authorization: Bearer {accessToken}` 헤더가 필요**합니다. 예외(permitAll) 목록:

| 경로 | 이유 |
|---|---|
| `GET /api/v1/auth/github/url` | 로그인 시작점(토큰이 없는 상태에서 호출) |
| `GET /api/v1/auth/github/callback` | GitHub 리다이렉트가 직접 호출 |
| `GET /api/v1/auth/github/app/callback` | GitHub이 직접 호출(브라우저 리다이렉트) |
| `POST /api/v1/auth/refresh` | 만료된 accessToken 대신 refreshToken으로 인증 |
| `POST /api/v1/webhook/github` | GitHub이 직접 호출(HMAC 서명으로 검증) |
| `GET /api/v1/previews/{sessionId}/{accessToken}/**` | URL에 내장된 1회성 토큰으로 인증(JWT 아님, iframe 접근용) |
| `/swagger-ui/**`, `/v3/api-docs/**`, `/error` | 문서/에러 핸들링 |

> 주의: `GET /api/v1/auth/github/app/install-url`, `GET /api/v1/auth/github/app/reauthorize-url`, `DELETE /api/v1/auth/logout`은 **인증이 필요**합니다(컨트롤러가 `@RequestHeader("Authorization")`으로 직접 헤더를 읽지만, Security 필터 자체가 이 경로들을 permitAll에 포함하지 않으므로 유효한 Bearer 토큰이 있어야 통과합니다).

### 2.2 로그인 플로우 (신규/재로그인)

```
1. FE: GET /api/v1/auth/github/url
   → { url: "https://github.com/login/oauth/authorize?..." }
2. FE: window.location = url  (사용자가 GitHub에서 로그인/승인)
3. GitHub: 브라우저를 GET /api/v1/auth/github/callback?code=...&state=... 로 리다이렉트
   → 백엔드가 code를 GitHub과 교환, User upsert, 서비스 JWT 발급
   → { accessToken, refreshToken, githubAppInstalled }
4. FE: accessToken/refreshToken을 저장(localStorage 등)
5. IF githubAppInstalled == false:
     GET /api/v1/auth/github/app/install-url (Authorization 필요)
     → { url: "https://github.com/apps/{slug}/installations/new?state={jwt}" }
     FE: window.location = url
     → 설치 완료 시 GitHub이 GET /api/v1/auth/github/app/callback 을 호출(FE가 직접 호출하지 않음)
     → 백엔드가 installation_id를 저장한 뒤 302로
        {frontend.redirect-url}/auth/app-callback?githubAppLinked=true 로 리다이렉트
6. FE: GET /api/v1/users/me 로 최종 프로필/GitHub App 연동 상태 확인
```

### 2.3 요청 시 인증 헤더

```
Authorization: Bearer {accessToken}
```

### 2.4 Access Token 만료 → Refresh (Token Rotation)

- Access Token 기본 만료: 1시간(`jwt.expiration-ms`, 기본 3600000ms)
- Refresh Token 기본 만료: 30일(`jwt.refresh-expiration-ms`, 기본 2592000000ms)
- API 호출이 401(`INVALID_TOKEN`)을 반환하면:
  ```
  POST /api/v1/auth/refresh
  { "refreshToken": "..." }
  → { accessToken, refreshToken, githubAppInstalled }   // 새 토큰 쌍(회전) — 이전 refreshToken은 즉시 폐기(재사용 시 REVOKED_REFRESH_TOKEN)
  ```
- 실패 시 에러 코드: `INVALID_TOKEN`(존재하지 않는 토큰), `EXPIRED_REFRESH_TOKEN`(만료), `REVOKED_REFRESH_TOKEN`(이미 회전되어 폐기된 토큰 재사용 — refresh 위변조/토큰 탈취 의심 신호이므로 이 경우 재로그인 유도 권장).

### 2.5 로그아웃

```
DELETE /api/v1/auth/logout   (Authorization 필요)
```
현재 accessToken을 블랙리스트에 등록(즉시 무효화)하고, 해당 유저의 **모든** refreshToken을 폐기합니다(다중 기기 전체 로그아웃). 호출 후 FE는 로컬에 저장된 토큰을 반드시 삭제해야 합니다.

### 2.6 GitHub App 재인증 (User Token 만료)

`GET /api/v1/users/me` 응답의 `githubAppTokenExpired`가 `true`이거나 `githubAppRefreshTokenExpiresAt`이 과거이면, GitHub App User Token이 만료된 것입니다(GitHub App 설치 자체와는 별개 — 설치는 유지된 채 유저 토큰만 만료될 수 있음).

```
GET /api/v1/auth/github/app/reauthorize-url   (Authorization 필요)
→ { url: "https://github.com/login/oauth/authorize?..." }
FE: window.location = url
→ GitHub이 GET /api/v1/auth/github/app/callback 호출(설치는 그대로 유지, User Token만 재발급)
```

새 저장소 생성 등 GitHub App User Token이 필요한 호출에서 `bad_refresh_token` 오류 메시지를 받으면 이 플로우로 유도하세요.

---

## 3. 공통 규약

### 3.1 응답 Envelope

SSE, GitHub webhook/App 콜백, preview 프록시(`@RawApiResponse` 마킹된 소수의 엔드포인트)를 제외한 **모든 응답**은 다음 형태로 감싸집니다:

```json
{ "status": 200, "code": "SUCCESS", "message": "요청이 성공적으로 처리되었습니다", "data": { } }
```

- `data`가 없는 응답(204 등)은 `data` 필드 자체가 생략됩니다(`null`이 아니라 키 부재 — `@JsonInclude(NON_NULL)`).
- 이 문서의 각 엔드포인트 표에 적힌 "응답"은 모두 이 envelope의 `data` 내용입니다.
- **`@RawApiResponse`가 붙은 엔드포인트**(감싸지지 않고 바디를 그대로 반환): `GET /api/v1/auth/github/app/callback`(302 redirect, 바디 없음), `POST /api/v1/webhook/github`(202, 바디 없음), `GET /api/v1/agent/tasks/{taskId}/events/stream`(SSE), `GET /api/v1/previews/{sessionId}/{accessToken}/**`(프리뷰 앱의 원본 바이트 그대로 프록시).

### 3.2 FE 정본(`Dvely_FE_test`)의 unwrap 규약

`src/api/http.ts`의 `request<T>()`는 아래 규칙으로 동작합니다 — 신규 FE 프로젝트도 동일 패턴을 권장합니다.

```ts
// 1. fetch 후 JSON 파싱(204 등 바디 없음은 null)
// 2. response.ok가 아니면 파싱된 바디의 message 필드로 Error를 throw
// 3. ok이면 바디가 객체이고 'data' 키를 가지면 그 값을 반환(envelope unwrap), 아니면 바디 그대로 반환
function unwrapResponse(body: unknown): unknown {
  if (body && typeof body === 'object' && 'data' in body) {
    return (body as { data: unknown }).data
  }
  return body
}
```

즉 FE 코드는 `envelope.data`가 아니라 **unwrap된 값**을 바로 받습니다. 이 문서의 응답 예시 JSON은 편의상 envelope 전체를 보여주지만, 실제 FE 호출 코드에서는 `data` 안쪽만 다루면 됩니다.

### 3.3 에러 코드 카탈로그

에러도 같은 envelope(단, `data` 없음)로 내려옵니다. `code`가 안정적인 판별자이고, `message`는 상황별 상세 문구(같은 `code`라도 메시지는 케이스마다 다를 수 있음)입니다.

| HTTP | code | 의미 | 대표 발생 상황 |
|---|---|---|---|
| 400 | `BAD_REQUEST` | 검증 실패/잘못된 파라미터/파싱 실패 | `@Valid` 실패, 잘못된 JSON, path/query 타입 불일치, `IllegalArgumentException` |
| 401 | `UNAUTHORIZED` | 인증 필요(토큰 없음/형식 오류) | `Authorization` 헤더 누락, `UnauthorizedException` |
| 401 | `INVALID_TOKEN` | accessToken 검증 실패 | 만료/위조된 JWT, refresh 시 존재하지 않는 refreshToken |
| 401 | `EXPIRED_REFRESH_TOKEN` | refreshToken 만료 | `POST /auth/refresh` |
| 401 | `REVOKED_REFRESH_TOKEN` | 이미 회전(rotation)되어 폐기된 refreshToken 재사용 | `POST /auth/refresh` — 재로그인 유도 |
| 403 | `FORBIDDEN` | 인증은 됐지만 권한 없음 | `ForbiddenException` |
| 403 | `GITHUB_APP_NOT_INSTALLED` | GitHub App 미설치 상태에서 App 필요 작업 시도 | (정의는 있으나 현재 코드에서 직접 throw하는 지점은 제한적 — `githubAppInstalled` 필드로 사전 분기 권장) |
| 404 | `NOT_FOUND` | 리소스 없음/소유권 불일치(존재 자체를 숨김) | 잘못된 ID, 다른 유저 소유 리소스 |
| 405 | `METHOD_NOT_ALLOWED` | 지원하지 않는 HTTP 메서드 | 라우트는 있으나 메서드 불일치 |
| 409 | `CONFLICT` | 상태 충돌(낙관적 락 경합, 이미 처리된 승인, 잘못된 상태 전이 등) | 동시 수정 경합(I45), 이미 결정된 Approval 재결정, FAILED가 아닌 배포 재시도 등 |
| 500 | `INTERNAL_SERVER_ERROR` | 예기치 못한 서버 오류 | 미처리 예외 |

**409(`CONFLICT`) 복구 패턴(공용)**: `Project`·`ProjectCloudConnectionSetting` 등 낙관적 락(`@Version`)이 걸린 리소스를 동시에 수정하면 409가 반환됩니다. FE는 최신 상태를 재조회(`GET`)한 뒤 사용자에게 재시도를 안내하세요(자동 재적용 없음 — 사용자가 마지막으로 본 화면 기준의 의도가 이미 낡았을 수 있으므로).

### 3.4 페이지네이션 부재

**이 API에는 페이지네이션이 없습니다.** 목록 엔드포인트(`GET /projects`, `GET /conversations`, `GET /deployments` 등)는 항상 전체 목록을 배열로 반환합니다. 다만 변경 이력류(`environment-variables/history`, `infrastructure/configuration/history`)는 `limit` 쿼리 파라미터(기본 50, 최대 200 — 초과/0/음수는 자동 보정)로 개수를 제한할 수 있습니다. offset/cursor 기반 페이지네이션은 없습니다.

### 3.5 202 + 승인(Approval) 시맨틱

Agent가 수행하는 부수효과 작업(코드 생성/수정, 배포, 도메인 연결/해제, 인프라 설정 변경, 인프라 운영)은 **비동기**입니다. 이런 엔드포인트는 최종 결과가 아니라 **추적 ID**를 즉시 반환합니다:

- HTTP 상태 202(Accepted) 또는 200이지만 페이로드에 `taskId`/`status`/`approvalIds`만 포함
- `status`가 `WAITING_APPROVAL`이면 프로젝트의 Chat 승인 정책(`GET /projects/{id}/settings/chat`)에 따라 사용자 승인이 먼저 필요합니다. **프로젝트 생성 시 4개 정책 모두 기본값 `true`**이므로, 별도로 끄지 않으면 대부분의 최초 작업은 승인 대기를 거칩니다.
- `approvalIds`의 각 ID를 `POST /api/v1/approvals/{approvalId}/approve` 또는 `/reject`로 처리해야 작업이 재개/취소됩니다.
- 승인이 필요 없으면(`approvalIds`가 빈 배열) `status`는 바로 `QUEUED`입니다.

### 3.6 taskId 폴링 / SSE

비동기 작업의 진행 상황은 두 가지 방식으로 확인할 수 있습니다.

**폴링(권장, 단순)**
```
GET /api/v1/agent/tasks/{taskId}
```
`status`가 `DONE`/`FAILED`/`CANCELLED`가 될 때까지 주기적으로 호출합니다. `WAITING_INPUT` 상태이면 `question` 필드를 사용자에게 보여주고 `POST /api/v1/agent/tasks/{taskId}/input`으로 응답을 제출해야 다음 단계로 진행됩니다.

**이벤트 조회(증분)**
```
GET /api/v1/agent/tasks/{taskId}/events?afterEventId={마지막으로 받은 ID}
```
`type`은 `CREATED | WAITING_APPROVAL | QUEUED | STARTED | RETRY_QUEUED | LEASE_RECOVERED | RECOVERY_EXHAUSTED | WAITING_INPUT | INPUT_RECEIVED | COMPLETED | FAILED | CANCELLED` 중 하나입니다. 매번 전체를 다시 받지 않고 `eventId`만 이어서 조회할 수 있습니다.

**SSE(실시간)**
```
GET /api/v1/agent/tasks/{taskId}/events/stream?afterEventId=0
Accept: text/event-stream
```
서버가 1초 간격으로 DB의 신규 이벤트를 push합니다(`event: {type}`, `id: {eventId}`, `data: {AgentTaskEventResponse JSON}`). 상태가 `DONE`/`CANCELLED`가 되면 서버가 스트림을 자동 종료합니다(스트림 자체의 상한은 5분). `@RawApiResponse`이므로 envelope로 감싸지지 않습니다. taskId가 없거나 소유자가 아니면 404입니다.

### 3.7 Secret 마스킹

- **Environment 변수**(`EnvironmentVariableResponse`): `secret=true`인 변수는 생성/조회/수정 **어떤 응답에서도 `value`가 항상 `null`**입니다. 평문은 절대 API 응답에 실리지 않습니다. `secret`은 `false→true`만 가능(역방향은 400).
- **CloudConnection 자격 증명**: `secretAccessKey`/`sessionToken`/`serviceAccountKeyJson`은 요청으로만 받고 응답에는 절대 포함되지 않습니다(`secretAccessKeyConfigured` 등 boolean으로 존재 여부만 노출). `accessKeyId`만 부분 마스킹되어 노출됩니다(`AKIA************3XYZ` 형식, 8자 이하면 `****`).

### 3.8 휴지통(Trash) 7일 보관

`Chat` 대화 삭제는 **soft delete**로 휴지통에 7일간 보관된 뒤 자동 영구 삭제됩니다(백엔드 스케줄러, `qeploy.chat.trash-cleanup-interval-ms`). 7일 이내에는 `POST /api/v1/trash/conversations/{id}/restore`로 복구할 수 있고, 즉시 영구 삭제하려면 `DELETE /api/v1/trash/conversations/{id}`를 호출합니다. 프로젝트를 `PROJECT_AND_REPOSITORY` 모드로 삭제하면 대화는 휴지통을 거치지 않고 **즉시** 영구 삭제됩니다(GitHub 저장소를 지우는 파괴적 작업과 함께 처리되므로).

---

## 4. 도메인별 엔드포인트 카탈로그 (전수 90개)

각 표의 "요청"·"응답" 열은 핵심 필드만 나열합니다. 전체 필드/타입/예시 값은 Swagger UI에서 확인하세요.

### 4.1 Auth — `com.example.dvely.auth.presentation` (7)

| 메서드 | 경로 | 용도 | 요청 | 응답(핵심 필드) | 주요 에러 |
|---|---|---|---|---|---|
| GET | `/api/v1/auth/github/url` | GitHub OAuth 로그인 URL 발급 | - | `{ url }` | - |
| GET | `/api/v1/auth/github/callback` | OAuth 콜백 → 서비스 JWT 발급 | query: `code`, `state` | `{ accessToken, refreshToken, githubAppInstalled }` | 400(state 검증 실패) |
| GET | `/api/v1/auth/github/app/install-url` | GitHub App 설치 URL 발급 | Bearer 필요 | `{ url }` | 401 |
| GET | `/api/v1/auth/github/app/reauthorize-url` | GitHub App User Token 재인증 URL(재설치 불필요) | Bearer 필요 | `{ url }` | 401 |
| GET | `/api/v1/auth/github/app/callback` | GitHub App 설치/재인증 완료 콜백(GitHub이 호출, FE가 직접 호출 안 함) | query: `installation_id`, `setup_action`, `state`, `code` | 302 redirect(바디 없음, `@RawApiResponse`) | - |
| POST | `/api/v1/auth/refresh` | Access Token 재발급(rotation) | `{ refreshToken }` | `{ accessToken, refreshToken, githubAppInstalled }` | 401(`INVALID_TOKEN`/`EXPIRED_REFRESH_TOKEN`/`REVOKED_REFRESH_TOKEN`) |
| DELETE | `/api/v1/auth/logout` | 로그아웃(현재 accessToken 블랙리스트 + 전체 refreshToken 폐기) | Bearer 필요 | 없음(`ApiResponse<Void>`) | 401 |

### 4.2 User — `com.example.dvely.user.presentation` (1)

| 메서드 | 경로 | 용도 | 응답(핵심 필드) |
|---|---|---|---|
| GET | `/api/v1/users/me` | 현재 로그인 유저 프로필 + GitHub App 연동 상태 | `{ id, username, avatarUrl, githubAppInstalled, githubAppTokenLinked, githubAppTokenExpired, githubAppAccessTokenExpiresAt, githubAppRefreshTokenExpiresAt }` |

### 4.3 Project — `com.example.dvely.project.presentation` (+ Approval, Change) (24 + 4 + 3 = 31)

기본 경로 `/api/v1/projects`. 모든 엔드포인트는 `Authorization` 필요, 소유권 검증(`ownerUserId`) 포함.

| 메서드 | 경로 | 용도 | 요청 | 응답(핵심 필드) | 주요 에러 |
|---|---|---|---|---|---|
| POST | `/api/v1/projects` | 프로젝트 생성(DRAFT) + 초기 코드 생성 Agent task 자동 제출 | `{ name, startMode(blank\|template), templateType?, draftMode(fast\|quality) }` | 202 `{ projectId, name, status, taskId, taskStatus, approvalIds }` | 400 |
| POST | `/api/v1/projects/{id}/repository` | GitHub 저장소 연결(신규 생성 또는 기존 import) | `{ repositoryMode(create\|existing), repositoryName?, repositoryFullName?, repositoryVisibility? }` | `{ projectId, repositoryFullName, repositoryVisibility, bindingStatus, repositoryHealth }` | 409(이미 연결됨), 400(저장소 접근 불가) |
| DELETE | `/api/v1/projects/{id}/repository` | 저장소 연결 해제(GitHub 저장소 자체는 삭제 안 함) | - | 204 | 404 |
| GET | `/api/v1/projects/github/repositories` | GitHub App으로 접근 가능한 내 저장소 목록 | - | `[{ fullName, name, owner, visibility, defaultBranch, updatedAt }]` | - |
| GET | `/api/v1/projects` | 내 프로젝트 목록(최신 수정순) | - | `[{ projectId, name, deployStatus, currentUrl, updatedAt, updatedAtRelativeText }]` | - |
| GET | `/api/v1/projects/{id}` | 프로젝트 상세(메타데이터) | - | `{ projectId, name, status, startMode, templateType, draftMode, createdAt, updatedAt }` | 404 |
| PATCH | `/api/v1/projects/{id}` | 프로젝트명 수정(현재 name만 수정 가능) | `{ name }` | `ProjectDetailResponse` (위와 동일 shape) | 400, 404 |
| DELETE | `/api/v1/projects/{id}` | 프로젝트 삭제 | query: `deleteMode`(`PROJECT_ONLY` 기본값 \| `PROJECT_AND_REPOSITORY`) | 204 | 404, 409(락 경합) |
| GET | `/api/v1/projects/{id}/overview` | 개요(도메인/배포/최근 이벤트/커밋/저장소·클라우드 상태/운영조치) | - | `{ currentUrl, deployStatus, currentVersion, repositoryVersion, recentChanges[], latestCommit, repositoryHealth, domainSummary, cloudSummary, operationActions[] }` | 404 |
| GET | `/api/v1/projects/{id}/activity-logs` | 활동 로그(Deployment/Change/Approval/Domain 통합) | - | `[{ type, message, occurredAt }]` | - |
| GET | `/api/v1/projects/{id}/commits` | 연결 저장소 최근 커밋(미연결 시 빈 배열) | - | `[{ sha, message, author, committedAt, relativeTime }]` | - |
| GET | `/api/v1/projects/{id}/repository-health` | 저장소 접근 가능 여부 라이브 확인 | - | `{ health: HEALTHY\|REPOSITORY_NOT_FOUND\|ACCESS_DENIED\|PERMISSION_MISMATCH\|UNKNOWN_ERROR }` | - |
| GET | `/api/v1/projects/{id}/settings/chat` | Agent 승인 정책 조회 | - | `{ projectId, changeApprovalRequired, deploymentApprovalRequired, domainApprovalRequired, infraApprovalRequired }`(생성 시 전부 `true`) | 404 |
| PATCH | `/api/v1/projects/{id}/settings/chat` | Agent 승인 정책 수정(4개 필드 전체 필수) | `{ changeApprovalRequired, deploymentApprovalRequired, domainApprovalRequired, infraApprovalRequired }` | 위와 동일 shape | 400 |
| GET | `/api/v1/projects/{id}/settings/infrastructure` | 선택된 클라우드 연결 조회 | - | `{ projectId, cloudConnectionId, provider, displayName, region, status, lastCheckedAt, updatedAt }`(미선택 시 전부 null) | 404 |
| PUT | `/api/v1/projects/{id}/settings/infrastructure` | 클라우드 연결 선택(CONNECTED만 가능) | `{ cloudConnectionId }` | 위와 동일 shape | 409(대상이 CONNECTED 아님) |
| DELETE | `/api/v1/projects/{id}/settings/infrastructure` | 클라우드 연결 선택 해제(연결 자체는 유지) | - | 204(멱등) | - |
| GET | `/api/v1/projects/{id}/settings/infrastructure/configuration` | 인프라 설정(배포 아키텍처/컴퓨팅/스토리지/네트워크) 조회 | - | `{ projectId, configurable, settings, pendingChange }`(클라우드 미선택이어도 200, `configurable=false`) | 404 |
| PUT | `/api/v1/projects/{id}/settings/infrastructure/configuration` | 인프라 설정 저장/변경(정책 ON이면 승인 필요) | `{ deploymentArchitecture(SERVER\|CONTAINER\|SERVERLESS), computeTier(MICRO\|SMALL\|MEDIUM\|LARGE), storageType(NONE\|OBJECT_STORAGE), networkAccess(PUBLIC\|PRIVATE) }` | 위와 동일 shape(`pendingChange` 채워짐 또는 즉시 `settings` 반영) | 409(CONNECTED 미선택 또는 이미 대기 중인 변경 존재) |
| GET | `/api/v1/projects/{id}/settings/infrastructure/configuration/history` | 인프라 설정 변경 이력(모든 상태 포함, 감사용) | query: `limit`(기본 50, 최대 200) | `[{ changeId, action, status, deploymentArchitecture, computeTier, storageType, networkAccess, approvalId, actorUserId, createdAt, decidedAt }]` | - |
| GET | `/api/v1/projects/{id}/settings/cost-budget` | 비용 추정 + 예산 상태 조회 | - | `{ projectId, costAvailable, provider, currency, estimatedMonthlyCost, resourceCosts[], assumptions[], priceTableVersion, budget, budgetStatus, budgetUsagePercent }` | 404 |
| PUT | `/api/v1/projects/{id}/settings/cost-budget` | 월 예산 설정(upsert, 멱등) | `{ monthlyBudgetAmount, currency? }`(USD만 허용) | 위와 동일 shape(재계산 결과 포함) | 400 |
| DELETE | `/api/v1/projects/{id}/settings/cost-budget` | 예산 해제 | - | 204(멱등) | - |
| GET | `/api/v1/projects/{id}/settings/repository` | 저장소 연결 설정 조회(`defaultBranch`는 GitHub 라이브 조회, 실패 시 null) | - | `{ projectId, connected, repositoryFullName, repositoryUrl, defaultBranch, repositoryVisibility, bindingStatus, repositoryHealth, connectedAt, lastSyncedAt }` | 404 |
| GET | `/api/v1/projects/{id}/approvals` | 프로젝트 승인 목록(전체 상태) | - | `[ApprovalResponse]` | - |
| GET | `/api/v1/approvals/{approvalId}` | 승인 상세 | - | `{ approvalId, projectId, conversationId, taskId, type(CHANGE\|DEPLOYMENT\|DOMAIN_BINDING\|INFRA_OPERATION), status(PENDING\|APPROVED\|REJECTED\|CANCELLED), summary, createdAt, decidedAt }` | 404 |
| POST | `/api/v1/approvals/{approvalId}/approve` | 승인(모든 필요 승인 완료 시 task 자동 재개) | - | `ApprovalResponse` | 409(이미 처리됨) |
| POST | `/api/v1/approvals/{approvalId}/reject` | 거절(task 취소) | - | `ApprovalResponse` | 409(이미 처리됨) |
| GET | `/api/v1/projects/{id}/changes` | 프로젝트 코드 변경(Change) 목록 | - | `[ChangeResponse]` | - |
| GET | `/api/v1/changes/{changeId}` | Change 상세 | - | `{ changeId, projectId, conversationId, taskId, previewSessionId, status(PREVIEW_READY\|DEPLOYED), summary, createdAt, updatedAt }` | 404 |
| GET | `/api/v1/changes/{changeId}/diff` | Change의 git diff 텍스트 | - | `{ changeId, diff }` | 404 |

### 4.4 Chat — `com.example.dvely.chat.presentation` (9)

| 메서드 | 경로 | 용도 | 요청 | 응답(핵심 필드) | 주요 에러 |
|---|---|---|---|---|---|
| GET | `/api/v1/projects/{id}/conversations` | 프로젝트 대화 목록(휴지통 제외, 최신순) | - | `[ConversationResponse]` | - |
| POST | `/api/v1/projects/{id}/conversations` | 대화 생성 | - | 201 `ConversationResponse` | 403(소유자 아님) |
| GET | `/api/v1/conversations/{id}` | 대화 상세 | - | `ConversationResponse` | 404 |
| DELETE | `/api/v1/conversations/{id}` | 대화 삭제(휴지통 이동, soft delete) | - | 204 | 404 |
| GET | `/api/v1/trash/conversations` | 휴지통 대화 목록(삭제 후 7일 이내만) | - | `[ConversationResponse]`(`deleted=true`, `remainingRetentionDays` 포함) | - |
| POST | `/api/v1/trash/conversations/{id}/restore` | 휴지통 대화 복구(7일 경과 시 불가) | - | `ConversationResponse` | 404, 409(7일 경과) |
| DELETE | `/api/v1/trash/conversations/{id}` | 휴지통 대화 영구 삭제 | - | 204 | 404 |
| GET | `/api/v1/conversations/{id}/messages` | 대화 메시지 목록(생성순) | - | `[MessageResponse]` | 404 |
| POST | `/api/v1/conversations/{id}/messages` | 메시지 저장 + Decision Agent 동기 판단(승인 정책에 따라 Agent task 큐잉) | `{ content }` | 201 `{ messageId, conversationId, role, content, tokenCount, createdAt, taskId }`(`taskId`는 판단 성공 시에만 채워짐) | 400, 404 |

### 4.5 Agent — `com.example.dvely.agent.presentation` (8)

기본 경로 `/api/v1/agent`.

| 메서드 | 경로 | 용도 | 요청 | 응답(핵심 필드) | 주요 에러 |
|---|---|---|---|---|---|
| POST | `/decision` | 자연어 요청 분석 → 실행 계획 수립 + 비동기 제출 | `{ content, aiProvider(ANTHROPIC\|OPENAI), projectId?, conversationId? }` | `{ steps[], reasoning, aiProvider, taskId, status, approvalIds }` | 400, 401 |
| GET | `/tasks/{taskId}` | 태스크 상태 조회 | - | `{ taskId, status, previewUrl, summary, error, question, failureLog, suggestedFix, attempt, maxAttempts, retryable }` | 404 |
| GET | `/tasks/{taskId}/events` | 영속 이벤트 목록(증분) | query: `afterEventId`(기본 0) | `[{ eventId, taskId, type, status, message, createdAt }]` | 404 |
| GET | `/tasks/{taskId}/events/stream` | SSE 이벤트 스트림 | query: `afterEventId` | `text/event-stream`(`@RawApiResponse`) | 404(emitter null) |
| POST | `/tasks/{taskId}/input` | `WAITING_INPUT` 상태의 태스크에 사용자 입력 제출 | `{ value }` | 204 | 400(WAITING_INPUT 아님), 404 |
| DELETE | `/tasks/{taskId}` | 대기/실행 중 태스크 취소 | - | 204 | 404 |
| POST | `/tasks/{taskId}/retry` | 실패한 태스크를 저장된 plan + 현재 step부터 재시도 | - | 202 | 409(재시도 불가 상태) |
| DELETE | `/session` | 현재 유저의 모든 PreviewSession 종료(Docker 컨테이너 정리) | - | 204 | 404(종료할 세션 없음) |

`TaskStatus` 전체 값: `PENDING · WAITING_APPROVAL · QUEUED · RETRY_WAIT · RUNNING · WAITING_INPUT · DONE · FAILED · CANCELLED`.
`AgentType`(`POST /decision` 응답의 `steps[].agentType`으로 직접 노출됨. task 상태 폴링 응답에는 없음): `CHAT · CODE · DEPLOY · DOMAIN_BIND · INFRA_OPERATE`.

### 4.6 Deployment — `com.example.dvely.deployment.presentation` (10)

| 메서드 | 경로 | 용도 | 요청 | 응답(핵심 필드) | 주요 에러 |
|---|---|---|---|---|---|
| POST | `/api/v1/projects/{id}/deployments` | GitHub Pages 배포 요청(영속 Job, 즉시 PENDING) | `{ deployTargetType(LATEST\|VERSION), versionName? }` | 202 `{ deploymentId, projectId, deployTargetType, versionName, status, pagesUrl, createdAt }` | 400 |
| GET | `/api/v1/deployments/{id}` | 배포 상태 조회(진행 중이면 GitHub Actions 실시간 buildStatus 포함) | - | `{ historyId, projectId, deployTargetType, versionLabel, deployedUrl, status(PENDING\|IN_PROGRESS\|LIVE\|FAILED), buildStatus, buildConclusion, triggeredAt, updatedAt }` | 404 |
| GET | `/api/v1/projects/{id}/deployments` | 배포 이력 목록(전체, 최신순) | - | `[{ historyId, projectId, deployTargetType, versionLabel, deployedUrl, status, triggeredAt, updatedAt, retriedFromHistoryId }]` | - |
| GET | `/api/v1/projects/{id}/versions` | merge 커밋 기준 버전 목록 | - | `[{ versionId, versionName, commitSha, title, deployStatus, mergedAt }]` | - |
| GET | `/api/v1/projects/{id}/deployment-candidates` | 재배포/롤백 가능한 성공(LIVE·PREVIEW_READY) 버전만 | - | `[{ versionId, versionName, commitSha, title, deployStatus, deployedUrl, deployedAt }]` | - |
| GET | `/api/v1/deployments/{id}/logs` | GitHub Actions 빌드 로그(Job/Step 상태 + 전체 로그 텍스트) | - | `{ historyId, workflowRunId, jobs[{ jobId, name, status, conclusion, steps[] }], logText }` | 404 |
| POST | `/api/v1/deployments/{id}/retry` | 실패한 배포를 새 이력으로 재큐잉(기존 이력 보존) | - | 201 `DeployResponse` | 409(대상이 FAILED 아님) |
| POST | `/api/v1/deployments/{id}/failure-analysis` | 실패 원인 분석 실행(LLM, 이미 있으면 멱등 재사용) | - | `{ deploymentId, summary, logExcerpt, suggestedFix, analysisSource(LLM\|RULE_BASED), analyzedAt }` | 409(대상이 FAILED 아님) |
| GET | `/api/v1/deployments/{id}/failure-analysis` | 저장된 분석 결과만 조회(부작용 없음) | - | 위와 동일 shape | 404(분석 이력 없음 — 먼저 POST 필요) |
| GET | `/api/v1/versions/{versionId}` | 버전 상세(merge 유저/PR/배포 URL) | - | `{ versionId, versionName, commitSha, title, description, deployStatus, deployedUrl, mergedBy, mergedByAvatarUrl, prNumber, mergedAt }` | 404 |

### 4.7 DomainBinding — `com.example.dvely.domainbinding.presentation` (7)

| 메서드 | 경로 | 용도 | 요청 | 응답(핵심 필드) | 주요 에러 |
|---|---|---|---|---|---|
| GET | `/api/v1/domain-search` | 관리형 서브도메인 사용 가능 여부 검색 | query: `keyword` | `{ keyword, results[{ type, hostname, available, price, currency }] }` | - |
| GET | `/api/v1/projects/{id}/domains` | 프로젝트 연결 도메인 목록 | - | `[DomainResponse]` | - |
| POST | `/api/v1/projects/{id}/domains` | 도메인 연결 요청(Agent task로 비동기 처리) | `{ type(managed_subdomain\|custom_domain\|purchasable_domain), label?, hostname?, verificationMethod?(CNAME\|A), hostingTarget?(GITHUB_PAGES\|AWS\|GCP) }` | 202 `{ taskId, status, approvalIds }` | 400 |
| GET | `/api/v1/domains/{id}` | 도메인 상태 조회 | - | `{ domainId, projectId, type, hostingTarget, hostname, status(REQUESTED\|PROVISIONING\|VERIFYING\|CONNECTED\|FAILED), verificationMethod, dnsTarget, httpsEnforced, certificateStatus, certificateExpiresAt, lastCheckedAt }` | 404 |
| GET | `/api/v1/domains/{id}/verification-guide` | DNS에 등록할 A/CNAME 레코드 안내 | - | `{ hostname, verificationMethod, records[{ type, host, value }] }` | 404 |
| POST | `/api/v1/domains/{id}/verification-checks` | DNS 검증 재시도(현재 DNS 조회해 상태 갱신) | - | `DomainResponse` | 404 |
| DELETE | `/api/v1/domains/{id}` | 도메인 연결 해제 요청(Agent task로 비동기 처리) | - | 202 `{ taskId, status, approvalIds }` | 404 |

### 4.8 Environment — `com.example.dvely.environment.presentation` (5)

기본 경로 `/api/v1/projects/{id}/environment-variables`.

| 메서드 | 경로 | 용도 | 요청 | 응답(핵심 필드) | 주요 에러 |
|---|---|---|---|---|---|
| GET | `(기본 경로)` | 환경변수 목록(scope 필터, scope asc → key asc 정렬) | query: `scope?`(PREVIEW\|PRODUCTION) | `[{ environmentVariableId, scope, key, value(secret이면 항상 null), secret, createdAt, updatedAt }]` | - |
| GET | `/history` | 변경 이력(값 자체는 기록 안 함) | query: `limit?`(기본 50, 최대 200) | `[{ historyId, environmentVariableId, scope, key, action(CREATED\|UPDATED\|DELETED), secret, valueChanged, actorUserId, createdAt }]` | - |
| POST | `(기본 경로)` | 환경변수 생성 | `{ key, value, scope(PREVIEW\|PRODUCTION), secret }` | 201 위 목록 항목 shape | 409(동일 프로젝트+scope+key 중복) |
| PATCH | `/{variableId}` | value/secret만 수정(key·scope 불변) | `{ value?, secret? }`(PATCH 시맨틱: 생략=유지) | 위와 동일 shape | 400(secret true→false 시도) |
| DELETE | `/{variableId}` | 삭제 | - | 204 | 404(멱등 아님) |

### 4.9 CloudConnection — `com.example.dvely.cloudconnection.presentation` (7)

기본 경로 `/api/v1/cloud-connections`.

| 메서드 | 경로 | 용도 | 요청 | 응답(핵심 필드) | 주요 에러 |
|---|---|---|---|---|---|
| GET | `(기본 경로)` | 내 클라우드 연결 목록 | - | `[CloudConnectionResponse]` | - |
| POST | `(기본 경로)` | AWS/GCP 연결 등록(형식 검증만, 실 권한 확인은 별도 Job) | `{ provider(AWS\|GCP), displayName, region, accountId?, roleArn?, awsCredentialType?(ACCESS_KEY\|ROLE_ARN), accessKeyId?, secretAccessKey?, sessionToken?, gcpCredentialType?(SERVICE_ACCOUNT_KEY\|SERVICE_ACCOUNT_EMAIL), serviceAccountKeyJson?, projectId?, serviceAccountEmail? }` | 201 `{ cloudConnectionId, provider, status(보통 VALIDATED), jobId }` | 400 |
| GET | `/{id}` | 연결 상세 조회 | - | `CloudConnectionResponse`(시크릿은 `*Configured` boolean으로만) | 404 |
| GET | `/{id}/health` | 저장된 마지막 상태 조회(재검증 아님) | - | `{ cloudConnectionId, provider, status, message, checkedAt }` | 404 |
| POST | `/{id}/verification-jobs` | 실 권한 확인 Job 생성 | - | 202 `{ jobId, cloudConnectionId, status, connectionStatus, message, attempt, createdAt, startedAt, completedAt }` | 404 |
| GET | `/api/v1/cloud-connection-verification-jobs/{jobId}` | 검증 Job 상태 폴링 | - | 위와 동일 shape(`status`: PENDING\|RUNNING\|SUCCEEDED\|FAILED) | 404 |
| DELETE | `/{id}` | 연결 정보 삭제(실 클라우드 리소스는 삭제 안 함) | - | 204 | 404 |

`CloudConnectionStatus` 전체 값: `VALIDATED · VERIFYING · CHECKING · CONNECTED · PERMISSION_MISSING · BILLING_DISABLED · REGION_UNSUPPORTED · INVALID_CREDENTIAL · UNKNOWN_ERROR`.

### 4.10 Preview — `com.example.dvely.preview.presentation` (4개 논리 엔드포인트, 프록시가 경로 패턴 2개를 가져 Swagger에는 5개로 집계)

| 메서드 | 경로 | 용도 | 인증 | 응답 |
|---|---|---|---|---|
| GET | `/api/v1/previews/{sessionId}/{accessToken}/**` | Docker 프리뷰 컨테이너로 리버스 프록시(HTML/JS/CSS 등 원본 그대로) | URL 내장 1회성 토큰(JWT 아님) | 프리뷰 앱 응답 그대로(`@RawApiResponse`). 세션 없음/토큰 불일치 시 404 |
| DELETE | `/api/v1/preview-sessions/{sessionId}` | 세션 종료 + 컨테이너 정리 | Bearer | 204(404: 없음/타 유저 소유) |
| GET | `/api/v1/preview-sessions/{sessionId}/status` | 컨테이너 실행 여부·리소스 사용량 조회(p95 ~1.5초 — 폴링은 5초 이상 권장) | Bearer | `{ sessionId, projectId, taskId, sessionStatus(ACTIVE\|CLOSED\|EXPIRED), containerRunning, oomKilled, exitCode, startedAt, expiresAt, resources{ memoryUsageBytes, memoryLimitBytes, memoryUsagePercent, cpuPercent } }`(resources는 미실행/조회 3초 초과 시 null) |
| GET | `/api/v1/preview-sessions/{sessionId}/logs` | 컨테이너 stdout/stderr 텍스트 조회(영속화 안 됨) | Bearer | `{ sessionId, containerRunning, logText }`(query: `tail`(기본 200, [1,2000] 클램프), `sinceSeconds`) |

프리뷰 세션은 별도 생성 API가 없습니다 — Agent CODE 스텝이 내부적으로 생성하며, `previewUrl`은 `GET /api/v1/agent/tasks/{taskId}` 응답에서 얻습니다.

### 4.11 Webhook — `com.example.dvely.webhook.presentation` (1)

| 메서드 | 경로 | 용도 |
|---|---|---|
| POST | `/api/v1/webhook/github` | GitHub App webhook 수신 전용(**FE에서 호출하지 않음**). `X-Hub-Signature-256` HMAC 서명 검증 후 push/pull_request/installation 이벤트 처리 |

---

## 5. 핵심 플로우 예시

아래 예시는 실제 DTO 필드명과 일치합니다(테스트/문서 목적의 예시 값 사용). 모든 요청에 `Authorization: Bearer {accessToken}` 헤더가 필요합니다(명시 생략).

### 5.1 프로젝트 생성 → 승인 → agent task 폴링 → preview

```
① POST /api/v1/projects
{ "name": "my-landing", "startMode": "blank", "templateType": null, "draftMode": "fast" }

→ 202
{ "status": 202, "code": "SUCCESS", "message": "요청이 접수되었습니다", "data": {
    "projectId": 12, "name": "my-landing", "status": "DRAFT",
    "taskId": "a1b2c3d4e5f6", "taskStatus": "WAITING_APPROVAL", "approvalIds": [34]
} }
```
프로젝트 생성 시 Chat 승인 정책은 기본값이 전부 `true`이므로, 이 초기 CODE 작업도 `changeApprovalRequired` 정책의 적용을 받아 **승인 대기**로 시작합니다.

```
② POST /api/v1/approvals/34/approve
→ { "approvalId": 34, "status": "APPROVED", ... }
   (내부적으로 taskId=a1b2c3d4e5f6이 QUEUED로 전환되어 worker가 실행 시작)

③ GET /api/v1/agent/tasks/a1b2c3d4e5f6   (수 초 간격 폴링)
→ status: QUEUED → RUNNING → DONE
   DONE 시: { "status": "DONE", "previewUrl": "https://api.qeploy.com/api/v1/previews/101/abcdef.../", "summary": "..." }
```

```
④ (선택) previewUrl을 iframe/새 탭으로 열어 결과 확인
⑤ (선택) 실제 GitHub 배포를 하려면 저장소를 먼저 연결:
   POST /api/v1/projects/12/repository
   { "repositoryMode": "create", "repositoryName": "my-landing-repo", "repositoryVisibility": "PRIVATE" }
```

CODE 단계는 GitHub 저장소 연결과 무관하게 동작합니다(Docker 프리뷰 컨테이너 기반) — 저장소 연결은 실제 배포(§5.3)를 위해서만 필요합니다.

### 5.2 Chat 메시지 → taskId 폴링

```
① POST /api/v1/conversations/101/messages
{ "content": "랜딩 페이지에 FAQ 섹션을 추가해줘" }

→ 201
{ "data": {
    "messageId": 3001, "conversationId": 101, "role": "user",
    "content": "랜딩 페이지에 FAQ 섹션을 추가해줘", "tokenCount": 0,
    "createdAt": "2026-07-18T10:00:00", "taskId": "b2c3d4e5f6a1"
} }
```
`taskId`가 `null`이면 Decision Agent 판단에 실패한 것(메시지 자체는 저장됨) — 이 경우 재요청을 안내하세요.

```
② GET /api/v1/agent/tasks/b2c3d4e5f6a1  (§3.6 폴링 또는 SSE)
→ DONE 시 summary/previewUrl 확인
```

### 5.3 배포 → 실패 분석 → 재시도

```
① POST /api/v1/projects/12/deployments
{ "deployTargetType": "LATEST" }
→ 202 { "deploymentId": 501, "status": "PENDING", "pagesUrl": null, ... }

② GET /api/v1/deployments/501   (폴링)
→ status: PENDING → IN_PROGRESS(buildStatus: queued → in_progress → completed) → LIVE 또는 FAILED

③ (FAILED인 경우) POST /api/v1/deployments/501/failure-analysis
→ (LLM 분석, 15~30초 소요 가능) { "summary": "...", "suggestedFix": "...", "analysisSource": "LLM" }

④ 같은 분석 재조회: GET /api/v1/deployments/501/failure-analysis  (즉시 응답, LLM 재호출 없음)

⑤ POST /api/v1/deployments/501/retry
→ 201 { "deploymentId": 502, "projectId": 12, "deployTargetType": "LATEST", "status": "PENDING", "pagesUrl": null, "createdAt": "..." }
   (501은 FAILED 상태 그대로 감사 목적으로 보존됨. 이후 GET /api/v1/deployments/502 로 새 이력을 계속 폴링.
    GET /api/v1/projects/12/deployments 로 이력 목록을 다시 조회하면 502 항목의 retriedFromHistoryId가 501을 가리킴)
```

### 5.4 Environment secret CRUD (마스킹)

```
① POST /api/v1/projects/12/environment-variables
{ "key": "STRIPE_SECRET_KEY", "value": "sk_live_...", "scope": "PRODUCTION", "secret": true }
→ 201 { "environmentVariableId": 7, "scope": "PRODUCTION", "key": "STRIPE_SECRET_KEY",
         "value": null, "secret": true, ... }   // value는 즉시 null

② GET /api/v1/projects/12/environment-variables?scope=PRODUCTION
→ [{ ..., "value": null, "secret": true, ... }]   // 목록에서도 절대 노출 안 됨

③ PATCH /api/v1/projects/12/environment-variables/7
{ "value": "sk_live_new..." }   // secret 생략 → 기존 true 유지
→ { ..., "value": null, "secret": true, ... }

④ GET /api/v1/projects/12/environment-variables/history
→ [{ "action": "UPDATED", "valueChanged": true, "secret": true, ... }]   // 값 자체는 없음, 변경 여부만

⑤ DELETE /api/v1/projects/12/environment-variables/7
→ 204
```

### 5.5 Infra configuration → 승인

```
① GET /api/v1/projects/12/settings/infrastructure
→ { "cloudConnectionId": null, ... }   // 아직 클라우드 연결 미선택

② PUT /api/v1/projects/12/settings/infrastructure
{ "cloudConnectionId": 3 }   // 3번 연결은 반드시 status=CONNECTED
→ { "cloudConnectionId": 3, "provider": "AWS", "status": "CONNECTED", ... }

③ GET /api/v1/projects/12/settings/infrastructure/configuration
→ { "configurable": true, "settings": null, "pendingChange": null }

④ PUT /api/v1/projects/12/settings/infrastructure/configuration
{ "deploymentArchitecture": "CONTAINER", "computeTier": "SMALL", "storageType": "OBJECT_STORAGE", "networkAccess": "PUBLIC" }
→ infraApprovalRequired=true(기본값)이면:
   { "configurable": true, "settings": null,
     "pendingChange": { "changeId": 9, "approvalId": 41, "action": "CREATED", ... } }
   → 즉시 반영되지 않음. 아래로 승인 필요:

⑤ POST /api/v1/approvals/41/approve
→ 승인 후 다시 GET configuration 하면 settings가 채워지고 pendingChange는 null

⑥ GET /api/v1/projects/12/settings/infrastructure/configuration/history
→ [{ changeId: 9, status: "APPLIED", ... }]
```

동일 값으로 다시 PUT하면 이력·승인 없이 현재 상태 그대로 반환(no-op)되고, 이미 대기 중인 변경이 있으면 409입니다(먼저 처리 필요).

### 5.6 Cost-budget 조회/설정

```
① GET /api/v1/projects/12/settings/cost-budget
→ { "costAvailable": true, "provider": "AWS", "estimatedMonthlyCost": 26.15,
     "resourceCosts": [{ "resourceType": "COMPUTE", "description": "SERVER · SMALL (AWS)", "monthlyCost": 17.00 }, ...],
     "budget": null, "budgetStatus": "NO_BUDGET", "budgetUsagePercent": null }

② PUT /api/v1/projects/12/settings/cost-budget
{ "monthlyBudgetAmount": 50.00, "currency": "USD" }
→ { ..., "budget": { "monthlyBudgetAmount": 50.00, "currency": "USD", "updatedAt": "..." },
     "budgetStatus": "WITHIN_BUDGET", "budgetUsagePercent": 52.3 }

③ DELETE /api/v1/projects/12/settings/cost-budget
→ 204(예산 해제, 멱등)
```
`estimatedMonthlyCost`는 정적 가격표 기반 **가정 추정치**이며 실시간 클라우드 청구 금액이 아닙니다. 인프라 미구성/클라우드 미연결 상태여도 200과 `costAvailable=false`로 응답합니다(추정 필드는 null/빈 배열).
`budgetStatus`: `NO_BUDGET`(예산 미설정) · `WITHIN_BUDGET` · `OVER_BUDGET` · `NOT_EVALUABLE`(예산은 있지만 추정 불가 상태).

### 5.7 Cloud Ops (자연어 chat 경유 — INFRA_OPERATE)

인프라 운영(상태 확인/로그 조회/장애 분석/재시작)은 **전용 HTTP API가 없고 오직 Chat 자연어 요청**으로만 실행됩니다(Decision Agent가 `INFRA_OPERATE`로 분류).

```
① POST /api/v1/conversations/101/messages
{ "content": "지금 서버 상태 알려줘" }
→ 201 { "taskId": "c3d4e5f6a1b2", ... }

② GET /api/v1/agent/tasks/c3d4e5f6a1b2   (폴링)
→ DONE, summary: "배포: LIVE(v3) ... / Preview: 실행 중 ... / 클라우드 연결: AWS CONNECTED ...
   / 저장된 인프라 설정의 실 클라우드 리소스는 아직 프로비저닝되지 않았습니다."
```
`STATUS_CHECK`/`LOG_VIEW`/`FAILURE_ANALYSIS`는 읽기 전용이라 승인 없이 바로 실행됩니다. 서비스에 영향을 주는 `RESTART`(현재 지원되는 유일한 조작형 작업 — preview 컨테이너 한정)는 `infraApprovalRequired` 정책(기본 `true`)에 따라 일반 플로우와 동일하게 승인을 거칩니다:

```
① POST /api/v1/conversations/101/messages
{ "content": "preview 서버 재시작해줘" }
→ 201 { "taskId": "d4e5f6a1b2c3", ... }

② GET /api/v1/agent/tasks/d4e5f6a1b2c3
→ status: "WAITING_APPROVAL"  (Approval.type = "INFRA_OPERATION", summary에 "[서비스 영향]" 마커 포함)

③ POST /api/v1/approvals/{approvalId}/approve
④ GET /api/v1/agent/tasks/d4e5f6a1b2c3 → DONE, summary: "preview 서버를 재시작했습니다 ..."
```
리소스 스펙 변경(RESOURCE_SCALING)·오토스케일링·리소스 정리는 아직 실행이 지원되지 않아, 요청 시 승인 없이 즉시 "아직 지원하지 않음 + 대체 경로 안내"(예: §5.5의 인프라 설정 API로 유도)가 반환됩니다. 실 클라우드 서버 프로비저닝(BI-130)은 아직 구현되지 않았으므로, 모든 응답에 이 사실이 명시됩니다.

---

## 6. 참고

- 이 문서의 모든 필드명·상태값은 각 컨트롤러·DTO(`src/main/java/com/example/dvely/**/presentation/**`)와 일치합니다. 스키마가 바뀌면 이 문서도 함께 갱신해야 합니다.
- 통합 테스트/수동 시나리오는 FE 정본 저장소의 `Dvely_FE_test/API_TEST_FLOW.md`(콘솔 기반 수동 QA 흐름)도 함께 참고하세요.
- Swagger UI 상단 Info 블록(SwaggerConfig)에도 이 문서의 §3(공통 규약) 요약이 markdown으로 포함되어 있습니다.
