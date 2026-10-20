# Qeploy 백엔드 API 명세

> 기준일: 2026-10-20 · 기준 커밋: 02a2240 · 코드 실측 기반

## 1. 문서 기준

- 기준일: 2026-10-20
- 기준 커밋: `02a2240` (PR #475 플래너 instruction 언어 반영, develop). 직전 기준은 `113abf0`(2026-08-15, PR #104 프리뷰 게이트웨이 인가 반영)
- 기준 코드: `src/main/java/com/example/dvely/**/presentation/*Controller.java`
- 현재 공개 엔드포인트: 117개, 직전 기준(`113abf0` 시점) 94개에서 +23 — Agent +2(`GET /agent/ai-providers` §9.6, `GET /agent/conversations/{conversationId}/active-task` §9), AiCredential +3(§16), ApiToken +3(§18), Template +2(§17), CloudConnection +1(`GET /cloud-connections/requirements`), DomainBinding +1(`GET /domains/hosting-targets`), DomainTls +1(`GET /tls/allow`), PreviewGateway +1(SSE 프록시), PreviewRuntimeConfig +2(`GET/PUT /projects/{projectId}/preview/runtime`), Provisioning +7(§19). 2026-09-28 이후로는 수가 바뀌지 않았다
  - 모듈별: Agent 10 · AiCredential 3 · ApiToken 3 · Approval 4 · AuditLog 1 · Auth 7 · Change 3 · Chat 9 · CloudConnection 8 · Deployment 10 · DomainBinding 8 · DomainTls 1 · Environment 5 · PreviewGateway 2 · PreviewRuntimeConfig 2 · PreviewSession 4 · ProjectPreview 2 · Project 24 · Provisioning(DB) 3 · Provisioning(Server) 4 · Template 2 · User 1 · Webhook 1
  - 재집계 명령: `grep -rE "@GetMapping|@PostMapping|@PutMapping|@PatchMapping|@DeleteMapping|@RequestMapping\(" src/main/java --include="*Controller.java"` 후 클래스 레벨 `@RequestMapping` 6개(Agent/Auth/PreviewSession/Project/User/Webhook) 제외
- 이 문서는 현재 코드에 존재하는 API와 PRD 달성을 위해 앞으로 필요한 API를 구분한다.
- 아래의 `예정 API`는 설계 후보이며 아직 호출 가능한 계약이 아니다.

---

## 2. 공통 규칙

### 2.1 Prefix와 인증

- 기본 prefix는 `/api/v1`이다.
- 공개 API를 제외한 요청은 `Authorization: Bearer {accessToken}`이 필요하다.
- 에이전트·CLI는 JWT 대신 개인 액세스 토큰(`Authorization: Bearer qp_...`, §18)으로도 인증한다. 필터가 `qp_` 접두사로 경로를 고르며, 스코프·쓰기 허용 목록 제약은 §18.3 참고.
- Spring Security가 JWT의 사용자 ID를 `@AuthenticationPrincipal Long userId`로 전달한다.

인증 없이 접근 가능한 경로:

- `GET /api/v1/auth/github/url`
- `GET /api/v1/auth/github/callback`
- `GET /api/v1/auth/github/app/callback`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/webhook/github`
- `/api/v1/previews/**` — 프리뷰 게이트웨이. JWT 대신 URL의 회전 accessToken + 소유권 쿠키로 인가한다(§13.4)
- `GET /api/v1/tls/allow?domain=` — 배포 EC2의 Caddy on-demand TLS 확인용(§19.4)
- `GET /api/v1/templates`, `GET /api/v1/templates/{templateId}` — 템플릿 카탈로그(§17)
- `GET /actuator/health` — 배포 기동 확인용, 상태값만 노출
- Swagger/OpenAPI 경로

### 2.2 요청 상관관계 ID (Issue #345)

- 모든 응답에 `X-Request-Id`가 붙는다. 장애 문의 시 이 값으로 서버 로그를 바로 집을 수 있다.
- 클라이언트가 같은 헤더로 값을 보내면 그대로 이어받는다. 다만 `[A-Za-z0-9._-]{1,64}`에 맞을 때만 받고, 어긋나면 조용히 새로 만든다(로그 인젝션 차단). 거절이 아니라 대체이므로 요청은 그대로 처리된다.

### 2.3 응답 형식

현재 응답 형식은 통일되어 있지 않다.

- Auth, User: `ApiResponse<T>`
- Project, Chat, Agent, Deployment, DomainBinding, CloudConnection, Approval, Change, PreviewSession, Environment, AuditLog, AiCredential, ApiToken, Template, Provisioning: DTO 직접 반환
- 삭제: 주로 `204 No Content`
- Agent 상태 조회/입력: `ResponseEntity`
- 일반 JSON 성공 응답은 공통 MVC advice(`ApiResponseAdvice`)가 `status/code/message/data` envelope로 자동 변환한다. 제외 대상: `@RawApiResponse`가 클래스에 붙은 Webhook·PreviewGateway 컨트롤러, 메서드에 붙은 `GET /agent/tasks/{taskId}/events/stream`(SSE)·`GET /auth/github/app/callback`(302 redirect), 이미 `ApiResponse<T>`를 반환하는 Auth·User, `SseEmitter`/`byte[]`/`void`/`text/event-stream` 응답. Agent의 나머지 `ResponseEntity` 응답은 envelope로 감싸진다.

### 2.4 공통 오류

`GlobalExceptionHandler`가 처리하는 주요 오류:

- `400 Bad Request`: 입력값 또는 현재 상태가 요청과 맞지 않음
- `401 Unauthorized`: 인증 실패
- `403 Forbidden`: 권한 또는 선행 조건 부족
- `404 Not Found`: 사용자 소유 리소스 또는 대상 리소스 없음
- `405 Method Not Allowed`: `METHOD_NOT_ALLOWED`
- `409 Conflict`: 중복 생성 등 상태 충돌 (예: 동일 (project, scope, key) 환경변수 재생성). `IllegalStateException` 전반과 낙관적 잠금 경합(`@Version`, "다른 요청과 동시에 수정되어…")도 409다
- `429`/`502`/`503`: AI 제공자 호출 실패 — `AI_PROVIDER_RATE_LIMITED`(429) · `AI_PROVIDER_ERROR`(502) · `AI_PROVIDER_UNAVAILABLE`(503)
- `503 Service Unavailable`: `PREVIEW_ENVIRONMENT_UNAVAILABLE`(Docker 접근 불가), `TEMPLATE_CATALOG_UNAVAILABLE`(§17.3)
- `500 Internal Server Error`: 처리되지 않은 외부 연동/서버 오류

도메인 전용 코드: `GITHUB_APP_NOT_INSTALLED`(403) · `GITHUB_APP_REPOSITORY_ACCESS_DENIED`(403) · `AI_CREDENTIAL_NOT_REGISTERED`(400) · `INVALID_TOKEN`/`EXPIRED_REFRESH_TOKEN`/`REVOKED_REFRESH_TOKEN`(401).

- 클라이언트가 응답 도중 연결을 끊은 경우(SSE 탭 닫기 등, `AsyncRequestNotUsableException`)는 오류 응답을 쓰지 않고 DEBUG 로그 한 줄만 남긴다(#423). ERROR 수를 배포 건강 신호로 쓰기 때문이다.
- PAT 스코프 거부(403, §18.3)는 보안 필터가 직접 쓰며 본문이 `{"code":"FORBIDDEN","message":"..."}`로 `status`/`data`가 없다.

### 2.5 현재 모듈

- Auth
- User
- Webhook
- Project
- Chat
- Agent
- Deployment
- DomainBinding
- CloudConnection
- Approval
- Change
- Preview (PreviewGateway, PreviewSession)
- Environment
- AuditLog
- Template
- AiCredential
- ApiToken
- Provisioning (Server, Database)

---

## 3. Auth API

Base path: `/api/v1/auth`

| Method | Path | 인증 | 기능 |
|---|---|---:|---|
| GET | `/github/url` | 불필요 | GitHub OAuth 로그인 URL 생성 |
| GET | `/github/callback` | 불필요 | OAuth code 교환, 사용자 저장/갱신, 서비스 토큰 발급 |
| GET | `/github/app/install-url` | 필요 | GitHub App 신규 설치 URL 생성 |
| GET | `/github/app/reauthorize-url` | 필요 | 설치를 유지한 채 GitHub App User Token 재인증 URL 생성 |
| GET | `/github/app/callback` | 불필요 | GitHub App 설치/재인증 결과 처리 후 프론트로 redirect |
| POST | `/refresh` | 불필요 | Refresh Token rotation과 새 토큰 발급 |
| DELETE | `/logout` | 필요 | Access Token 폐기와 사용자 Refresh Token 폐기 |

### 3.1 로그인 callback

`GET /api/v1/auth/github/callback?code={code}&state={state}`

응답:

```json
{
  "status": 200,
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다",
  "data": {
    "accessToken": "service-jwt",
    "refreshToken": "refresh-token",
    "githubAppInstalled": true
  }
}
```

### 3.2 GitHub App callback

Query:

- `installation_id`: 설치 ID, 재인증에서는 없을 수 있음
- `setup_action`: `install`, `update`, `delete`
- `state`: 설치 URL에 넣은 서비스 JWT
- `code`: GitHub User Token 교환용 code

성공 시 프론트 redirect URL에 `githubAppLinked=true`를 붙인다. 실패 시 `githubAppLinked=false&error=...`를 붙인다.

redirect 대상은 `{프론트 redirect URL}/auth/app-callback`이며 `error` 값은 UTF-8로 인코딩해 싣는다(#367 — 한글 메시지가 그대로 붙어 콜백 자체가 400을 내던 문제). `setup_action=delete`는 아무것도 바꾸지 않고 `githubAppLinked=true`로 돌려보낸다.

### 3.3 Refresh

```json
{
  "refreshToken": "refresh-token"
}
```

---

## 4. User API

| Method | Path | 기능 |
|---|---|---|
| GET | `/api/v1/users/me` | 로그인 사용자 프로필과 GitHub App/Token 상태 조회 |

주요 응답 필드:

- `id`, `username`, `avatarUrl`
- `githubAppInstalled`
- `githubAppTokenLinked`
- `githubAppTokenExpired` — 액세스 토큰 만료 여부(진단용). true여도 리프레시 토큰이 살아 있으면 서버가 자동 갱신하므로 재인증 판단에 쓰지 않는다
- `githubAppReauthorizationRequired` — 사용자가 직접 재인증해야 하는지. **재인증 UI는 이 값이 true일 때만 띄운다.** App 미설치 사용자는 재인증이 아니라 설치 대상이라 false다(#367)
- `githubAppAccessTokenExpiresAt`
- `githubAppRefreshTokenExpiresAt`

현재 `/api/v1/me` 또는 `/api/v1/me/github-connection` 경로는 없다. 프론트는 `/api/v1/users/me`를 사용해야 한다.

---

## 5. Webhook API

| Method | Path | 인증 | 기능 |
|---|---|---:|---|
| POST | `/api/v1/webhook/github` | JWT 불필요 | GitHub App webhook 수신 |

필수 헤더:

- `X-GitHub-Event`
- `X-GitHub-Delivery`
- `X-Hub-Signature-256`

처리 흐름:

- HMAC-SHA256 서명 검증
- delivery GUID 기준 중복 확인 후 DB queue 저장
- 즉시 `202 Accepted` 반환
- worker가 lease를 획득해 이벤트 처리
- 실패 시 backoff 후 최대 5회 재시도하고 상태와 오류 기록

처리 이벤트:

- `workflow_run`: run ID/correlation ID, 저장소, head SHA가 일치하는 배포만 반영
- `push`: 기본 브랜치 commit snapshot과 repository health 동기화
- `push`: `vN` tag를 최신 `repositoryVersion`으로 동기화
- `pull_request`: 기본 브랜치에 merge된 PR의 merge commit snapshot 동기화
- `installation`: 삭제/중단/복구/권한 갱신을 사용자 GitHub App 연결과 project health에 반영

delivery 처리 상태:

- `PENDING`
- `PROCESSING`
- `RETRY_WAIT`
- `COMPLETED`
- `IGNORED`
- `FAILED`

현재 delivery 상태 조회용 공개 API는 없다. 처리 기록은 DB에 남지만 **터미널 상태(`COMPLETED`/`IGNORED`/`FAILED`)는 수신 후 7일이 지나면 삭제된다**(#338, `qeploy.webhook.retention-days` 기본 7, 매시간 스윕). 처리 대기·재시도 대기·처리 중인 배달은 지우지 않는다.

---

## 6. Project API

Base path: `/api/v1/projects`

| Method | Path | 기능 |
|---|---|---|
| POST | `/api/v1/projects` | GitHub 저장소 없이 DRAFT 프로젝트 생성(`202`). 코드 생성은 첫 대화 요청에서 시작 |
| POST | `/api/v1/projects/{projectId}/repository` | 새 저장소 생성 또는 기존 저장소 연결 |
| DELETE | `/api/v1/projects/{projectId}/repository` | GitHub 저장소 연결 해제(비파괴, GitHub 호출 없음) |
| GET | `/api/v1/projects/github/repositories` | 접근 가능한 GitHub 저장소 목록 조회. 유저별 60초 캐시, `?refresh=true`로 무효화 |
| GET | `/api/v1/projects` | 내 프로젝트 목록을 최근 수정순 조회 |
| GET | `/api/v1/projects/{projectId}` | 프로젝트 기본 정보 조회 |
| PATCH | `/api/v1/projects/{projectId}` | 프로젝트 이름 수정 |
| DELETE | `/api/v1/projects/{projectId}` | 프로젝트 제거 또는 저장소 포함 삭제 |
| GET | `/api/v1/projects/{projectId}/overview` | Overview 데이터 조회 |
| GET | `/api/v1/projects/{projectId}/activity-logs` | 프로젝트 활동 요약 조회 |
| GET | `/api/v1/projects/{projectId}/commits` | GitHub 최근 커밋 조회 |
| GET | `/api/v1/projects/{projectId}/repository-health` | 저장소 접근 상태 조회 |
| GET | `/api/v1/projects/{projectId}/settings/chat` | 프로젝트 Chat 승인 정책 조회 |
| PATCH | `/api/v1/projects/{projectId}/settings/chat` | 프로젝트 Chat 승인 정책 수정 |
| GET | `/api/v1/projects/{projectId}/settings/infrastructure` | 프로젝트 Infrastructure(CloudConnection) 설정 조회 |
| PUT | `/api/v1/projects/{projectId}/settings/infrastructure` | 프로젝트에 CONNECTED 상태 CloudConnection 선택 |
| DELETE | `/api/v1/projects/{projectId}/settings/infrastructure` | 프로젝트 CloudConnection 선택 해제 |
| GET | `/api/v1/projects/{projectId}/settings/repository` | 연결된 GitHub 저장소 정보와 기본 브랜치 조회 |
| GET | `/api/v1/projects/{projectId}/settings/infrastructure/configuration` | 프로젝트 인프라 설정(아키텍처/컴퓨팅/스토리지/네트워크) 조회 |
| PUT | `/api/v1/projects/{projectId}/settings/infrastructure/configuration` | 인프라 설정 저장/변경(정책에 따라 즉시 적용 또는 INFRA_OPERATION 승인 대기) |
| GET | `/api/v1/projects/{projectId}/settings/infrastructure/configuration/history` | 인프라 설정 변경 이력 조회 |
| GET | `/api/v1/projects/{projectId}/settings/cost-budget` | 인프라 설정 기반 비용 추정 + 예산 조회 |
| PUT | `/api/v1/projects/{projectId}/settings/cost-budget` | 월 예산 저장(upsert) |
| DELETE | `/api/v1/projects/{projectId}/settings/cost-budget` | 월 예산 해제 |

### 6.1 프로젝트 생성

```json
{
  "name": "my-landing",
  "startMode": "blank",
  "templateType": "landing",
  "draftMode": "fast"
}
```

`startMode`(`blank`/`template`)와 `draftMode`(`fast`/`quality`)는 검증·정규화되어 저장된다. 프로젝트 생성은 **프로젝트 행만 만든다** — 초기 CODE task 를 함께 제출하던 경로는 제거됐다(제출돼도 실행된 적이 없었다). 첫 코드는 사용자가 첫 요청을 보낼 때 CODE Agent 가 만든다.

`startMode=template` 이면 `templateType` 은 **템플릿 카탈로그(§17)에 실재하는 ID 여야 한다.** 정규화(소문자화·공백→하이픈) 후 대조하므로 `"E Commerce"` 는 `e-commerce` 로 조회된다. 없는 ID 는 `400`, 카탈로그를 한 번도 읽지 못한 상태면 `503 TEMPLATE_CATALOG_UNAVAILABLE`.

> 이전에는 슬러그 형식(`[a-z0-9][a-z0-9_-]{0,49}`)만 검증하고 값을 읽는 코드가 없었다. 존재하지 않는 템플릿도 `200` 으로 통과했고, 고른 템플릿은 조용히 무시된 채 백지 생성됐다.

ZIP 업로드 처리는 아직 없다(§14.1 참고).

### 6.2 저장소 연결/해제

새 저장소 생성:

```json
{
  "repositoryMode": "create",
  "repositoryName": "my-landing",
  "repositoryVisibility": "PRIVATE"
}
```

기존 저장소 연결:

```json
{
  "repositoryMode": "existing",
  "repositoryFullName": "owner/repository"
}
```

요청 규칙:

- `repositoryMode`: `create`(별칭 `create_new`·`new`) / `existing`(별칭 `import`·`import_existing`). 대소문자·하이픈 무시, 생략하면 `create`. 그 외 값은 `400`.
- `repositoryVisibility` 생략 시 `PRIVATE`. `existing`이면 GitHub 저장소의 실제 공개 범위를 따른다.
- `existing`의 `repositoryFullName`은 `owner/repo` 형식이어야 하고(아니면 `400`), 찾을 수 없거나 접근 권한이 없어도 `400`.
- 이미 저장소가 연결된 프로젝트에 다시 연결하면 `409`. `create`에서 같은 이름의 저장소가 이미 있어도 `409`.
- preview 브랜치 준비부터 저장·감사까지는 저장소 연결 승인(`REPOSITORY_BINDING`, §14.3)과 같은 공용 코어(`RepositoryProvisioningService.bindToProject`)를 탄다.

연결 처리 흐름:

1. 프로젝트 소유권 확인
2. 새 저장소 생성 또는 기존 저장소 접근 확인
3. `preview` 브랜치 준비
4. source/deployment repository를 같은 저장소로 연결
5. binding과 health 상태 저장, `repositoryConnectedAt` 기록

해제(`DELETE /{projectId}/repository`):

- 프로젝트에서 GitHub 저장소 연결 정보만 제거하는 비파괴 작업이다. GitHub 저장소·workflow·Pages는 삭제하지 않는다.
- 배포 이력·도메인 연결 등 다른 도메인의 상태는 별도로 정리하지 않는다(자연 단절).
- 해제 후 `POST /repository`로 동일하거나 다른 저장소를 다시 연결할 수 있다.
- 동시 쓰기 lost-update(webhook head-sync와의 경합)는 Issue #45에서 해소했다. `Project`에 버전 컬럼(V26) + `@Version` 낙관적 잠금이 적용되어, 경합 시 `409`를 반환한다(`connection.md` §5.4 참고).

### 6.3 Repository 설정 조회

`GET /{projectId}/settings/repository`

- 연결된 저장소 정보와 `defaultBranch`를 반환한다. 저장소가 연결되지 않은 프로젝트도 `200`과 `connected: false`로 응답한다.
- `defaultBranch`는 매 요청마다 GitHub에서 실시간 조회한다(GitHub 왕복 지연 p95 약 500ms 추가). 조회 실패 시 `null`로 degrade한다.

### 6.4 프로젝트 삭제

Query `deleteMode`:

- 생략 또는 `PROJECT_ONLY`: 프로젝트 soft delete, 대화를 휴지통으로 이동
- `PROJECT_AND_REPOSITORY`: GitHub 저장소 삭제 후 대화와 프로젝트 데이터 제거
- 별칭: `PROJECT`(=`PROJECT_ONLY`), `PROJECT_WITH_REPOSITORY`(=`PROJECT_AND_REPOSITORY`). 그 외 값은 `400`. 저장소가 연결되지 않은 프로젝트에 `PROJECT_AND_REPOSITORY`를 쓰면 `409`.
- 삭제가 커밋된 뒤 트랜잭션 밖에서 best-effort로 정리한다: 선택된 클라우드 연결이 있으면 프론트 S3 정적 사이트 버킷을 지우고, 프론트 S3 HTTPS 도메인(CloudFront+ACM, Cloudflare 레코드)의 정리를 큐에 넣는다. 정리가 실패해도 삭제는 되돌리지 않는다.

### 6.5 Overview 통합 데이터

- `currentUrl`: 연결된(connected) DomainBinding을 `https://{hostname}`으로 우선하고, 없으면 최신 LIVE 배포 URL, 그것도 없으면 프로젝트에 저장된 URL
- `deployStatus`: 가장 최근 Deployment 상태
- `currentVersion`: 가장 최근 LIVE Deployment 버전
- `repositoryVersion`: GitHub webhook(`push`)으로 동기화된 최신 `vN` 저장소 태그
- `latestCommit`: GitHub 최신 커밋과 상대시간
- `repositoryHealth`: 연결 저장소 health
- `domainSummary`: 현재 우선 DomainBinding의 hostname, URL, 유형, 상태
- `cloudSummary`: 프로젝트에 선택된 CloudConnection과 실제 검증 상태
- `operationActions`: 배포, 도메인, cloud, AI Workspace, 설정, 제거 조치

트래픽/분석 기능은 PRD 현재 범위에서 제외되어 `trafficSummary`를 응답하지 않는다.

`recentChanges`는 2026-08-16 응답에서 제거됐다(읽는 소비자가 없었다). 최근 이벤트는 `GET /{projectId}/activity-logs`로 본다.

### 6.6 Infrastructure 설정

- 프로젝트당 하나의 CloudConnection만 선택할 수 있다.
- 실제 검증 상태가 `CONNECTED`인 connection만 선택 가능하다.
- 여기서 고른 연결이 실제 AWS 실행의 자격이 된다: EC2 백엔드 서버(`/projects/{id}/servers`), DB 프로비저닝(`/projects/{id}/databases`), S3 프론트 배포가 모두 이 선택을 읽는다(§19). 선택이 없으면 서버·DB는 `404`, S3 배포는 `400`이고, 선택했어도 CONNECTED가 아니면 `409`다. GCP 실행 경로는 없다.

### 6.7 Infrastructure 설정 저장(Configuration)

```json
{
  "deploymentArchitecture": "CONTAINER",
  "computeTier": "SMALL",
  "storageType": "OBJECT_STORAGE",
  "networkAccess": "PUBLIC"
}
```

필드는 4개 provider-중립 enum이다:

- `deploymentArchitecture`: `SERVER` | `CONTAINER` | `SERVERLESS`
- `computeTier`: `MICRO` | `SMALL` | `MEDIUM` | `LARGE`(구체적 인스턴스 타입 매핑은 아직 없음, EPIC 15 Cloud-Ops에서 결정 예정)
- `storageType`: `NONE` | `OBJECT_STORAGE`
- `networkAccess`: `PUBLIC` | `PRIVATE`

오토스케일링/로드밸런서/DB 연결 설정(BI-125~127)은 이 API 범위에서 명시적으로 제외되어 있다(`BACKLOG_STATUS.md` Removed 참고).

현재 동작:

- `GET`은 `CONNECTED` CloudConnection이 선택되어 있지 않은 프로젝트도 `200` + `configurable: false`로 응답한다.
- `PUT`은 `CONNECTED` 연결이 없으면 `409`.
- 프로젝트 Chat 설정의 `infraApprovalRequired`(기본 `true`)가 켜져 있으면 저장 요청이 즉시 반영되지 않고 `INFRA_OPERATION` Approval을 생성한다. 응답의 `settings`는 기존 값을 유지하고, 생성된 대기 건이 `pendingChange`로 함께 반환된다. `infraApprovalRequired`가 꺼져 있으면 즉시 적용된다.
- 현재 적용된 값과 완전히 동일한 요청은 이력·승인을 만들지 않고 현재 상태를 그대로 반환한다(no-op).
- 프로젝트당 `PENDING_APPROVAL` 상태 변경은 1건만 허용한다. 이미 대기 중인 변경이 있으면 `409`(먼저 승인/거절로 처리해야 함).
- 변경 이력(`/configuration/history`)은 `limit` 기본 50/최대 200이며, `APPLIED`/`PENDING_APPROVAL`/`REJECTED`를 모두 포함한 최신순 감사 이력을 반환한다.

Approval 승인/거절과의 연결(§14.3 참고):

- 이 승인은 Agent task 없이 API 요청에서 직접 생성되는 **standalone 승인**이다(`approval.taskId = null`).
- 승인/거절 시 `InfrastructureChangeApprovalHandler`가 호출되어 승인이면 설정을 실제로 적용하고, 거절이면 대기 변경을 폐기한다. 이 처리는 `ApprovalCommandService.approve/reject`의 기존 트랜잭션 안에서 실행되므로, 적용 실패 시 승인 상태 변경도 함께 롤백된다.
- 승인 결정에는 동시 요청 경합을 막기 위한 비관적 잠금(`@Lock(PESSIMISTIC_WRITE)`)이 걸려 있다.

운영 한계:

- 이 4개 enum 설정은 "적용하고 싶은 desired state"를 저장할 뿐이고, 어떤 프로비저닝 경로도 읽지 않는다. 실제 AWS 리소스(EC2·RDS·S3)는 별도 API(§19, §10.1)가 만들고, 그쪽은 이 설정이 아니라 §6.6의 연결 선택과 요청 본문을 쓴다. Cloud Ops Agent(§9.4)의 `STATUS_CHECK` 응답도 매번 "저장된 인프라 설정의 실 클라우드 리소스는 아직 프로비저닝되지 않았습니다"를 붙인다.

### 6.8 Infrastructure 비용 추정 + 예산 (Cost & Budget)

`GET /{projectId}/settings/cost-budget` 응답 예:

```json
{
  "projectId": 12,
  "costAvailable": true,
  "provider": "AWS",
  "currency": "USD",
  "estimatedMonthlyCost": 42.50,
  "resourceCosts": [
    { "resourceType": "COMPUTE", "description": "SMALL container", "monthlyCost": 30.00 },
    { "resourceType": "OBJECT_STORAGE", "description": "Object storage", "monthlyCost": 12.50 }
  ],
  "assumptions": ["..."],
  "priceTableVersion": "2026-07",
  "budget": { "monthlyBudgetAmount": 50.00, "currency": "USD", "updatedAt": "2026-07-18T00:00:00" },
  "budgetStatus": "WITHIN_BUDGET",
  "budgetUsagePercent": 85.0
}
```

`PUT /{projectId}/settings/cost-budget`:

```json
{ "monthlyBudgetAmount": 50.00, "currency": "USD" }
```

현재 동작:

- 비용 추정은 저장하지 않고 **매 요청마다 온더플라이로 계산**한다. 계산은 `InfrastructureCostEstimator`의 코드 상수 정적 가격표를 사용하며, 외부 클라우드 API를 호출하지 않는다 — 응답은 가정 기반 추정치이며 실시간 요금이 아니다(`priceTableVersion`으로 가격표 버전 식별).
- 인프라가 구성되지 않았거나 `CONNECTED` 클라우드 연결이 선택되지 않은 프로젝트도 `200`으로 응답하며 `costAvailable=false`와 함께 추정 필드는 `null`/빈 배열로 표시한다. `assumptions`/`priceTableVersion`은 `costAvailable`과 무관하게 항상 채워진다(가격표 자체에 대한 설명이므로).
- 통화는 `USD`로 고정된다.
- `monthlyBudgetAmount`는 필수이며 0.01 이상, 정수부 10자리·소수 둘째 자리까지. `currency`는 생략하거나 `USD`만 받고 그 외는 `400`.
- `budgetStatus`는 4가지: `NO_BUDGET`(예산 미설정) · `NOT_EVALUABLE`(예산은 있으나 비용 추정 불가) · `OVER_BUDGET` · `WITHIN_BUDGET`(추정 == 예산이면 `WITHIN_BUDGET`, 엄격한 `>` 비교로 판정).
- `PUT`은 upsert(멱등)이다. 인프라가 아직 구성되지 않은 프로젝트도 예산을 먼저 설정할 수 있다(§6.7의 CONNECTED 요구사항과 달리 이 API는 그런 전제조건이 없다). 저장 직후 재계산된 결과를 `GET`과 동일한 shape로 즉시 반환한다.
- `DELETE`는 예산이 없는 상태에서 호출해도 `204`(멱등).

현재 한계:

- 리소스별 세부 비용은 티어→구체 인스턴스 가격 매핑이 없는 정적 가정치다(§6.7의 `computeTier` 참고).
- 예산 초과 시 알림(이메일/슬랙 등) 발송 채널은 없다. `budgetStatus` 필드 계산까지만 제공한다.
- 오토스케일링/불필요 리소스 정리에 따른 비용 최적화(BI-148~151)는 범위 밖이다.

### 6.9 Chat 승인 정책 (Settings)

`GET/PATCH /{projectId}/settings/chat` 응답/요청 예:

```json
{
  "projectId": 12,
  "changeApprovalRequired": true,
  "deploymentApprovalRequired": true,
  "domainApprovalRequired": true,
  "infraApprovalRequired": true,
  "resultApprovalRequired": true
}
```

필드는 5개이며, 각각 어느 Agent 실행 단계를 승인 게이트로 막을지 결정한다:

| 필드 | 대상 |
|---|---|
| `changeApprovalRequired` | CODE(코드 변경) 실행 전 |
| `deploymentApprovalRequired` | DEPLOY(배포) 실행 전 |
| `domainApprovalRequired` | DOMAIN_BIND(도메인 연결/해제) 실행 전 — 승인 유형 `DOMAIN_BINDING`·`DOMAIN_UNBIND` 둘 다 |
| `infraApprovalRequired` | INFRA_OPERATE 중 영향 있는 작업(인프라 설정 변경, Cloud Ops `RESTART`) 실행 전 |
| `resultApprovalRequired` | CODE 실행 **후**, preview→main 반영(RESULT 승인, §9.5) 전 |

- `changeApprovalRequired`~`infraApprovalRequired` 4개는 `PATCH`에서 전체 문서 `PUT`과 동일하게 필수다(부분 수정 불가, 4개 모두 매번 지정).
- `resultApprovalRequired`만 예외적으로 nullable이다 — `null`이면 현재 값을 유지한다(이 필드를 모르는 구버전 FE 요청도 그대로 동작하도록 하는 하위호환 설계).
- 모든 정책은 기본값 `true`(가장 안전한 fail-safe)다.
- `REPOSITORY_BINDING`·`DATABASE_PROVISION`·`SERVER_PROVISION` 세 유형은 대응 정책 필드가 없고 **항상 승인을 요구한다**. 저장소 연결은 끄면 작업물이 프리뷰 만료와 함께 사라지고, 나머지 둘은 사용자 AWS 계정에 과금되기 때문이다(§14.3).

---

## 7. Environment API

Base path: `/api/v1/projects/{projectId}/environment-variables`

| Method | Path | 기능 |
|---|---|---|
| GET | `/api/v1/projects/{projectId}/environment-variables` | 환경변수 목록 조회(`scope` 쿼리로 필터, scope asc → key asc 정렬, `limit` 기본 200·최대 500) |
| POST | `/api/v1/projects/{projectId}/environment-variables` | 환경변수 생성 |
| PATCH | `/api/v1/projects/{projectId}/environment-variables/{variableId}` | 환경변수 값/secret 여부 수정 |
| DELETE | `/api/v1/projects/{projectId}/environment-variables/{variableId}` | 환경변수 삭제 |
| GET | `/api/v1/projects/{projectId}/environment-variables/history` | 변경 이력 조회(`limit` 기본 50, 최대 200) |

### 7.1 생성

```json
{
  "scope": "PREVIEW",
  "key": "API_BASE_URL",
  "value": "https://api.example.com",
  "secret": false
}
```

- `scope`는 `PREVIEW` 또는 `PRODUCTION`만 허용한다. `COMMON`은 존재하지 않는다 — 두 환경 모두 필요하면 두 번 생성해야 한다(설계상 의도적 결정).
- 동일한 (프로젝트, scope, key) 조합이 이미 있으면 `409`.
- `secret=true`로 생성해도 이 응답의 `value`는 `null`이다.
- `key`는 `^[A-Za-z_][A-Za-z0-9_]{0,127}$`(앞뒤 공백 제거 후, 숫자로 시작 불가, 최대 128자), `value`는 최대 4096자이며 빈 문자열을 허용하고 NUL 문자는 `400`. 지원하지 않는 `scope`도 `400`.

### 7.2 수정

```json
{
  "value": "https://api2.example.com",
  "secret": true
}
```

- `key`/`scope`는 불변이다(이름을 바꾸려면 삭제 후 재생성).
- `secret`은 `true → false`로 되돌릴 수 없다(`400`).
- `value`·`secret` 둘 다 생략할 수 있다(null이면 기존 값 유지). 빈 값으로 바꾸려면 `""`를 명시한다.

목록 상한: 상한에 걸리면 응답 헤더 `X-Qeploy-Next-Cursor`가 붙는다. 이 목록은 (scope, key) 정렬이라 커서로 이어 받는 경로는 아직 없고, 헤더는 "상한에 닿았다"는 신호일 뿐이다. 본문은 예전과 같은 JSON 배열이다.

### 7.3 Secret 마스킹 규칙

- 값은 `secret` 여부와 무관하게 항상 AES-256-GCM으로 전체 암호화되어 저장된다.
- 응답에서 `secret=true`인 변수의 `value`는 목록/생성/수정 어디에서도 `null`이다. `secret=false`인 변수는 복호화된 평문이 반환된다.
- 변경 이력(`/history`)에는 값 자체를 저장하지 않는다. `action`(`CREATED`/`UPDATED`/`DELETED`)과 `valueChanged`(불리언)만 기록해, 이력에서 secret 값을 유추할 수 있는 경로를 차단한다.

### 7.4 현재 한계

- `EnvironmentValueResolver`는 애플리케이션 내부 port로만 존재하며 HTTP로 노출되지 않는다.
- 주입은 두 경로다. 서버형 프리뷰 컨테이너(`NODE_SERVER`/`JAVA_FULLSTACK`, `PreviewRuntimeLauncher`)에는 `PREVIEW` 스코프만, EC2 백엔드 배포(`BackendDeployRunner`)에는 `PRODUCTION` 스코프만 들어간다(#415, 그 전에는 배포에 전 스코프가 섞여 들어갔다). 둘 다 DB 자동값·포트를 먼저 깔고 사용자 값으로 덮는다. 정적(`STATIC`) 프리뷰와 GitHub Pages workflow·S3 정적 배포에는 주입하지 않는다(`state.md` §3.0).

---

## 8. Chat API

| Method | Path | 기능 |
|---|---|---|
| GET | `/api/v1/projects/{projectId}/conversations` | 프로젝트 대화 목록 |
| POST | `/api/v1/projects/{projectId}/conversations` | 빈 대화 생성 |
| GET | `/api/v1/conversations/{conversationId}` | 대화 상세 |
| DELETE | `/api/v1/conversations/{conversationId}` | 대화를 휴지통으로 이동 |
| GET | `/api/v1/trash/conversations` | 계정 휴지통 대화 목록 |
| POST | `/api/v1/trash/conversations/{conversationId}/restore` | 대화 복구 |
| DELETE | `/api/v1/trash/conversations/{conversationId}` | 휴지통 대화 즉시 영구 삭제 |
| GET | `/api/v1/conversations/{conversationId}/messages` | 메시지 목록(생성순, `limit`·`after` 커서) |
| POST | `/api/v1/conversations/{conversationId}/messages` | USER 메시지 저장 후 Agent task를 비동기 제출(`201`, `taskId` 즉시 반환) |

메시지 생성:

```json
{
  "content": "헤더 크기를 줄여줘",
  "aiProvider": "CLAUDE_CODE"
}
```

> **⚠️ `aiProvider` 는 필수다** (Issue #364 / PR #365). 생략하면 400 이다.
>
> 예전에는 생략하면 배포에 설정된 **서버 키**로 돌았다. 그 경로가 없어졌다 — 로그인한 누구나 운영자 계정에 과금할 수 있었고, 사용량 상한도 사용자별 할당도 없었기 때문이다. FE 가 화면에서 서버 키 제공자를 걷어냈어도 서버는 여전히 받았고, 브라우저 JWT 만 있으면 `curl` 로 그대로 부를 수 있었다.
>
> 고를 수 있는 값은 **사용자가 키를 등록한 제공자뿐**이고, 목록은 `GET /api/v1/agent/ai-providers`(§9)가 그 기준으로 내려준다. 키가 없는 제공자를 지정하면 `AI_CREDENTIAL_NOT_REGISTERED`(400) 다 — 키 등록 화면으로 유도한다.

현재 동작:

- 메시지 생성은 **비동기**다. `POST`는 PENDING Agent task를 먼저 열고, USER 메시지를 그 `taskId`와 함께 저장한 뒤 곧바로 `201`을 돌려준다. 계획 수립(Decision)은 트랜잭션 커밋 뒤 백그라운드에서 돌고, 끝나면 task가 `QUEUED` 또는 `WAITING_APPROVAL`로 확정된다. 실패하면(서버 혼잡 포함) task가 `FAILED`로 닫힌다. FE는 받은 `taskId`로 바로 `GET /api/v1/agent/tasks/{taskId}/events/stream`(SSE)을 연다.
- Decision에는 이 대화의 **사용자 발화만** 넘긴다(우리가 쓴 운영 안내 문장을 모델이 흉내 내 JSON을 못 내던 문제 때문). 최근 20턴·24,000자 윈도우가 적용된다.
- 승인 대기, 질문, 결과, 오류는 ASSISTANT 메시지로 저장되고, 각 줄에 `kind`가 붙는다(아래).
- 생성된 task는 conversation/project/owner 문맥을 보관한다.
- 첫 사용자 메시지를 기반으로 대화 제목을 자동 생성하고 80자로 제한한다(`Conversation.MAX_AUTO_TITLE_LENGTH`).
- `MessageResponse.taskId`(nullable): `POST` 응답에서는 항상 값이 있다. `GET messages`에서도 그 줄을 만든(또는 그 발화가 낳은) task의 ID가 채워진다(V59). 태스크와 무관한 줄과 V59 이전에 쌓인 줄만 `null`이다.
- `MessageResponse.kind`(nullable, V58): 어시스턴트 줄의 종류. 화면이 본문 문자열로 의미를 추론하지 않도록 서버가 붙인다. `AGENT_RESULT` · `TASK_PROGRESS` · `APPROVAL_REQUESTED` · `INPUT_REQUIRED` · `CLARIFICATION_ANSWER` · `TASK_FAILED` · `TASK_CANCELLED` · `TASK_CLOSED`(#461: 작업은 `DONE`인데 시스템이 마무리함. 예: 저장소 연결을 묻는 동안 프리뷰가 정리돼 연결하지 못하고 끝남. 취소처럼 그리지 말 것). 사용자 메시지와 도입 이전 줄은 `null`이다. **모르는 값이 오면 평범한 어시스턴트 줄로 그린다** — 종류는 늘어난다.
- `GET messages`는 생성순(오래된 것부터)이고 `limit` 기본 500·최대 1000이다. 더 남아 있으면 응답 헤더 `X-Qeploy-Next-Cursor`가 붙으며, 그 값을 `after`로 넘기면 그 메시지보다 뒤의 것을 받는다. 헤더가 없으면 마지막 페이지다. 숫자가 아닌 커서는 `400`.
- V66(#405): #391 이전 승인 메시지에 박혀 있던 죽은 `- preview: https://…` 줄을 이력에서 지웠다(그 줄만 지우고 주변 줄은 유지).

휴지통/삭제 정책 (코드 실측, `ChatTrashPolicy`/`ChatTrashCleanupScheduler`):

- 보관 기간은 7일이다(`ChatTrashPolicy.RETENTION_DAYS = 7`). PRD와 일치한다.
- 휴지통 응답에 만료 시각과 올림 기준 남은 보관일을 제공한다.
- `DELETE /api/v1/trash/conversations/{conversationId}`로 즉시 영구 삭제할 수 있다.
- `ChatTrashCleanupScheduler`가 기본 1시간 주기(`qeploy.chat.trash-cleanup-interval-ms`, 기본값 3600000ms)로 만료된 대화를 자동 영구 삭제한다.
- 정확히 7일이 지난 대화는 조회/복구 대상에서 제외되고 자동 삭제 대상으로 처리된다.

현재 한계:

- 삭제된 원본 프로젝트를 대신할 동일 저장소 프로젝트가 없으면 복구할 수 없다.
- 프로젝트별 Chat 지침(커스텀 system prompt)이나 응답 상세도 조절은 아직 없다(§9.3 참고).

---

## 9. Agent API

Base path: `/api/v1/agent`

| Method | Path | 기능 |
|---|---|---|
| POST | `/decision` | 자연어를 분류하고 비동기 Agent task 시작 |
| GET | `/ai-providers` | 지정 가능한 AI 제공자·모델 목록(§9.6) |
| GET | `/tasks/{taskId}` | task 상태 폴링 |
| GET | `/conversations/{conversationId}/active-task` | 대화의 진행/대기 중(비-terminal) 태스크 `{taskId, status}`. 없으면 `204`. 새로고침 후 되묻기 폼·진행 상태 복구용 |
| POST | `/tasks/{taskId}/input` | `WAITING_INPUT` task에 값 제출. `204`, 대기 상태가 아니면 `409`, 없거나 대기 만료면 `404` |
| DELETE | `/tasks/{taskId}` | 소유한 대기/실행 task 취소(`204`). 취소할 수 없거나 내 것이 아니면 `404` |
| DELETE | `/session` | 현재 사용자의 PreviewSession과 Docker 컨테이너 종료(`204`). 닫을 세션이 없으면 `404` |

### 9.1 요청 제출

```json
{
  "content": "React 랜딩페이지를 만들고 배포해줘",
  "aiProvider": "ANTHROPIC",
  "projectId": 12,
  "conversationId": 34
}
```

`projectId`가 없으면 신규 작업, 있으면 기존 프로젝트 수정 문맥으로 분류한다. `conversationId`가 있으면 소유 대화를 확인하고 대화의 프로젝트 문맥을 task에 연결한다.

`aiProvider`는 **필수이며, 호출자 본인이 키를 등록한 제공자여야 한다**(#364). 값은 벤더(`ANTHROPIC`·`OPENAI`·`GLM`) 또는 코딩 에이전트(`CLAUDE_CODE`·`CODEX`)다. 서버 키로 도는 기본 경로는 없어졌고, 키가 없으면 실행 전에 `400 AI_CREDENTIAL_NOT_REGISTERED`로 떨어진다. 코딩 에이전트는 대응 벤더 키로 인증한다(`CLAUDE_CODE`→Anthropic, `CODEX`→OpenAI) — 계획·채팅은 그 벤더의 chat-completions로, 코드 생성만 벤더 CLI로 돈다.

`model`(nullable)은 제공자 기본 모델 대신 쓸 모델 ID다. 서버가 허용한 모델이 아니면 `400`. `thinking`(nullable, `OFF`|`LOW`|`MEDIUM`|`HIGH`, 기본 `OFF`)을 지원하지 않는 모델에 켜면 무시하지 않고 `400`이다. 코딩 에이전트(`CLAUDE_CODE`·`CODEX`)는 둘 다 CLI가 정하므로, 값을 보내면 `400`이다(`thinking: OFF`는 허용).

응답 주요 필드:

- `steps`: `{ agentType, parameters }` 목록. `agentType`은 `CLARIFY`, `CHAT`, `CODE`, `DEPLOY`, `DOMAIN_BIND`, `INFRA_OPERATE`, `RUNTIME_SETUP`, `BACKEND_DEPLOY`. 스텝의 `instruction`·`userSummary`는 사용자가 쓴 언어로 만든다(#469 — 프롬프트 지시라 LLM 출력이 보장하지는 않는다)
- `reasoning`
- `aiProvider`
- `taskId`
- `status`: `WAITING_APPROVAL` 또는 `QUEUED`
- `approvalIds`: 승인이 필요한 경우 생성된 approval ID 목록

### 9.2 task 상태

상태:

- `PENDING`
- `WAITING_APPROVAL`
- `QUEUED`
- `RETRY_WAIT`
- `RUNNING`
- `WAITING_INPUT`
- `WAITING_RESULT_APPROVAL` — plan의 마지막 CODE step이 끝난 뒤 결과 승인을 기다리는 상태(§9.5 참고). worker가 claim할 수 없는 상태이며, 오직 사람의 승인/거절/취소로만 벗어난다
- `DONE`
- `FAILED`
- `CANCELLED`

결과 필드:

- `previewCreated` (#392): 이 태스크에 프리뷰가 **만들어졌는지**. 위 컬럼이 비어 있지 않으면 `true`(공백 문자열은 `false`로 본다). `previewUrl` 의 유무만 필요한 소비자가 주소까지 들고 가지 않도록 분리했다.
  이름이 `previewReady`가 아닌 이유: 근거 컬럼이 회수 시 지워지지 않으므로 **`true`가 "지금 볼 수 있다"를 뜻하지 않는다.** 지금 상태는 `GET /projects/{id}/preview-session` 의 `status` 가 말한다
  > **`previewUrl` 은 이 응답에서 제거됐다 (#392).** CODE 스텝이 끝난 시점 주소의 스냅샷이라 회전 뒤 404 였고, 컬럼이 회수 시에도 지워지지 않아 값의 존재가 생존을 뜻하지도 않았다. 같은 이유로 `GET /projects/{id}/preview-session` 의 `previewUrl` 도 제거했다(그쪽은 최신이었지만 접근 쿠키 없이는 401 이었다). **열 수 있는 주소는 `POST /preview-sessions/{id}/access` 응답의 것 하나뿐이다.**
- `summary`
- `error`
- `question`
- `failureLog`
- `suggestedFix`
- `attempt`
- `maxAttempts`
- `retryable`: `POST /tasks/{taskId}/retry` 호출이 실제로 성공할지 여부(Issue #57). `true`가 되려면 `pendingApprovalId`가 `null`(대기 중인 승인 없음)**이면서** `attempt < maxAttempts`여야 한다. status가 FAILED가 아니면 항상 false다. 판단 기준("PENDING 승인이 하나라도 있으면 막는다")은 응답과 /retry가 같지만, 응답은 잠금 없는 스냅샷이고 /retry는 태스크 행 잠금 아래에서 다시 읽으므로, 승인이 막 생긴 순간에는 응답이 잠깐 true로 읽힐 수 있다 — 실제 판정은 /retry가 한다
- `pendingApprovalId` (nullable): 이 task에 걸린 `PENDING` 승인의 ID(예: `BuildFailureRecoveryService`가 만드는 "자동 수정 및 재build" 승인). 값이 있으면 `retryable`은 항상 `false`이며, `/retry`를 호출해도 `409`로 거부된다 — 먼저 `POST /approvals/{pendingApprovalId}/approve`로 그 승인을 처리해야 한다(승인 자체가 재실행을 트리거함). `null`이면 승인 대기가 없다는 뜻이며, 그 경우에만 `attempt < maxAttempts` 여부로 `retryable`이 결정된다
- `clarification` (nullable): 구조화 되묻기(CLARIFY). `WAITING_INPUT`이고 선택형 질문일 때만 채워진다 — `{question, inputType(TEXT|SINGLE_SELECT|MULTI_SELECT), options[{value,label,recommended}], allowOther, actionType}`. `actionType=CONNECT_CLOUD`이면 질문이 아니라 클라우드 연결이 필요하다는 조치 안내다(연결 가이드는 `GET /cloud-connections/requirements`). 답은 사람이 읽는 문자열 하나로 `/input`에 보낸다. null이면 `question`만 보여주는 텍스트 입력
- `answeredClarification` (nullable): 이미 답한 되묻기. `{question, inputType, options, allowOther, answer}`. 되묻기를 거치지 않은 태스크에서는 null

추가 task API:

| Method | Path | 기능 |
|---|---|---|
| GET | `/api/v1/agent/tasks/{taskId}/events?afterEventId={id}` | event ID 기반 polling(`afterEventId` 기본 0, 시간순). `type`은 생명주기(CREATED/QUEUED/STARTED/COMPLETED/FAILED/CANCELLED/WAITING_APPROVAL/WAITING_INPUT/WAITING_RESULT_APPROVAL/REPLANNED/INPUT_RECEIVED/RETRY_QUEUED)와 스텝(STEP_STARTED/STEP_COMPLETED). 스텝 이벤트만 `stepIndex`·`stepTotal`·`agentType`을 싣는다. 모르는 type은 무시 |
| GET | `/api/v1/agent/tasks/{taskId}/events/stream` | 영속 이벤트 SSE. 여는 즉시 주석 프레임 `:hb` 를 쓰고 유휴 시 15초마다 반복한다(#370 — 이것이 없으면 보낼 이벤트가 없는 재연결이 응답 헤더를 못 내보내 프록시에 끊긴다). `data:` 가 없으므로 이벤트가 아니다 |
| POST | `/api/v1/agent/tasks/{taskId}/retry` | 실패 task 수동 재시도. 성공 `202`. FAILED가 아니거나, attempt가 소진됐거나, PENDING 승인이 남았거나(`pendingApprovalId` non-null — 먼저 그 승인을 처리해야 함), 없는·남의 task면 모두 `409`(404가 아니다). `retryable:true`일 때만 호출할 것 |

### 9.3 실제 Agent 동작

- Decision Agent: LLM으로 다중 intent를 분류
- Code Agent: Docker `node:20-alpine` 프리뷰 컨테이너에서 파일 생성/수정·빌드·프리뷰 실행. 런타임은 `STATIC`/`NODE_SERVER`/`JAVA_FULLSTACK`(§14.2 런타임 설정). `aiProvider`가 `CLAUDE_CODE`·`CODEX`면 코드 생성만 벤더 CLI가 별도 컨테이너에서 사용자 키로 수행하고, 결과를 프리뷰 컨테이너로 되옮긴다(`node_modules`·`.git`은 옮기지 않음). 결과 요약은 요청과 같은 언어로 쓰게 지시한다(#469)
- Deploy Agent: 승인 완료 후 요청 단위 commit을 `preview` 브랜치에 non-force push하고 배포를 요청한다. step 파라미터 `hostingType`(`GITHUB_PAGES`|`S3`|`EC2`)이 비어 있으면 프로젝트 현재 설정을 쓰고, 모르는 값이면 기본값으로 떨어뜨리지 않고 실패한다
- Domain Agent: 관리형 서브도메인 또는 커스텀 도메인 연결
- Chat Agent(`ChatAgentService`): Decision Agent가 CODE/DEPLOY/DOMAIN_BIND로 분류하지 못한 일반 대화·질문에 LlmRouter 경유로 실제 LLM 응답을 1회 생성해 반환한다(Docker/GitHub 등 인프라를 건드리지 않는다). 프로젝트별 커스텀 지침은 아직 없고 고정 system prompt만 사용한다.
- Cloud Ops Agent(`InfraOpsAgentService`, `AgentType.INFRA_OPERATE`): 서버/서비스 운영을 묻는 자연어 요청을 처리한다. §9.4 참고.
- Clarify(`CLARIFY`): 핵심 스펙(백엔드 스택 등)이 모호하면 이 스텝 하나만 내고 `WAITING_INPUT`으로 구조화 질문을 낸다(§9.2 `clarification`). 답을 받으면 다시 판단해 계획을 만든다
- Runtime Setup(`RUNTIME_SETUP`, `RuntimeSetupAgentService`): CODE 앞에서 프리뷰 런타임 설정(`runtimeType`·`dbEngine`·`startCommand`)을 부분 갱신해, 이어지는 프리뷰를 백엔드 런타임으로 띄운다
- Backend Deploy(`BACKEND_DEPLOY`, `BackendDeployAgentService`): 사용자 AWS 계정에 RDS(선택)+EC2 배포를 **요청**한다. 직접 만들지 않고 `DATABASE_PROVISION`·`SERVER_PROVISION` 승인을 만들며, 승인 후 워커가 생성한다(§19)

운영 한계:

- task, plan, 현재 step, 입력, retry, event는 DB에 영속화된다.
- worker는 DB queue claim과 lease heartbeat로 scale-out 시 중복 실행을 제한한다.
- task에는 소유 사용자, 프로젝트, 대화 ID가 저장되며 조회/입력/취소 시 소유권을 확인한다.
- 실행 중 외부 Docker/GitHub 호출은 즉시 interrupt하지 않고 step 경계에서 취소한다.
- preview URL은 access token이 포함된 backend gateway URL이다.

### 9.4 Cloud Ops Agent (INFRA_OPERATE)

**신규 HTTP 엔드포인트는 없다.** `POST /agent/decision`(§9.1)이나 `POST /conversations/{conversationId}/messages`(§8)로 서버/서비스 운영을 묻는 자연어를 보내면, Decision Agent가 `INFRA_OPERATE` step으로 분류하고 `InfraOpsAgentService`가 실행한다.

지원 operation(화이트리스트, LLM은 이름만 제공하고 대상은 항상 서버가 재조회):

| operation | 기능 | 승인 필요 | 비고 |
|---|---|---:|---|
| `STATUS_CHECK` | 배포/Preview/클라우드 연결/인프라 설정 상태를 고정 템플릿으로 요약 | 아니오 | 각 항목 독립적으로 degrade, "실 클라우드 리소스는 아직 프로비저닝되지 않았습니다" 문구 항상 포함. 이 문구는 §6.7 인프라 설정에 대한 것이고, §19로 만든 EC2 서버·DB는 STATUS_CHECK가 조회하지 않는다(위 4항목만) |
| `LOG_VIEW` | ACTIVE preview 컨테이너 로그(tail 50줄/최대 2000자) | 아니오 | Preview 운영 API(§14.2, `GET /preview-sessions/{id}/logs`)보다 훨씬 축약된 chat 전용 뷰 |
| `FAILURE_ANALYSIS` | 최근 배포 실패 원인 분석 | 아니오 | §10.2의 `DeploymentFailureAnalysisService`를 그대로 재사용 |
| `RESTART` | ACTIVE preview 컨테이너 재시작 | 예(`INFRA_OPERATION`, 프로젝트 정책이 켜져 있으면) | 유일한 변경 작업, 대상은 항상 DB 재조회. 이 승인은 Agent task 기반(taskId 있음) — §6.7의 standalone 승인과 타입은 같지만 출처가 다름(§14.3 참고) |
| `RESOURCE_SCALING` / `AUTOSCALING_CHANGE` / `RESOURCE_CLEANUP` | (미지원) | 해당 없음 | 승인을 만들지 않고 즉시 감지·설명 후 거부 |

예:

```text
사용자: "지금 서버 상태 어때?"
→ Decision Agent가 INFRA_OPERATE/STATUS_CHECK로 분류
→ InfraOpsAgentService가 배포/Preview/클라우드연결/인프라설정을 조회해 고정 형식으로 응답
```

프롬프트 인젝션 방어: LLM은 `operation` 이름 문자열만 제공하며, 조작할 리소스(컨테이너 ID, 배포 이력 ID 등)를 직접 지정하는 경로가 없다. 서버가 항상 `(projectId, ownerUserId)`로 DB에서 대상을 다시 조회한다.

### 9.5 결과 승인 2단계 게이트 (RESULT, Issue #56)

**신규 HTTP 엔드포인트는 없다.** 기존 `GET /agent/tasks/{taskId}`(상태값 확장), `GET/POST /approvals/...`(승인 유형 확장), `GET /projects/{projectId}/changes`(상태값·병합 메타데이터 확장) 위에서 동작한다.

제품 모델: 사용자가 만든 결과물은 4단계를 거친다.

```text
[컨테이너/preview 서버] → [git preview 브랜치] → [git main] → [GitHub Pages 공개]
      "만들어진 것"           "기록된 것"        "제품으로 수용된 것"   "공개된 것"
                                        ↑                    ↑
                                  결과 승인(RESULT)      배포 승인/배포 요청
```

main에 merge되어도 라이브 사이트는 자동으로 바뀌지 않는다 — Pages는 별도 workflow 산출물을 서빙한다. **git 반영(RESULT 승인)과 실제 배포는 서로 다른 단계다.**

동작:

- plan의 **마지막 CODE step**이 끝난 직후 1회 평가된다. 발동 조건: 프로젝트가 GitHub 저장소에 BOUND, 그리고 프로젝트 Chat 설정의 `resultApprovalRequired`(기본 `true`)가 켜져 있음.
- 신규/미연결 프로젝트(아직 BOUND 아님)는 RESULT 게이트 대상이 아니다. 대신 같은 지점에서 **저장소 연결 승인(`REPOSITORY_BINDING`)** 이 열린다 — 이 태스크의 작업 결과 프리뷰가 있을 때만, 정책과 무관하게(`RepositoryBindingGate`). 두 게이트는 같은 `repositoryBindingStatus` 로 갈리므로 한 태스크에서 하나만 열린다. 흐름은 §13.11, 승인 유형은 §14.3 참고.
- 게이트가 발동하면: CODE 결과를 `preview` 브랜치에 push → task를 `WAITING_RESULT_APPROVAL`로 전환 → `ApprovalType.RESULT` Approval 생성 → "미리보기와 변경 내역을 확인해 주세요" assistant 메시지 발송.
- 여러 CODE step이 있는 plan(예: 실패 후 재시도)도 RESULT 승인은 1번만 열리며, 마지막 CODE step까지의 누적 결과 전체를 대상으로 한다.
- **승인**: `POST /approvals/{approvalId}/approve` → `preview`에 `main` 대비 새 커밋이 있으면 PR 생성+merge, 없으면(멱등 재시도) main HEAD SHA만 기록 → `Change`가 `MERGED`로 전환(`prNumber`/`mergeCommitSha`/`mergedAt` 기록) → task가 이어서 재개(남은 step 계속 또는 `DONE`).
- **거절**: `POST /approvals/{approvalId}/reject` → `Change`가 `REJECTED`로 전환(merge 없음) → task `CANCELLED`. 거절된 커밋은 `preview` 브랜치에서 지우지 않는다(**누적 시맨틱**) — 다음 작업은 그 위에 이어서 커밋되고, 다음 RESULT 승인은 그 시점 `preview` 브랜치 전체 상태를 main에 반영한다(거절분이 되돌려지지 않은 채 함께 반영될 수 있음).

`GET /projects/{projectId}/changes` 응답 상태값에 `MERGED`/`REJECTED`가 추가되었다(기존 `PREVIEW_READY`/`DEPLOYED`에 더해). `MERGED` 항목은 `approvalId`/`prNumber`/`mergeCommitSha`/`mergedAt`을 포함한다.

직접 Deployment API(`POST /projects/{projectId}/deployments`)의 자동 merge 동작 변경(§10.1과 연결):

- `resultApprovalRequired` 정책이 켜져 있으면, "한 번도 배포된 적 없고 RESULT 게이트도 거친 적 없는" 프로젝트에만 예외적으로 자동 merge를 허용한다(신규 프로젝트의 첫 배포까지 막지 않기 위함).
- 그 외에는 merge 권한이 RESULT 승인으로 완전히 이관된다 — 직접 배포 요청은 **main의 현재 상태만 공개**할 뿐 더 이상 `preview`를 자동으로 끌어오지 않는다.
- 정책이 꺼져 있으면 기존 동작(배포 시 자동 merge)이 그대로 유지된다(회귀 없음).

---

### 9.6 사용 가능한 AI 제공자 조회

`GET /api/v1/agent/ai-providers` — 이 사용자가 `aiProvider`로 지정할 수 있는 것과, 각각에 허용된 모델을 돌려준다.

```json
{ "providers": [
  { "provider": "ANTHROPIC", "defaultModel": "claude-opus-4-5-20251101",
    "models": ["claude-opus-4-5-20251101"], "thinkingModels": ["claude-opus-4-5-20251101"] },
  { "provider": "CLAUDE_CODE", "defaultModel": null, "models": [], "thinkingModels": [] }
] }
```

- **근거는 호출자가 등록한 크리덴셜이다**(#364). 예전에는 배포에 설정된 서버 키가 근거였다. 키를 하나도 등록하지 않았으면 **빈 배열**이다. 미인증 호출은 다른 인증 API와 같이 `401`이다.
- 벤더 키 하나를 등록하면 그 벤더와 **대응 코딩 에이전트가 함께** 나온다(`ANTHROPIC`→`CLAUDE_CODE`, `OPENAI`→`CODEX`). 키는 벤더 단위로 한 번만 등록하면 되기 때문이다. `GLM`은 코딩 에이전트 모드가 없어 벤더만 나온다.
- 코딩 에이전트는 `models: []`·`defaultModel: null`이다 — 코드 생성 단계의 모델을 벤더 CLI가 정하므로 고를 것이 없다. 빈 목록이 정직한 답이고, 플레이스홀더를 넣으면 아무것도 바꾸지 않는 선택지를 그리게 된다.
- **API 키는 어떤 필드로도 나가지 않는다.** 모델 카탈로그는 배포 설정(`qeploy.ai.*`)에서 오고 비밀이 아니다.

---

## 10. Deployment API

| Method | Path | 기능 |
|---|---|---|
| POST | `/api/v1/projects/{projectId}/deployments` | 프론트 배포 시작. 프로젝트 `frontendHostingType`(`GITHUB_PAGES` / `S3` / `EC2`)을 따른다 |
| GET | `/api/v1/deployments/{deploymentId}` | 배포 상태와 GitHub Actions 상태 조회 |
| GET | `/api/v1/projects/{projectId}/deployments` | 배포 이력 목록 |
| GET | `/api/v1/projects/{projectId}/versions` | 배포 이력을 버전별로 묶어 조회 |
| GET | `/api/v1/projects/{projectId}/deployment-candidates` | LIVE 버전 재배포 후보 조회 |
| GET | `/api/v1/deployments/{deploymentId}/logs` | GitHub Actions job/step/log 조회 |
| POST | `/api/v1/deployments/{deploymentId}/retry` | 실패(FAILED) 배포를 새 이력으로 재큐잉 |
| POST | `/api/v1/deployments/{deploymentId}/failure-analysis` | 실패 원인 분석 실행(멱등, 이미 있으면 재사용) |
| GET | `/api/v1/deployments/{deploymentId}/failure-analysis` | 저장된 실패 원인 분석 결과 조회(부작용 없음) |
| GET | `/api/v1/versions/{versionId}` | 버전 상세 조회 |

### 10.1 배포 요청

최신 버전:

```json
{
  "deployTargetType": "LATEST",
  "versionName": null
}
```

특정 tag:

```json
{
  "deployTargetType": "VERSION",
  "versionName": "v3"
}
```

프론트 호스팅 지정(선택):

```json
{
  "deployTargetType": "LATEST",
  "frontendHostingType": "S3"
}
```

- `frontendHostingType`: `GITHUB_PAGES` | `S3` | `EC2`. 지정하면 **프로젝트 설정 자체를 그 값으로 바꾸고** 이후 배포·재시도도 그것을 따른다. 생략하면 프로젝트의 현재 설정(기본 `GITHUB_PAGES`)을 쓴다.
- `GITHUB_PAGES`: 아래 `LATEST` 처리(workflow dispatch → `workflow_run` webhook) 그대로.
- `S3`: GitHub Actions 를 쓰지 않는다. worker 가 빌드 컨테이너에서 `npm ci|install` → `npm run build` 후 사용자 AWS 계정의 S3 정적 웹호스팅 버킷에 올리고, **worker 실행 안에서 동기로 `LIVE`** 까지 간다. `deployedUrl` 은 S3 website 엔드포인트(`http://...s3-website...amazonaws.com`, http)다. 버전·preview→main 병합은 Pages 와 같은 release 준비 단계를 재사용한다. 요청 시점에 프로젝트에 선택된 클라우드 연결이 없으면 `400`. 연결이 `CONNECTED` 가 아닌 것은 실행 단계에서 실패해 worker 재시도 경로로 간다.
- `EC2`: 배포 이력/worker 파이프라인을 타지 않고 **웹 전용 EC2 서버 프로비저닝(§19)으로 위임**한다. 과금 자원이라 항상 `SERVER_PROVISION` 승인을 거친다. 응답의 `deploymentId` 는 배포 이력 ID 가 아니라 **대기 서버 ID**, `status=PENDING`, `approvalIds` 에 승인 ID 가 담긴다. 진행 상황은 `GET /projects/{projectId}/servers`(`webOnly=true`)로 본다. 선택된 클라우드 연결이 없으면 `404`, `CONNECTED` 가 아니면 `409`.

응답(`DeployResponse`)의 `approvalIds` 는 EC2 일 때만 채워지고 Pages/S3 는 빈 배열이다.

`LATEST` 처리:

1. 요청 소유권과 repository binding 확인
2. `DeploymentHistory(PENDING)`와 correlation ID 저장 후 `202 Accepted`
3. DB worker가 lease를 획득
4. workflow 파일 구성 후 `preview`와 `main`의 차이 확인
5. 차이가 있으면 PR 생성/조회 후 merge
6. main HEAD의 기존 순차 tag 조회 또는 새 `vN` tag 생성
7. commit/PR/merge 메타데이터와 Pages URL 저장
8. correlation ID를 포함해 workflow dispatch
9. run ID와 workflow head SHA 저장
10. `workflow_run` webhook에서 정확한 이력을 `LIVE` 또는 `FAILED`로 반영

상태:

- `PENDING`: worker queue 대기 또는 retry 대기
- `IN_PROGRESS`: GitHub workflow 준비/실행 중
- `LIVE`: workflow 성공
- `FAILED`: 재시도 소진 또는 workflow 실패

실패 분류(`GET /deployments/{id}`·`GET /projects/{id}/deployments` 의 `errorCode`, 성공·진행 중이면 `null`):

- `WORKFLOW_FAILED`: GitHub Actions 가 실패로 끝남
- `RESULT_UNKNOWN`: 결과를 확인하지 못하고 포기함. **실패라는 뜻이 아니다**(사이트가 떠 있을 수 있다). 화면에서 실패로 단정하지 말 것
- `RETRY_EXHAUSTED`: 재시도 한도 소진
- 분류 도입 전에 실패한 옛 이력은 `errorCode=null` 이고 `errorMessage` 만 있다.

정확성:

- 새 배포 run은 correlation ID와 workflow head SHA로 polling한다.
- webhook은 workflow run ID 또는 correlation ID로 이력을 찾고 저장소/head SHA를 검증한다.
- webhook 처리는 delivery GUID로 멱등 처리하고 내부 worker가 실패를 재시도한다.
- 버전 응답은 저장된 commit SHA, title/description, merge 사용자/avatar, PR number를 반환한다.
- 배포 상태/이력/로그/버전/후보 조회는 인증 사용자의 프로젝트 소유권을 확인한다.

현재 한계 / 정책 연동(Issue #56 반영):

- `resultApprovalRequired` 정책이 켜져 있으면(기본값), "한 번도 배포된 적 없고 RESULT 게이트도 거친 적 없는" 프로젝트에서만 이 API가 preview→main을 자동 merge한다(신규 프로젝트 첫 배포 예외). 그 외 프로젝트는 이 API가 더 이상 preview를 끌어오지 않고 main의 현재 상태만 공개한다 — merge 권한은 RESULT 승인(§9.5)으로 이관되었다.
- `resultApprovalRequired` 정책이 꺼져 있으면 이 API는 Approval 없이 기존처럼 PR merge와 배포를 함께 실행한다(회귀 없음).
- 배포 취소 API와 동일 프로젝트 동시 배포 직렬화는 아직 없다.

### 10.2 실패 원인 분석과 재시도

`POST /deployments/{deploymentId}/failure-analysis`:

- FAILED 상태 배포만 대상이다(아니면 `409`).
- 이미 저장된 분석 결과가 있으면 다시 분석하지 않고 그대로 반환한다(멱등).
- 신규 분석은 GitHub Actions 로그 수집 + 룰 기반 분석이다. 소요 시간은 로그 수집에 달려 있다.
- 로그는 최대 12,000자만 발췌하고, 시크릿으로 보이는 패턴은 정규식으로 레닥션(`***REDACTED***`)한다.
- **LLM을 쓰지 않는다(#364).** 사용자가 `aiProvider`를 고르지 않는 내부 경로라 BYOK로 돌릴 수 없는데, 서버 키로 도는 경로를 남기지 않기로 했다. 응답의 `analysisSource`는 항상 `RULE_BASED`다 — 필드와 enum은 나중에 BYOK 요약을 붙일 자리로 남겨 뒀다.

`GET /deployments/{deploymentId}/failure-analysis`:

- 저장된 결과만 반환한다(부작용 없음, GitHub 재호출 없음). 분석을 실행한 적이 없으면 `404`.

`POST /deployments/{deploymentId}/retry`:

- FAILED 상태 배포만 대상이다(아니면 `409`). 요청 본문은 없다.
- `201`을 반환하며, 원본과 동일한 `deployTargetType`(VERSION이면 동일 버전)으로 새 `DeploymentHistory`를 생성한다.
- 새 이력은 `retriedFromHistoryId`로 원본 이력과 연결된다. 원본 이력은 되돌리지 않고 감사 목적으로 그대로 보존된다.
- 재시도는 직접 Deployment API와 동일하게 Approval 없이 즉시 큐잉된다(승인 미적용, 직접 배포와 대칭적인 설계).
- 프로젝트가 EC2 프론트 호스팅이면 재시도도 웹 전용 서버 재프로비저닝(§19)으로 위임되어 `SERVER_PROVISION` 승인을 거친다. 응답 `deploymentId` 는 대기 서버 ID 다. 원본 이력이 Pages/S3 였더라도 지금 설정이 EC2 면 이쪽으로 간다.

---

## 11. DomainBinding API

| Method | Path | 기능 |
|---|---|---|
| GET | `/api/v1/domain-search?keyword={label}` | 지원 중인 관리형 도메인 후보 조회 |
| GET | `/api/v1/domains/hosting-targets` | 이 서버가 도메인 연결을 실제로 지원하는 `hostingTarget` 목록(어댑터가 등록된 것만) |
| GET | `/api/v1/projects/{projectId}/domains` | 프로젝트 도메인 목록(최신순, `limit` 기본 200·최대 500. 더 있으면 `X-Qeploy-Next-Cursor` 헤더, `after`로 이어 받음. 숫자가 아닌 커서는 `400`) |
| POST | `/api/v1/projects/{projectId}/domains` | 도메인 연결 Agent task 제출 |
| GET | `/api/v1/domains/{domainId}` | 도메인 상태 조회 |
| GET | `/api/v1/domains/{domainId}/verification-guide` | DNS 레코드 가이드 |
| POST | `/api/v1/domains/{domainId}/verification-checks` | DNS, hosting, 인증서, HTTPS 재검증 |
| DELETE | `/api/v1/domains/{domainId}` | 도메인 연결 해제 Agent task 제출 |
| GET | `/api/v1/tls/allow?domain={hostname}` | **인증 불필요.** EC2 Caddy on-demand TLS 확인 게이트(`DomainTlsController`, §19.4) |

관리형 서브도메인:

```json
{
  "type": "MANAGED_SUBDOMAIN",
  "label": "cafe",
  "hostingTarget": "GITHUB_PAGES"
}
```

커스텀 도메인:

```json
{
  "type": "CUSTOM_DOMAIN",
  "hostname": "www.example.com",
  "verificationMethod": "CNAME",
  "hostingTarget": "GITHUB_PAGES"
}
```

`hostingTarget`(생략 시 `GITHUB_PAGES`):

| 값 | 대상 | DNS | HTTPS |
|---|---|---|---|
| `GITHUB_PAGES` | GitHub Pages 프론트 | CNAME | Pages 인증서 + HTTPS 강제 |
| `AWS` | 이 프로젝트의 RUNNING 백엔드 EC2 | A 레코드 → EIP | 인스턴스 Caddy on-demand TLS(`/tls/allow` 게이트) |
| `AWS_EC2_FRONTEND` | 웹 전용(독립 프론트) EC2 | A 레코드 → EIP | 위와 같음 |
| `AWS_S3_FRONTEND` | S3 정적 프론트 | CloudFront 도메인으로 CNAME | CloudFront + ACM(us-east-1), 비동기 수 분 |
| `GCP` | — | — | 어댑터가 없어 `400`("아직 지원되지 않습니다") |

- `AWS`/`AWS_EC2_FRONTEND`: RUNNING 서버가 없으면 `409`. 커스텀 도메인의 검증 방식 기본값은 `A` 다.
- `AWS_S3_FRONTEND`: 프로젝트 `frontendHostingType=S3` 가 아니면 `409`. ACM 인증서만 요청하고 `PROVISIONING` 으로 저장한 뒤 worker 가 검증 CNAME → CloudFront 배포 → 최종 CNAME → https 확인을 진행해 `CONNECTED` 로 올린다. 관리형은 worker 가 DNS 를 걸고, 커스텀은 사용자가 `verification-guide` 의 검증 CNAME·최종 CNAME 을 직접 넣는다.
- `type` 은 `MANAGED_SUBDOMAIN`/`managed_subdomain` 둘 다 받고, 응답은 소문자(`managed_subdomain`)로 나간다.

현재 동작:

- 연결/해제 요청은 `202 Accepted`와 `taskId`, 상태, approval ID 목록 반환
- Domain Approval 정책이 켜져 있으면 승인 완료 후 Agent가 실행
- 관리형: Cloudflare CNAME 생성, 선택 hosting adapter 설정
- 커스텀: 선택 hosting adapter 설정, DNS 가이드 제공, A/CNAME 확인
- GitHub Pages: custom domain, 인증서 상태/만료일, HTTPS 강제 적용 상태 조회
- GitHub Pages 인증서 활성화 후 HTTPS 강제 적용 자동 활성화
- 삭제: hosting adapter에서 custom domain 해제, 관리형이면 Cloudflare record도 삭제
- `AWS_S3_FRONTEND` 해제는 Cloudflare 레코드(최종·ACM 검증 CNAME)를 지우고 CloudFront 배포·인증서 정리를 큐잉한다(best-effort). 해제 요청이 만드는 승인 유형은 `DOMAIN_BINDING` 이 아니라 `DOMAIN_UNBIND` 다.
- Domain 응답과 Overview에 `hostingTarget`, `httpsEnforced`, `certificateStatus`, `certificateExpiresAt` 포함. Domain 응답의 `serverId` 는 EC2 대상(`AWS`·`AWS_EC2_FRONTEND`)일 때 가리키는 RUNNING 서버 ID(아니면 null)

현재 한계:

- Cloudflare 기본값과 profile 설정은 `qeploy.com`이다.
- Agent prompt와 사용자 안내 예시는 `qeploy.com`으로 통일되어 있다.
- 구매형 도메인은 registrar 연동 전까지 검색 결과와 연결 API에서 제외한다.
- AWS 계열(`AWS`·`AWS_EC2_FRONTEND`·`AWS_S3_FRONTEND`)은 어댑터가 있어 동작한다. `GCP` 만 어댑터가 없어 `400` 으로 거절한다. 실제 지원 목록은 `GET /domains/hosting-targets` 로 받는다(배포마다 달라질 수 있음).
- www/apex redirect 정책은 아직 없다.

---

## 12. CloudConnection API

| Method | Path | 기능 |
|---|---|---|
| GET | `/api/v1/cloud-connections` | 내 AWS/GCP 연결 목록 |
| GET | `/api/v1/cloud-connections/requirements?provider=AWS&credentialType=ROLE_ARN` | 연결 가이드: 입력 필드·얻는 위치·준비 단계·추천 IAM 권한 정책(전체본)·신뢰 정책 |
| POST | `/api/v1/cloud-connections` | 클라우드 연결 정보 등록 |
| GET | `/api/v1/cloud-connections/{cloudConnectionId}` | 연결 상세 |
| GET | `/api/v1/cloud-connections/{cloudConnectionId}/health` | 저장된 실제 검증 결과 조회 |
| POST | `/api/v1/cloud-connections/{cloudConnectionId}/verification-jobs` | 실제 권한 검증 Job 발급(재검증) |
| GET | `/api/v1/cloud-connection-verification-jobs/{jobId}` | 검증 Job 상태 조회 |
| DELETE | `/api/v1/cloud-connections/{cloudConnectionId}` | 연결 삭제 |

`requirements`(인증 필요): `provider` 기본 `AWS`, `credentialType` 기본 `ROLE_ARN`. AWS 외 provider 는 `400`. 서버에 AssumeRole 소스 신원(`platform-principal-arn`)이 설정되지 않은 배포에서는 `credentialType` 과 무관하게 **ACCESS_KEY 가이드만** 내려간다(역할 위임이 실제로 동작하지 않으므로). 응답: `provider`, `credentialType`, `recommendedCredentialType`, `credentialOptions`, `fields`, `steps`, `policyName`, `roleName`, `recommendedPolicy`(JSON), `trustPolicy`(ROLE_ARN 만, ACCESS_KEY 는 null), `notes`. 연결 검증은 자격 유효성만 보고 프로비저닝 권한은 보지 않으므로, 이 정책을 미리 붙이지 않으면 연결은 성공해도 배포에서 권한 부족으로 실패한다.

지원 credential:

- AWS `ACCESS_KEY` — IAM 사용자의 장기 키(`AKIA`)만 받는다. 임시 자격(`ASIA`)은 만료되면 배포가 전부 죽으므로 등록 시점에 `400`(#429). `ACCESS_KEY` 방식에서 `sessionToken` 에 값이 있으면 `400` 이다(필드는 하위 호환용으로만 남아 있다).
- AWS `ROLE_ARN`
- GCP `SERVICE_ACCOUNT_KEY`
- GCP `SERVICE_ACCOUNT_EMAIL`

민감정보 저장:

- AWS secret access key
- AWS session token — #429 이후 신규 등록에서는 저장하지 않는다(항상 null). 컬럼은 이전에 저장된 임시 자격 때문에 남아 있다.
- GCP service account key JSON
- 위 값은 JPA converter를 통해 AES-GCM 암호화 저장된다.
- 응답에는 원문 secret을 반환하지 않는다.

현재 동작:

- 등록 시 로컬 형식 검증만 수행하고 `VALIDATED` 상태로 저장한다.
- `cloud_connection_verification_jobs`를 발급하고 worker가 lease로 비동기 실제 검증을 수행한다.
- AWS Access Key는 STS `GetCallerIdentity`, AWS Role은 `AssumeRole` 후 STS 신원 확인을 실제로 호출한다.
- GCP Service Account Key/Email은 각각 OAuth token 발급 또는 IAM Credentials impersonation 후 Resource Manager `projects.get`을 실제로 호출한다.
- 실제 호출 성공 시에만 `CONNECTED`로 저장하며, `GET .../health`는 상태를 바꾸지 않고 저장된 결과만 조회한다.
- `CONNECTED` 상태 connection만 `PUT /api/v1/projects/{projectId}/settings/infrastructure`에서 선택할 수 있다(§6.6).
- 재검증(`POST .../verification-jobs`, `202`)은 진행 중(PENDING/RUNNING) Job 이 있으면 그 Job 을 그대로 돌려준다. 없으면 연결을 `VALIDATED` 로 되돌리고 새 Job 을 만든다.
- 연결 상태: `VALIDATED` · `VERIFYING` · `CHECKING` · `CONNECTED` · `PERMISSION_MISSING` · `BILLING_DISABLED` · `REGION_UNSUPPORTED` · `INVALID_CREDENTIAL` · `UNKNOWN_ERROR`.
- **실제 AWS 작업이 자격 오류로 실패하면 연결 상태가 `INVALID_CREDENTIAL` 로 바뀐다(#429).** 백엔드/프론트 EC2 배포 실행, 프로비저닝 중 EC2 상태 폴링, 고아 CloudFront·EIP 정리 스윕이 이를 감지한다(`AwsCredentialFailureReporter`). 판정은 재시도로 풀리지 않는 코드(`ExpiredToken`·`InvalidClientTokenId`·`InvalidAccessKeyId`·`SignatureDoesNotMatch`·`AuthFailure` 등)나 만료/무효 토큰 메시지일 때만이다. 스로틀링·5xx·네트워크 오류나 권한 부족(`AccessDenied`)으로는 바꾸지 않는다.
- 자격을 고치는 수정 API 는 없다. 재검증은 저장된 같은 값을 다시 확인할 뿐이므로, 자격이 상했으면 새 자격으로 다시 등록해야 한다.

현재 한계:

- **AWS 연결은 실제 리소스 생성에 쓰인다.** 프로젝트에 선택된(§6.6) `CONNECTED` 연결로 EC2 서버·RDS/EC2 DB(§19), S3 프론트 배포(§10.1), S3 프론트 HTTPS(CloudFront+ACM, §11)를 만든다.
- GCP 연결은 검증(`CONNECTED` 판정)까지만 있고 실제 배포 경로는 없다.
- §6.7 인프라 설정(desired state)은 프로비저닝에 쓰이지 않는다. 서버 인스턴스 타입은 `POST /servers` 요청값(기본 `t3.micro`)으로 정해진다.
- IaC plan/apply, 오토스케일링/로드밸런서 설정 API 는 없다.

---

## 13. 현재 연결 가능한 주요 호출 순서

### 13.1 로그인과 프로젝트

```text
GET  /auth/github/url
GET  /auth/github/callback
GET  /users/me
GET  /auth/github/app/install-url 또는 /reauthorize-url
GET  /projects
POST /projects
POST /projects/{projectId}/repository
```

### 13.2 Chat

```text
POST /projects/{projectId}/conversations
POST /conversations/{conversationId}/messages
GET  /conversations/{conversationId}/messages
```

메시지 `POST`는 PENDING task의 `taskId`를 즉시 돌려주고, 계획 수립은 백그라운드에서 사용자 발화만으로 진행된다. FE는 그 `taskId`로 SSE(`GET /agent/tasks/{taskId}/events/stream`)를 열어 진행을 받는다. 필요한 승인을 거쳐 Agent가 실행되며, 승인 대기/질문/결과/오류는 `kind`가 붙은 ASSISTANT 메시지로 저장된다(§8, §9 참고).

### 13.3 Agent 직접 호출

```text
POST /agent/decision
GET  /agent/tasks/{taskId}
GET  /agent/conversations/{conversationId}/active-task   # 새로고침 후 진행 태스크 복구(없으면 204)
POST /agent/tasks/{taskId}/input
DELETE /agent/tasks/{taskId}
DELETE /agent/session
```

### 13.4 Preview 운영

```text
GET  /projects/{projectId}/preview-session      # 프로젝트의 현재 프리뷰(없으면 204)
POST /projects/{projectId}/preview-session      # 프리뷰 띄우기(200 연결 / 202 준비 시작 / 409 저장소 미연결 / 503 Docker). ?force=true 는 기존 세션을 닫고 항상 새로 빌드(202)
POST /preview-sessions/{sessionId}/access       # 열람 권한 발급 — iframe 표시 직전 필수(404 없음·타유저 / 409 종료·준비중)
GET  /preview-sessions/{sessionId}/status
GET  /preview-sessions/{sessionId}/logs
DELETE /preview-sessions/{sessionId}
```

프리뷰 세션은 두 경로로 만들어진다(Issue #94).

- **작업 결과 프리뷰**: Agent CODE 스텝이 내부적으로 생성(`task_id` 있음). 생성 API 없음. 태스크 응답에는 주소가 없고 `previewCreated`(만들어졌는지)만 있다 — 열 주소는 `POST /preview-sessions/{sessionId}/access`가 준다(§9.2).
- **현재 상태 프리뷰**: 위 `POST`로 사용자가 직접 생성(`task_id` NULL, V31). `preview` 브랜치를 clone → 빌드 → 서빙하며, 준비 중에는 세션 상태가 `PROVISIONING`이다(이 응답에는 주소가 없다 — `ACTIVE`가 되면 access로 발급받는다). 실패하면 `FAILED` + `failureReason`(빌드 로그 꼬리 포함).

`previewUrl`의 기준 오리진은 `qeploy.preview.gateway-base-url`이다(운영 기본값 `https://qeploy.com`, 미설정 시 CORS 허용 오리진에서 유추 — Issue #95). Docker 데몬에 닿지 못하면 500이 아니라 **503 `PREVIEW_ENVIRONMENT_UNAVAILABLE`**로 원인을 적어 응답한다.

게이트웨이(`GET /api/v1/previews/{sessionId}/{accessToken}/**`)는 URL만으로 열리지 않는다(Issue #77). `POST /preview-sessions/{sessionId}/access`가 소유자를 확인하고 `HttpOnly` 소유권 쿠키(`Path=/api/v1/previews/{sessionId}/`)를 `Set-Cookie`로 내려주며, 문서 탐색(`Sec-Fetch-Dest`가 document/iframe/frame/embed/object이거나 헤더 없음)을 쿠키 없이 하면 **401**이다. JS/CSS/이미지 같은 서브리소스는 회전된 accessToken URL만으로 통과한다(#108 — sandbox 문서는 서브리소스에 쿠키를 싣지 않는다)(`qeploy.preview.require-access-cookie`, 기본 true, dev 프로필 기본 false). 이 호출은 accessToken을 **회전**시키므로 응답 `{ sessionId, previewUrl, expiresAt }`의 `previewUrl`만 유효하고, 이전에 발급된 주소는 즉시 404가 된다.

게이트웨이 응답 코드: 세션 없음·토큰 불일치 `404` / 문서 탐색에 쿠키 없음 `401` / 요청 본문 10 MiB 초과 `413` / 본문을 끝까지 못 읽음 `400` / 안쪽 앱에 닿지 못함 `502`(일시 실패가 아니면 세션을 회수해, 다음 `GET /projects/{id}/preview-session`이 `204`가 된다). `Accept: text/event-stream` 요청은 스트리밍 프록시로 갈리고 `Last-Event-ID`를 전달한다(그 외 계약은 같다). 응답은 공통 envelope로 감싸지 않는다.

### 13.5 GitHub Pages 배포

```text
GET  /projects/{projectId}/repository-health
POST /projects/{projectId}/deployments
GET  /deployments/{deploymentId}
GET  /deployments/{deploymentId}/logs
GET  /projects/{projectId}/versions
```

프론트를 S3·EC2 로 올릴 때는 같은 `POST /projects/{projectId}/deployments` 에 `frontendHostingType` 을 실어 보낸다(§10.1). EC2 는 승인이 필요하다:

```text
POST /projects/{projectId}/deployments      ({"deployTargetType":"LATEST","frontendHostingType":"EC2"} → approvalIds)
POST /approvals/{approvalId}/approve
GET  /projects/{projectId}/servers          (webOnly=true 서버가 RUNNING 될 때까지 폴링)
```

배포가 FAILED로 끝나면 다음 흐름으로 복구한다(§10.2 참고):

```text
POST /deployments/{deploymentId}/failure-analysis
GET  /deployments/{deploymentId}/failure-analysis
POST /deployments/{deploymentId}/retry
```

### 13.6 도메인

```text
GET  /domains/hosting-targets
GET  /domain-search
POST /projects/{projectId}/domains
GET  /domains/{domainId}/verification-guide
POST /domains/{domainId}/verification-checks
```

### 13.7 Environment

```text
GET    /projects/{projectId}/environment-variables
POST   /projects/{projectId}/environment-variables
PATCH  /projects/{projectId}/environment-variables/{variableId}
DELETE /projects/{projectId}/environment-variables/{variableId}
GET    /projects/{projectId}/environment-variables/history
```

### 13.8 인프라 설정 저장(승인 포함)

```text
GET  /projects/{projectId}/settings/infrastructure/configuration
PUT  /projects/{projectId}/settings/infrastructure/configuration
GET  /projects/{projectId}/settings/infrastructure/configuration/history
```

`infraApprovalRequired`가 켜져 있으면 `PUT` 이후 standalone INFRA_OPERATION 승인이 생성되며, 다음 흐름으로 확정한다(§14.3 참고):

```text
GET  /approvals/{approvalId}
POST /approvals/{approvalId}/approve  또는  POST /approvals/{approvalId}/reject
```

### 13.9 비용/예산

```text
GET /projects/{projectId}/settings/infrastructure/configuration
PUT /projects/{projectId}/settings/cost-budget
GET /projects/{projectId}/settings/cost-budget
```

인프라 설정(§13.8) 저장 후 비용/예산 조회 순서로 확인하는 것이 일반적이지만, 예산은 인프라 설정 이전에도 먼저 저장할 수 있다(§6.8 참고).

### 13.10 Cloud Ops (자연어 운영)

**HTTP 엔드포인트는 §13.2/13.3과 동일하다.** 요청 본문의 자연어 내용만 다르다(§9.4 참고).

```text
POST /conversations/{conversationId}/messages   ("서버 상태 알려줘" 등)
또는
POST /agent/decision
```

### 13.11 결과 승인 (RESULT, Issue #56)

CODE 작업이 끝나면(정책 ON + 저장소 BOUND일 때) 자동으로 다음이 열린다:

```text
GET  /agent/tasks/{taskId}        (status: WAITING_RESULT_APPROVAL)
GET  /projects/{projectId}/approvals   (type: RESULT)
POST /approvals/{approvalId}/approve   → preview→main merge, task 재개
또는
POST /approvals/{approvalId}/reject    → task CANCELLED, preview에는 그대로 남음
```

저장소가 아직 없는(NOT_BOUND) 프로젝트는 같은 지점에서 RESULT 대신 저장소 연결 승인(REPOSITORY_BINDING)이 열린다(작업 결과 프리뷰가 있을 때만, 정책으로 끌 수 없음):

```text
GET  /agent/tasks/{taskId}        (status: WAITING_RESULT_APPROVAL)
GET  /approvals/{approvalId}      (type: REPOSITORY_BINDING, input.field=repositoryName)
POST /approvals/{approvalId}/approve  { "repositoryName": "..." }(선택) → 저장소 생성·연결 + preview 브랜치 push, task 재개
또는
POST /approvals/{approvalId}/reject   → 저장소를 만들지 않음. task는 취소되지 않고 그대로 완료
```

프리뷰가 이미 회수된 뒤 approve를 누르면 승인은 `CANCELLED`로 닫히고(응답 200) task는 저장소 없이 마친다. 결정 없이 프리뷰가 회수되거나 오래 방치돼도(`qeploy.agent.approval.abandon-ttl`, 기본 7일) 같은 방식으로 마치며, 이때 채팅의 마감 안내는 `kind=TASK_CLOSED` 이고 결과 메시지는 붙지 않는다(#461, §8).

§9.5 참고.

---

## 14. 확장 API

### 14.1 Import

- `POST /api/v1/project-imports/zip`
- `POST /api/v1/project-imports/github`
- `GET /api/v1/project-imports/{importId}`
- ZIP 보안 검사, 압축 해제, 프로젝트 분석, 저장소 연결 단계가 필요하다.
- 현재 백로그에서는 MVP 범위 밖으로 정리되어 있다(`BACKLOG_STATUS.md` Removed 참고). 착수 전 사용자 확인이 필요하다.

### 14.2 Preview와 Change

구현됨:

- `GET /api/v1/projects/{projectId}/changes` — `status`: `PREVIEW_READY` / `DEPLOYED` / `MERGED` / `REJECTED`(뒤 2개는 결과 승인 게이트, §9.5 참고). `MERGED`는 `approvalId`/`prNumber`/`mergeCommitSha`/`mergedAt`을 함께 반환한다.
  최신순, `limit` 기본 200·최대 500. 더 있으면 `X-Qeploy-Next-Cursor` 헤더가 붙고 `after`로 넘기면 그보다 오래된 항목을 받는다.
- `GET /api/v1/changes/{changeId}`
- `GET /api/v1/changes/{changeId}/diff`
- `DELETE /api/v1/preview-sessions/{previewSessionId}`
- `GET /api/v1/preview-sessions/{previewSessionId}/status`
- `GET /api/v1/preview-sessions/{previewSessionId}/logs?tail=&sinceSeconds=` — `tail` 기본 200, [1, 2000] 밖이면 경계값으로 보정. `sinceSeconds`는 "최근 N초". 컨테이너가 이미 없으면 404가 아니라 `containerRunning=false`, `logText=""`
- `GET /api/v1/projects/{projectId}/preview-session` — 프로젝트의 현재 프리뷰(작업 프리뷰/현재 상태 프리뷰 중 최근 것). ACTIVE 세션은 컨테이너 생존을 확인한 뒤에만 반환한다
- `POST /api/v1/projects/{projectId}/preview-session` — 작업 지시 없이 `preview` 브랜치 현재 상태로 프리뷰 생성. `?force=true`면 붙지 않고 항상 새로 빌드(202)
- `POST /api/v1/preview-sessions/{previewSessionId}/access` — 열람 권한 발급(소유권 쿠키 + accessToken 회전, Issue #77). iframe 표시 직전 필수
- `GET /api/v1/projects/{projectId}/preview/runtime` — 프리뷰 런타임 설정 조회. 저장값이 없으면 `source=DEFAULT`(`STATIC`). 실제 기동 시에는 저장값이 없으면 클론 내용으로 자동 감지한다(`DETECTED`)
- `PUT /api/v1/projects/{projectId}/preview/runtime` — **전체 교체**. 본문 `{runtimeType(STATIC|NODE_SERVER|JAVA_FULLSTACK, 필수), startCommand, apiPathPrefix, healthPath, dbEngine(MYSQL|POSTGRESQL)}`. 비운 `apiPathPrefix`는 `/api`, `dbEngine`은 `MYSQL`, `healthPath`는 null로 리셋된다. 일부만 바꾸려면 GET 값을 그대로 실어 보낸다(채팅 `RUNTIME_SETUP`은 부분 갱신). 응답 `source`: `STORED`|`DEFAULT`|`DETECTED`. 남의·없는 프로젝트는 `404`

Preview URL은 `/api/v1/previews/{previewSessionId}/{accessToken}/...` gateway 형식으로 발급되며, 오리진은 서버 설정(`qeploy.preview.gateway-base-url`)에서 온다. 게이트웨이는 위 access 발급으로 받은 소유권 쿠키가 없으면 문서 탐색에 **401**을 반환한다(서브리소스는 accessToken URL만으로 통과, §13.4 참고).

예정:

- `POST /api/v1/changes/{changeId}/rebuild`

### 14.3 Approval

구현됨:

- `GET /api/v1/projects/{projectId}/approvals` — 상태와 무관하게 최신순. `limit` 기본 200·최대 500, 더 있으면 `X-Qeploy-Next-Cursor` 헤더, `after`로 넘기면 그보다 오래된 것
- `GET /api/v1/approvals/{approvalId}`
- `POST /api/v1/approvals/{approvalId}/approve`
- `POST /api/v1/approvals/{approvalId}/reject`

승인 유형:

- `CHANGE`
- `DEPLOYMENT`
- `DOMAIN_BINDING`
- `DOMAIN_UNBIND` — 도메인 연결 **해제**(DOMAIN_BIND step의 `operation=DELETE`). 되돌리기 어려운 삭제라 "연결"로 오표시되지 않게 유형을 나눴다. 정책은 `domainApprovalRequired`를 같이 따른다.
- `INFRA_OPERATION`
- `RESULT` — 결과 승인(Issue #56, §9.5 참고). 이미 실행된 CODE 결과를 main에 반영할지 묻는다. 항상 Agent task 기반(`taskId` 있음)이며 standalone으로는 생성되지 않는다.
- `REPOSITORY_BINDING` — 저장소 연결 승인. RESULT와 같은 지점(마지막 CODE step 직후)에서 저장소가 없는(NOT_BOUND) 프로젝트에만 열리는 RESULT의 짝이다. 항상 task 기반이고 정책으로 끌 수 없다.
- `DATABASE_PROVISION` — DB 생성(RDS 또는 EC2 위 DB 컨테이너, §19.5). 과금이라 정책으로 끌 수 없다. standalone.
- `SERVER_PROVISION` — EC2 백엔드/프론트 서버 생성(§19.2). 과금이라 정책으로 끌 수 없다. standalone.

`CHANGE`·`DEPLOYMENT`·`DOMAIN_BINDING`·`DOMAIN_UNBIND`·`INFRA_OPERATION`은 실행 **전** 계획을 승인하고, `RESULT`·`REPOSITORY_BINDING`은 이미 실행된 CODE 결과를 어떻게 남길지 승인한다.

승인 출처는 두 가지다:

- Agent 기반: `approval.taskId`가 채워짐. Agent plan 실행 중 생성된다(§9 참고). `RESULT`는 항상 이 경로다.
- **Standalone**: `approval.taskId = null`. Agent task 없이 API에서 직접 생성된다. `StandaloneApprovalHandler` SPI를 구현한 도메인이 승인/거절 후속 처리를 소유한다 — `InfrastructureChangeApprovalHandler`(INFRA_OPERATION), `DatabaseProvisionApprovalHandler`(DATABASE_PROVISION), `ServerProvisionApprovalHandler`(SERVER_PROVISION). 에이전트의 BACKEND_DEPLOY 경로가 만드는 DB·서버 승인도 standalone이지만 `conversationId`가 채워져(`Approval.standaloneInConversation`) 채팅에 카드로 뜬다. 즉 standalone이어도 `conversationId`가 null이라는 보장은 없다.
- `INFRA_OPERATION` 타입은 두 출처를 모두 가질 수 있는 유일한 승인 유형이다: §6.7의 `PUT .../settings/infrastructure/configuration`이 만드는 승인은 standalone(`taskId=null`)이고, §9.4 Cloud Ops Agent의 `RESTART`가 만드는 승인은 Agent 기반(`taskId` 있음, 일반 Agent plan 승인과 동일 경로)이다. `ApprovalType`은 같지만 `taskId` 유무로 두 흐름을 구분한다.
- 승인/거절 결정에는 동시 요청 경합 방지를 위한 비관적 잠금이 걸려 있다.

approve 요청 본문(선택): `{ "repositoryName": "..." }` — `REPOSITORY_BINDING`에서만 읽고 다른 유형은 무시한다. 소문자·숫자·하이픈만 유효하며 그 외 문자는 하이픈으로 치환된다. 생략하거나 비우면 프로젝트명 기반 후보 이름을 쓴다. 응답의 `input`(nullable)이 이 입력 스펙(`field`, `defaultValue`, `required=false`, `pattern="^[a-z0-9-]+$"`, `maxLength=100`)을 주며, PENDING인 `REPOSITORY_BINDING`에만 채워진다.

거절 예외: 다른 유형의 거절은 task를 `CANCELLED`로 만든다(RESULT 포함). `REPOSITORY_BINDING`만 task를 취소하지 않는다 — 거절은 "저장소를 만들지 않는다"는 뜻이고 task는 그대로 재개·완료된다. 승인 시점에 프리뷰가 이미 회수됐으면 승인은 `CANCELLED`로 닫히고 task는 저장소 없이 마친다(이때 결과 메시지는 내지 않는다, #461).

PENDING이 아닌 승인에 approve/reject를 호출하면 `409`, 본인 소유가 아니거나 없으면 `404`.

채팅 기록의 승인 안내 줄은 유형을 사람이 읽는 이름으로 남긴다(#457, `ApprovalTypeLabel` — 예: "변경 사항 승인", "GitHub 저장소 연결"). 예전처럼 `- [48] CHANGE` 같은 ID·원시 enum을 쓰지 않는다. 승인 ID는 승인 API로 본다.

### 14.4 Project Settings

구현됨:

- `GET/PATCH /api/v1/projects/{projectId}/settings/chat` — 5개 승인 정책 필드(`resultApprovalRequired` 포함), §6.9 참고
- `GET/PUT/DELETE /api/v1/projects/{projectId}/settings/infrastructure` — CloudConnection 선택(§6.6)
- `GET/PUT /api/v1/projects/{projectId}/settings/infrastructure/configuration` + `GET .../configuration/history` — 인프라 설정 저장(§6.7)
- `GET /api/v1/projects/{projectId}/settings/repository`
- `GET/PUT /api/v1/projects/{projectId}/preview/runtime` — 프리뷰 런타임 설정(STATIC/NODE_SERVER/JAVA_FULLSTACK). PUT은 전체 교체. 상세는 §14.2
- `GET/PUT/DELETE /api/v1/projects/{projectId}/settings/cost-budget` — 비용 추정 + 예산(§6.8)
- `GET/POST/PATCH/DELETE /api/v1/projects/{projectId}/environment-variables`(+ `/history`) — Environment/Secrets 설정, §7 참고

아래 설정 API는 목표 상태다.

- `GET/PATCH /api/v1/projects/{projectId}/settings/general`
- `GET/PATCH /api/v1/projects/{projectId}/settings/release-policy`
- `GET/PATCH /api/v1/projects/{projectId}/settings/deployment`
- `GET/PATCH /api/v1/projects/{projectId}/settings/domain`

### 14.5 AWS/GCP 실제 프로비저닝과 운영

사용자 AWS 계정에 실제 리소스를 만드는 API 는 §19(EC2 서버·DB 프로비저닝)와 §10.1(S3/EC2 프론트 배포)로 구현되어 있다. 다만 §6.7 의 인프라 "설정 저장"(desired state)·§6.8 비용 추정과는 연결되어 있지 않다 — 프로비저닝은 그 설정을 읽지 않는다. 아래는 여전히 없는 API 다.

- `POST /api/v1/projects/{projectId}/cloud-deployments` — 실제 IaC plan/apply 실행
- `GET /api/v1/projects/{projectId}/infrastructure/status` — 실제 프로비저닝된 리소스 상태 조회(현재 §9.4 STATUS_CHECK는 "프로비저닝되지 않았다"만 알린다)
- `GET /api/v1/projects/{projectId}/infrastructure/logs` — 실제 서버 로그(현재 §9.4 LOG_VIEW는 Preview 컨테이너 로그만 조회한다)
- `POST /api/v1/projects/{projectId}/infrastructure/operations` — `RESOURCE_SCALING`/`AUTOSCALING_CHANGE`/`RESOURCE_CLEANUP` 등 실제 실행(현재 §9.4에서 감지·거부만 구현됨)

### 14.6 Persistent Job

AgentRun 구현:

- `GET /api/v1/agent/tasks/{taskId}`
- `GET /api/v1/agent/conversations/{conversationId}/active-task`
- `GET /api/v1/agent/tasks/{taskId}/events`
- `GET /api/v1/agent/tasks/{taskId}/events/stream`
- `POST /api/v1/agent/tasks/{taskId}/retry`
- `DELETE /api/v1/agent/tasks/{taskId}`

CloudConnection verification job도 동일한 claim/lease DB queue 모델을 사용한다(§12).

Import, deployment, domain health까지 동일 Job 모델로 통합하는 작업은 남아 있다.

---

## 15. AuditLog API (Issue #74)

Base path: `/api/v1/projects/{projectId}/audit-logs`

| Method | Path | 기능 |
|---|---|---|
| GET | `/api/v1/projects/{projectId}/audit-logs` | 프로젝트 감사 로그 조회(`category`·`limit` 필터) |

### 15.1 조회

`GET /api/v1/projects/{projectId}/audit-logs?category={GITHUB|DEPLOYMENT|DOMAIN|INFRA}&limit=50`

동작(`AuditLogController` → `AuditLogQueryService` 실측):

- 소유권: 프로젝트가 없거나 요청자 소유가 아니면 **404**(다른 프로젝트 하위 API와 동일한 소유자 전용 패턴 — 관리자/전체 조회 표면 없음).
- `category`: 생략 시 전체 조회. `GITHUB`/`DEPLOYMENT`/`DOMAIN`/`INFRA` 외의 값은 **400**(enum `valueOf` 원본 예외 메시지를 그대로 노출하지 않고 "지원하지 않는 category입니다: {입력값}"으로 래핑).
- `limit`: 생략·0·음수 → 기본 50, 200 초과 → 200으로 보정(다른 이력 조회 API와 동일한 관례). offset 페이징 없음.
- 정렬: `audit_log_id desc`(최신순 고정, auto_increment 기준).
- 신규 `ErrorCode`는 없다 — 404/400/401 기존 체인을 그대로 재사용한다.

응답(배열, 공통 envelope로 자동 래핑):

```json
[
  {
    "auditLogId": 42,
    "category": "DEPLOYMENT",
    "action": "DEPLOYMENT_REQUESTED",
    "outcome": "SUCCEEDED",
    "actorType": "USER",
    "actorUserId": 7,
    "resourceType": "DEPLOYMENT",
    "resourceId": "123",
    "taskId": null,
    "approvalId": null,
    "detail": "target=LATEST, correlationId=6f9a2c10",
    "errorSummary": null,
    "createdAt": "2026-07-25T10:00:00"
  }
]
```

필드는 `AuditLogResponse`(`audit/presentation/dto/response`)를 그대로 반영한다.

### 15.2 기록되는 데이터 (참고 — 이 API는 조회 전용)

이 엔드포인트는 감사 로그를 **읽기만** 한다. 감사 행을 만들거나 수정·삭제하는 공개 API는 없다 — 기록은 GitHub 저장소 생성/연결/해제/삭제, preview 브랜치 push, RESULT merge, 배포 요청/재시도/성공/실패, 도메인 연결/해제, preview 재시작, 인프라 설정 변경 요청/적용/거절, 배포 EC2 서버 자동 복구 시도/실패(`SERVER_RECOVERY_ATTEMPTED`/`SERVER_RECOVERY_FAILED`, 헬스 모니터가 무응답 앱을 재시작한 시스템 이벤트) 등 18종 액션(`AuditAction`, `category`는 `GITHUB`/`DEPLOYMENT`/`DOMAIN`/`INFRA` 4종)이 발생할 때 서버 내부 훅에서만 남는다.

- `detail`/`errorSummary`에는 토큰·시크릿·환경변수 값·로그 본문을 저장하지 않는다. `errorSummary`는 저장 전 시크릿 패턴 레닥션(`SecretRedactor`)을 거친다.
- 보관 기간은 기본 180일(`qeploy.audit.retention-days`)이며, 만료 행은 시간당 배치 스케줄러(`qeploy.audit.retention-sweep-interval-ms`, 기본 3600000ms)가 삭제한다. 삭제 결과를 조회하는 API는 없다(애플리케이션 로그로만 확인 가능).
- 감사 기록 자체가 실패해도 원래 요청(레포 연결, 배포 등)은 실패하지 않는다(비차단 계약). 이 경우 행이 남지 않으며, 애플리케이션 로그의 `AUDIT_FALLBACK` 라인에 흔적이 남는다.
- **기록은 즉시 보이지 않을 수 있다(Issue #345).** 감사 INSERT 는 요청 처리와 분리된 전용 스레드에서 일어난다. 쓰기 요청이 `200`을 돌려준 직후 이 API 를 호출하면 해당 행이 아직 없을 수 있다 — 실무상 지연은 INSERT 한 건 수준(밀리초)이지만 **순서가 보장되지는 않는다.** 쓰기 직후 감사 목록을 갱신하는 화면이라면 짧은 재조회가 필요하다.
- 위 지연과 별개로, 프로세스가 **비정상 종료**하면 큐에 남아 있던 기록은 유실된다. 정상 종료에서는 큐를 흘려보내고 내려간다. 즉 조회 대상에 누락이 생기는 경우는 ① 감사 쓰기 실패(`AUDIT_FALLBACK`) ② 비정상 종료 두 가지다.

---

## 16. AiCredential API (BYOK, Issue #242)

사용자 본인 AI 제공자 API 키를 등록·조회·삭제한다. 등록한 키로 `CLAUDE_CODE`·`CODEX` 코딩 에이전트가 격리 컨테이너에서 실행되고, 사용량은 사용자 계정으로 직접 청구된다. 상세 설계는 `docs/byok-coding-agent-design.md`, 요구사항은 `srs.md`.

### 16.1 엔드포인트 (3개)

| 메서드 | 경로 | 용도 |
|---|---|---|
| GET | `/api/v1/ai-credentials` | 본인이 등록한 키 목록(마스킹) |
| PUT | `/api/v1/ai-credentials/{provider}` | 등록/교체 (벤더당 키 하나라 두 동작이 같다) |
| DELETE | `/api/v1/ai-credentials/{provider}` | 삭제. 미등록이면 404 |

소유자는 인증 토큰에서만 온다. 저장소 포트에 id 단독 조회나 전체 조회가 없어, 남의 키에 도달하는 경로가 구조적으로 존재하지 않는다.

### 16.2 provider 는 벤더 단위

`ANTHROPIC` · `OPENAI` · `GLM` 만 받는다. `CLAUDE_CODE` 는 Anthropic 키를, `CODEX` 는 OpenAI 키를 쓰므로(`AiProvider.credentialVendor()`), 실행 모드로 등록을 시도하면 400 이다. 사용자는 벤더당 키를 한 번만 넣는다.

### 16.3 키 취급

- 저장은 기존 `AesEncryptor`(AES-256-GCM) 컨버터로 at-rest 암호화. 신규 crypto 코드 없음(U3 방침).
- 응답에는 평문이 없다. `maskedApiKey` 는 앞 6자만 남긴다(`sk-ant****`) — 접두사는 벤더별로 거의 상수라 식별용이고, 꼬리는 실제 엔트로피라 노출하지 않는다.
- 도메인·엔티티 모두 `toString` 을 두지 않아 로그로 새지 않는다. 실행 시에는 컨테이너 exec 환경변수로만 전달되어 `docker inspect` 나 명령행(`/proc`)에 남지 않는다.
- 공백·제어문자가 섞인 키는 400. 환경변수로 그대로 주입되는 값이라 개행이 붙은 채 조용히 받지 않는다.
- 요청 본문 `{ apiKey(필수, ≤512자), label(선택, ≤64자) }`. 키가 6자 이하면 `maskedApiKey`는 `****`다.

### 16.4 미등록 시 동작

키 없이 코딩 에이전트를 요청하면 `400 AI_CREDENTIAL_NOT_REGISTERED`. 서버가 운영자 키로 대신 채우지 않는다 — 사용자를 대신한 결제·재판매·중개는 제공사 약관이 금지한다. FE 는 이 코드를 키 등록 화면 유도에 쓴다.

### 16.5 어디에 쓰이는가

등록한 키는 **그 사용자의 모든 AI 실행**에 쓰인다 — 계획(DECISION)·채팅·코드 생성 전부. 서버 키로 도는 경로는 없다(#364). 지금 무엇을 고를 수 있는지는 `GET /api/v1/agent/ai-providers`(§9.6)로 조회한다.

### 16.6 저장 스키마

`ai_provider_credentials`(V55): `user_id`, `provider`, `encrypted_api_key`, `label`, timestamps. `UNIQUE(user_id, provider)` + 사용자 삭제 시 CASCADE.

---

## 17. Template API

퍼블리싱 템플릿 카탈로그. **정본은 이 서버가 아니다** — 템플릿 저장소(`Dvely/qeploy-templates`)가 GitHub Pages 로 발행하는 `catalog.json` 을 서버가 읽어 나른다. 서버는 템플릿 소스를 들지 않는다.

설계 배경은 `docs/template-architecture-design.md`.

**인증 불필요.** 내용 자체가 이미 공개 Pages 에 있는 정적 목록이고 사용자 데이터가 없어, 로그인 전 화면에서도 갤러리를 띄울 수 있게 열려 있다.

### 17.1 목록 조회

`GET /api/v1/templates`

```json
[
  {
    "templateId": "landing-minimal",
    "name": "미니멀 랜딩",
    "description": "제품·서비스 하나를 소개하는 한 장짜리 랜딩 페이지",
    "tags": ["landing", "one-page", "product"],
    "stack": "vanilla",
    "demoUrl": "https://dvely.github.io/qeploy-templates/t/landing-minimal/",
    "thumbnailUrl": "https://dvely.github.io/qeploy-templates/t/landing-minimal/thumbnail.jpg",
    "contentHints": [
      { "key": "hero.title", "where": "index.html", "desc": "히어로 대제목 — 한 문장으로 무엇인지" }
    ]
  }
]
```

`thumbnailUrl` 은 **목록 카드용**이고 `demoUrl` 은 **고른 하나를 조작해보는 용도**다. 17종을 동시에 iframe 으로 올리면 데모 이미지 총량(24MB)이 한 번에 가므로, 목록은 썸네일만 받고 iframe 은 항상 한 개만 띄우는 것이 의도된 사용이다. 썸네일은 템플릿 저장소 CI 가 발행할 데모를 그대로 찍어 만든다 — 손으로 커밋하면 템플릿을 고칠 때 어긋난다.

`thumbnailUrl` 은 **nullable** 이다. 카탈로그는 Pages 에서 실시간으로 받아오므로 썸네일 발행 전 카탈로그에는 이 필드가 없다 — 필수로 두면 발행 순서에 따라 이 API 전체가 깨진다.

`demoUrl` 은 **iframe 으로 띄울 수 있다.** GitHub Pages 는 정상 문서에 `X-Frame-Options` 도 `CSP frame-ancestors` 도 보내지 않는다(2026-09-10 실측). 고르기 전에 조작해보게 하는 것이 이 필드의 목적이다.

`contentHints` 는 "어디가 바꿔도 되는 내용인지" 에 대한 템플릿 자신의 선언이다. 없으면 코딩 에이전트가 무엇이 내용이고 무엇이 구조인지 추측한다.

씨앗 tarball 주소(`sourceUrl`)는 **응답에 담지 않는다.** 서버가 컨테이너에 풀 때만 쓰는 내부 경로다.

### 17.2 단건 조회

`GET /api/v1/templates/{templateId}`

목록과 같은 형태의 단건. 없는 ID 는 `404`.

### 17.3 카탈로그를 읽지 못할 때

갱신에 실패해도 **직전에 읽어둔 목록으로 계속 응답한다**(stale-while-error). 카탈로그는 정적 문서라 잠깐 낡은 목록을 보여주는 편이 기능을 멈추는 것보다 낫다. 낡은 것을 쓰는 동안은 WARN 로그가 남는다.

`503 TEMPLATE_CATALOG_UNAVAILABLE` 은 **한 번도 읽지 못한 경우에만** 나간다.

그 "한 번도"는 예전엔 **이 프로세스가 뜬 뒤로**였다 — 캐시가 메모리에만 있어서 재시작마다 cold start 였고, 배포가 곧 그 순간이다. 이제 성공한 갱신을 DB(`template_catalog_snapshot`, 단일 행)에 남기고, cold start 에서 네트워크가 실패했을 때만 되살린다(#395).

복원에는 세 가지 조건이 걸린다. 하나라도 어긋나면 복원하지 않고 예전처럼 `503` 이다.

| 조건 | 왜 |
|---|---|
| 설정된 카탈로그 URL 과 스냅샷의 출처가 같다 | URL 이 바뀌었다면 남의 카탈로그다 |
| 스냅샷 나이 ≤ `qeploy.template.catalog.snapshot-max-age` (기본 `24h`) | 너무 낡은 목록은 이미 없어진 템플릿을 고르게 만든다. 그러면 §17 의 존재 확인이 막으려던 "고를 때는 200, 만들 때는 실패"가 된다 |
| 복원한 값이 지금 기준으로도 안전하다 | 아래 스킴 검사를 복원 경로도 통과해야 한다 |

저장·복원은 어느 쪽이 실패해도 **카탈로그 서빙을 막지 않는다.** 저장 실패는 WARN, 복원 실패는 ERROR 를 남기고 이 기능이 없던 때와 똑같이 동작한다.

### 17.4 카탈로그 URL 의 스킴 검증 (#395)

받은 문서의 `demoUrl`(필수)과 `thumbnailUrl`(nullable)이 `http://` 또는 `https://` 로 시작하는지 확인한다. 어기면 **템플릿 하나를 빼지 않고 문서 전체를 거부**하고, 위의 stale-while-error 가 직전 목록을 유지한다 — 조용히 빼면 카탈로그를 고친 사람은 자기가 무엇을 잘못했는지 모른 채 그 템플릿이 목록에서 사라진 것만 본다. WARN 에 어느 템플릿의 어느 필드인지 남는다.

FE 는 `demoUrl` 을 iframe `src` 에, `thumbnailUrl` 을 `img src` 에 넣는다. 자기 쪽에서도 막고 있지만(`Dvely_FE#117`), **이 서버가 유일한 병목이라 여기서 막으면 모든 소비자가 덮인다** — 앞으로 붙는 소비자까지.

`http` 는 허용한다. 코드 실행 벡터는 `javascript:`·`data:` 이고 `http` 는 아니다. https 페이지의 iframe 이 http 를 물면 그건 브라우저가 mixed content 로 막는 일이고, 여기서 https 를 강제하면 보안은 나아지지 않고 로컬 템플릿 서버만 깨진다.

`sourceUrl` 은 이 검사에 포함하지 않는다. 그 값은 씨딩 때 셸 `curl` 에 들어가므로 훨씬 좁은 검사가 이미 **소비 지점**에 걸려 있다(`^https://[A-Za-z0-9._~:/-]+\.tar\.gz$`). 싱크에 붙은 검사를 수신 지점으로 옮기거나 느슨하게 복제하지 않는다.

---

## 18. ApiToken API (PAT, Issue #304)

에이전트·CLI 용 개인 액세스 토큰. 브라우저 JWT 는 `JWT_EXPIRATION_MS` 기본 1시간이라 헤드리스 클라이언트가 쓸 수 없어 이 토큰을 둔다. MCP 서버·CLI(PRD 부록 A-2)의 선행 요건이다.

### 18.1 엔드포인트 (3개)

| 메서드 | 경로 | 용도 |
|---|---|---|
| GET | `/api/v1/api-tokens` | 본인 토큰 목록(평문 없음) |
| POST | `/api/v1/api-tokens` | 발급. 평문은 이 응답에서만 1회 |
| DELETE | `/api/v1/api-tokens/{apiTokenId}` | 폐기. 미발급 ID 면 404 |

### 18.2 저장 방식 — 해시, 암호화가 아니다

`ai_provider_credentials`(§16)는 키를 벤더 CLI 에 전달해야 해서 복호화 가능한 AES 저장이지만, PAT 는 우리가 비교만 하면 되므로 원문을 보관하지 않는다. DB 를 잃어도 동작하는 토큰이 함께 새지 않고, 재노출 경로가 애초에 없다.

해시는 SHA-256 이다. bcrypt 류의 work factor 는 엔트로피가 낮은 비밀번호를 느리게 만들려는 장치인데 이 토큰은 256비트 난수라 work factor 가 추측 가능성을 바꾸지 않는다. 바꾸는 것은 모든 인증 요청의 비용뿐이다.

### 18.3 인증·스코프

- `Authorization: Bearer qp_...` — 필터가 `qp_` 접두사로 PAT 경로를 고른다. JWT 파싱을 먼저 시도하는 방식은 에이전트 요청마다 예외를 던진다.
- 스코프 `READ`/`WRITE` 2종. **HTTP 메서드로 강제**한다 — `READ` 토큰은 GET 만 가능하고 변경 메서드는 **403**. 엔드포인트별 애노테이션이면 새 엔드포인트가 누군가 애노테이션을 기억한 날에야 보호되지만, 메서드 기준은 작성한 날부터 덮인다.
- **`WRITE` 도 모든 쓰기가 아니다(#453).** PAT로 통과하는 쓰기는 허용 목록(`JwtAuthenticationFilter.PAT_WRITABLE`)의 다섯 개뿐이다.

  | Method | Path |
  |---|---|
  | POST | `/api/v1/projects/{projectId}/deployments` |
  | POST | `/api/v1/deployments/{deploymentId}/retry` |
  | POST | `/api/v1/projects/{projectId}/environment-variables` |
  | PATCH | `/api/v1/projects/{projectId}/environment-variables/{variableId}` |
  | POST | `/api/v1/projects/{projectId}/domains` |

  나머지 변경 메서드(승인 결정, PAT 발급·폐기, AI 키, 클라우드 연결, 각종 삭제 등)는 `WRITE` 토큰이어도 **403**이다. 그 전에는 WRITE PAT가 브라우저 로그인과 같은 권한이라, 에이전트가 저장된 토큰으로 자기 승인을 누르거나 PAT로 PAT를 재발급할 수 있었다. 거부 목록이 아니라 허용 목록이라 새 엔드포인트는 누군가 열기 전까지 닫혀 있다. 에이전트에 쓰기를 새로 열 때는 이 목록에 한 줄을 더하고, `agent-tools/packages/client`의 쓰기 호출이 목록 밖으로 나가면 `JwtAuthenticationFilterTest`가 깨진다. 브라우저 JWT는 영향이 없다.
- 403 메시지는 두 가지다: READ 토큰의 쓰기 → "이 토큰은 읽기 전용입니다. write 스코프 토큰이 필요합니다." / 허용 목록 밖의 쓰기 → "이 작업은 개인 액세스 토큰으로 할 수 없습니다. 웹에서 진행해주세요." 본문은 `{"code":"FORBIDDEN","message":"..."}`이다.
- 403 이지 401 이 아니다. 인증 자체는 성공했으므로 재인증하러 보낼 일이 아니다.
- 미상·만료·폐기는 전부 **401** 로 동일하다. 어느 쪽인지 알려주지 않는다.
- **만료는 스코프마다 다르다** — `READ` 기본 90일·최대 365일, `WRITE` 기본 30일·최대 90일. 유출됐을 때 할 수 있는 일이 다르기 때문이다: `READ` 는 소유자가 이미 볼 수 있는 것을 드러내지만 `WRITE` 는 배포하고 환경변수를 바꾸고 도메인을 붙이며, 그것도 아무도 화면을 보고 있지 않은 헤드리스 클라이언트에서 일어난다. 상한이 있어야 아무도 기억하지 못하는 토큰이 스스로 멈춘다.
- 범위를 벗어나면 400 이고, 메시지에 스코프를 넣는다 — 365일 요청은 그 토큰이 배포할 수 있다는 걸 알기 전까지 합리적이라, 상한만 알려주면 왜 답이 달라졌는지 알 수 없다.
- `last_used_at` 은 1시간 스로틀로 갱신한다(매 요청 UPDATE 를 인증 경로에 얹지 않는다).
- 만료된 토큰은 바로 지우지 않고 30일 유예(`qeploy.api-token.expired-grace-days`) 뒤 매일 03:00 정리에서 삭제한다(#338) — 그동안 목록에 "만료됨"으로 남아 인증이 왜 깨졌는지 알 수 있다.
- 폐기(`DELETE`)는 행을 즉시 삭제한다(소유자 범위 삭제라 남의 토큰 ID는 404).

### 18.4 저장 스키마

`api_tokens`(V60): `user_id`, `token_hash`(UNIQUE), `token_prefix`, `scope`, `label`, `expires_at`, `last_used_at`. 사용자 삭제 시 CASCADE.

---

## 19. Provisioning API (사용자 AWS 계정의 EC2·DB)

백엔드 앱(또는 웹 전용 프론트)을 사용자 AWS 계정의 EC2 에, DB 를 RDS 나 EC2 위 컨테이너에 만든다. 과금 자원은 **항상 승인을 거친다** — 프로젝트 Chat 승인 정책(§6.9)으로 끌 수 없다. 모두 프로젝트에 선택된(§6.6) 클라우드 연결을 쓴다.

### 19.1 엔드포인트 (7개)

| Method | Path | 기능 |
|---|---|---|
| POST | `/api/v1/projects/{projectId}/servers` | EC2 서버 생성 요청(승인 대기 행 + `SERVER_PROVISION` 승인 생성) |
| GET | `/api/v1/projects/{projectId}/servers` | 프로젝트 EC2 서버 목록(최신순, 순수 DB 조회 — 상시 폴링 안전) |
| POST | `/api/v1/servers/{serverId}/terminate` | 서버 종료(멱등, `200` 본문 없음) |
| GET | `/api/v1/servers/{serverId}/logs` | 인스턴스 최근 로그(SSM Run Command — 수 초 걸리는 외부 호출) |
| GET | `/api/v1/projects/{projectId}/databases` | 프로젝트 DB 목록(순수 DB 조회 — 상시 폴링 안전) |
| POST | `/api/v1/projects/{projectId}/databases` | DB 생성 요청 |
| DELETE | `/api/v1/databases/{databaseId}` | DB 삭제(멱등, `204`) |

PAT(§18)로는 이 중 **조회(GET)만** 된다. 쓰기 메서드는 `WRITE` 토큰이어도 `403`(PAT 쓰기 허용 목록 밖).

### 19.2 EC2 서버 생성

```json
{
  "instanceType": "t3.micro",
  "deployMode": "DOCKER",
  "bundledDbEngine": "MYSQL",
  "frontendRepo": null,
  "frontendDir": "frontend",
  "apiPathPrefix": "/api",
  "webOnly": false
}
```

본문은 생략할 수 있다(전부 기본값).

- `instanceType`: 생략 시 `t3.micro`.
- `deployMode`: `NATIVE`(jar 를 `java -jar`, 기본) | `DOCKER`(저장소 Dockerfile 로 이미지 빌드 후 `docker run`). 그 외 값은 `400`.
- `bundledDbEngine`: `MYSQL` | `POSTGRESQL`. 같은 EC2 에 DB 컨테이너를 compose 로 함께 띄운다. `DOCKER` 에서만 쓸 수 있다.
- `frontendRepo`(별도 프론트 저장소 `owner/repo`) / `frontendDir`(하위 폴더): 하나라도 있으면 같은 EC2 에 nginx 프론트 컨테이너를 함께 띄운다. `apiPathPrefix`(기본 `/api`, 콤마로 여러 개)만 백엔드로 프록시한다. `DOCKER` 에서만 쓸 수 있다.
- `webOnly=true`: 백엔드 없이 프론트 nginx 만 띄운다(독립 프론트 EC2). 프론트 소스가 필요하고 번들 DB 는 쓸 수 없다. `POST /deployments` 의 `frontendHostingType=EC2`(§10.1)가 이 경로를 쓴다.

응답(`200`): `{ "requiresApproval": true, "serverId": 42, "approvalIds": [101] }` — 항상 승인 대기다.

오류:

- `404`: 프로젝트에 선택된 클라우드 연결이 없거나 요청자 소유가 아님
- `409`: 연결이 `CONNECTED` 가 아님 / `webOnly` 인데 프론트 소스 없음 / `webOnly` + 번들 DB / 번들 DB·프론트 컨테이너인데 `deployMode≠DOCKER`
- `400`: `deployMode`·`bundledDbEngine` 미지원 값

승인 이후:

- `POST /approvals/{approvalId}/approve`(standalone 승인, `taskId=null`): 연결이 `CONNECTED` 인지 다시 확인하고 `QUEUED` 로 넘긴다. 실제 빌드·인스턴스 생성은 배포 worker 가 트랜잭션 밖에서 한다. 이때 연결이 해제됐거나 `CONNECTED` 가 아니면 `409` 이고 승인 상태도 함께 롤백된다.
- `POST /approvals/{approvalId}/reject`: AWS 자원을 만들기 전이라 대기 행만 `FAILED`(`errorCode=null`)로 닫는다.
- 같은 종류(`webOnly` 같음)의 진행 중 서버가 있는 상태에서 다시 요청하면 **재배포**다. 새 서버가 `RUNNING` 이 되면 옛 서버의 EIP 를 새 인스턴스로 옮기고 옛 서버를 종료한다(블루그린). IP 가 그대로라 도메인·인증서는 건드리지 않는다.
- 대화의 `BACKEND_DEPLOY` 스텝도 같은 서비스로 대기 행과 승인을 만든다(승인 카드가 그 대화에 뜬다).

### 19.3 서버 목록 응답(`ServerResponse`)

| 필드 | 설명 |
|---|---|
| `serverId`, `projectId`, `instanceType`, `instanceId` | |
| `status` | `PENDING`(승인 대기) → `QUEUED` → `BUILDING` → `PROVISIONING` → `RUNNING`, 또는 `FAILED` / `TERMINATED`. 전이 상태(`QUEUED`·`BUILDING`·`PROVISIONING`)면 폴링, 종료 상태(`RUNNING`·`FAILED`·`TERMINATED`)면 정지 |
| `webOnly` | 독립 프론트 서버면 `true` |
| `host`, `port` | EIP, 앱 포트(8080 고정) |
| `url` | `RUNNING` 일 때만 `http://{host}:{port}` |
| `domainUrl` | 이 서버 종류(백엔드/프론트)에 연결된 도메인이 있고 `RUNNING` 일 때만 `https://{hostname}` — Caddy 가 443 을 종단 |
| `errorCode` | `IAM_PERMISSION` · `QUOTA_EXCEEDED` · `ENGINE_UNSUPPORTED` · `PROVIDER_ERROR` · `INVALID_CREDENTIAL`. 사용자 거부는 `null` |
| `errorMessage` | 실패 상세 |
| `healthy`, `lastHealthCheckAt` | `RUNNING` 이후 주기 TCP 헬스체크. `false` 면 인스턴스는 살아 있으나 앱 무응답 |
| `recoveryAttemptedAt` | 무응답 감지 후 자동 재시작을 시도한 시각(회복되면 `null`) |
| `hasBootDiagnostics` | 부트 타임아웃으로 실패하며 부트 로그를 보존했는지 |
| `createdAt`, `updatedAt` | |

`INVALID_CREDENTIAL`(#429)은 클라우드 연결의 자격이 만료/무효라는 뜻이다. 권한 부족(`IAM_PERMISSION`)과 달리 정책이 아니라 **자격을 다시 등록**해야 한다. 이때 연결 상태도 `INVALID_CREDENTIAL` 로 바뀐다(§12).

### 19.4 서버 종료·로그, TLS 확인

- `terminate`: 인스턴스 종료 후 EIP release, 그 IP 를 가리키던 도메인의 Cloudflare 레코드 삭제, SSM 파라미터·S3/ECR 아티팩트를 정리한다. 인스턴스 종료 외에는 best-effort 다. 이미 `TERMINATED` 면 그냥 지나간다. 남의 서버는 `404`.
- `logs?source=`: `APP`(기본) · `BOOT`(cloud-init, "왜 안 떴나") · `CADDY`(HTTPS). 최근 약 200줄. `RUNNING`·`PROVISIONING` 서버만 조회되고 그 외는 `409` 다. 예외는 `source=BOOT` 이면서 보존된 부트 로그가 있는 경우로, 종료된 서버도 보존본을 돌려준다. 인스턴스가 아직 없어도 `409`.
- `GET /api/v1/tls/allow?domain={hostname}`(**인증 불필요**, `DomainTlsController`): EC2 인스턴스의 Caddy 가 on-demand TLS 인증서를 받기 전에 묻는 게이트다. 등록된 EC2 도메인(`hostingTarget` 이 `AWS`·`AWS_EC2_FRONTEND`)이면 `200`, 아니면 `404`(본문 없음).

### 19.5 DB 생성

```json
{ "method": "RDS", "engine": "POSTGRESQL" }
```

- `method`: `LOCAL`(실행 중인 프리뷰 컨테이너 옆 DB 컨테이너, 테스트용·무과금) | `RDS` | `DOCKER`(사용자 AWS EC2 위 DB 컨테이너). `engine`: `POSTGRESQL` | `MYSQL`. 둘 다 필수(`400`).
- 응답(`200`)은 method 와 무관하게 같은 형태다: `{ requiresApproval, database, taskId, approvalIds }`.
  - `LOCAL`: 즉시 만든다. `requiresApproval=false` 이고 `database` 에 접속정보와 **`password`(이 응답에서 1회만)**, `expiresAt`(기본 30분, `qeploy.provisioning.local-ttl`)이 실린다. ACTIVE 프리뷰가 없으면 `404`.
  - `RDS`/`DOCKER`: 대기 행과 `DATABASE_PROVISION` standalone 승인을 만든다. `requiresApproval=true`, `approvalIds` 채움, `database=null`. 선택된 클라우드 연결이 없으면 `404`, `CONNECTED` 가 아니면 `409`.
- 승인하면 생성을 시작해 `PROVISIONING`, 상태 worker 가 `READY` 로 올린다. 거절하면 `FAILED`(`errorCode=null`).

목록(`ProvisionedDatabaseResponse`): `databaseId`, `projectId`, `method`, `engine`, `origin`(`MANUAL` | `PREVIEW_AUTO` — 서버형 프리뷰가 자동 마련), `status`(`PENDING`·`PROVISIONING` 은 폴링 / `READY`·`FAILED`·`EXPIRED` 는 정지), `host`·`port`(`READY` 전 `null`), `database`, `username`, `expiresAt`(LOCAL 만), `errorCode`(열린 문자열), `errorMessage`, `createdAt`, `updatedAt`. **조회 응답에는 `password` 가 없다.** 시각은 오프셋을 포함한다(`+09:00`).

`DELETE`: RDS 는 생성에 쓴 연결로 인스턴스를 삭제하고 DOCKER 는 EC2 를 정리한다. LOCAL 은 컨테이너를 정리한다. 끝나면 `EXPIRED`. 이미 `EXPIRED` 면 그냥 지나간다. 남의 DB 는 `404`.
