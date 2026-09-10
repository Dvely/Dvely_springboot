# Qeploy 백엔드 API 명세

> 기준일: 2026-08-15 · 기준 커밋: 113abf0 · 코드 실측 기반

## 1. 문서 기준

- 기준일: 2026-08-15
- 기준 커밋: `113abf0` (PR #104 프리뷰 게이트웨이 인가 반영, main)
- 기준 코드: `src/main/java/com/example/dvely/**/presentation/*Controller.java`
- 현재 공개 엔드포인트: 94개, 직전 기준(PR #96 시점) 93개에서 +1 — Issue #77(게이트웨이 인가)이 `POST /api/v1/preview-sessions/{sessionId}/access`(프리뷰 열람 권한 발급)를 신규 추가했다(§13.4 참고). 게이트웨이 프록시는 이제 소유권 쿠키 없이는 **401**을 반환한다
  - 재집계 명령: `grep -rE "@GetMapping|@PostMapping|@PutMapping|@PatchMapping|@DeleteMapping|@RequestMapping\(" src/main/java --include="*Controller.java"` 후 클래스 레벨 `@RequestMapping` 6개(Agent/Auth/PreviewSession/Project/User/Webhook) 제외
- 이 문서는 현재 코드에 존재하는 API와 PRD 달성을 위해 앞으로 필요한 API를 구분한다.
- 아래의 `예정 API`는 설계 후보이며 아직 호출 가능한 계약이 아니다.

---

## 2. 공통 규칙

### 2.1 Prefix와 인증

- 기본 prefix는 `/api/v1`이다.
- 공개 API를 제외한 요청은 `Authorization: Bearer {accessToken}`이 필요하다.
- Spring Security가 JWT의 사용자 ID를 `@AuthenticationPrincipal Long userId`로 전달한다.

인증 없이 접근 가능한 경로:

- `GET /api/v1/auth/github/url`
- `GET /api/v1/auth/github/callback`
- `GET /api/v1/auth/github/app/callback`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/webhook/github`
- Swagger/OpenAPI 경로

### 2.2 응답 형식

현재 응답 형식은 통일되어 있지 않다.

- Auth, User: `ApiResponse<T>`
- Project, Chat, Agent, Deployment, DomainBinding, CloudConnection, Approval, Change, PreviewSession, Environment, AuditLog: DTO 직접 반환
- 삭제: 주로 `204 No Content`
- Agent 상태 조회/입력: `ResponseEntity`
- 일반 JSON 성공 응답은 `RawApiResponse` 처리를 거치지 않는 한 공통 MVC advice가 `status/code/message/data` envelope로 자동 변환한다. `@RawApiResponse`가 붙은 Agent/Auth/User/Webhook/PreviewGateway 컨트롤러는 원본 응답을 유지한다.

### 2.3 공통 오류

`GlobalExceptionHandler`가 처리하는 주요 오류:

- `400 Bad Request`: 입력값 또는 현재 상태가 요청과 맞지 않음
- `401 Unauthorized`: 인증 실패
- `403 Forbidden`: 권한 또는 선행 조건 부족
- `404 Not Found`: 사용자 소유 리소스 또는 대상 리소스 없음
- `409 Conflict`: 중복 생성 등 상태 충돌 (예: 동일 (project, scope, key) 환경변수 재생성)
- `500 Internal Server Error`: 처리되지 않은 외부 연동/서버 오류

### 2.4 현재 모듈

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
- `githubAppTokenExpired`
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

현재 delivery 상태 조회용 공개 API는 없으며 운영 DB에 처리 기록을 보존한다.

---

## 6. Project API

Base path: `/api/v1/projects`

| Method | Path | 기능 |
|---|---|---|
| POST | `/api/v1/projects` | GitHub 저장소 없이 DRAFT 프로젝트 생성, 초기 코드 생성 CODE Agent task 제출 |
| POST | `/api/v1/projects/{projectId}/repository` | 새 저장소 생성 또는 기존 저장소 연결 |
| DELETE | `/api/v1/projects/{projectId}/repository` | GitHub 저장소 연결 해제(비파괴, GitHub 호출 없음) |
| GET | `/api/v1/projects/github/repositories` | 접근 가능한 GitHub 저장소 목록 조회 |
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

### 6.5 Overview 통합 데이터

- `currentUrl`: 연결된 DomainBinding을 우선하고 없으면 최신 LIVE 배포 URL 사용
- `deployStatus`: 가장 최근 Deployment 상태
- `currentVersion`: 가장 최근 LIVE Deployment 버전
- `recentChanges`: Deployment, Change, Approval, Domain의 최근 이벤트 3개
- `latestCommit`: GitHub 최신 커밋과 상대시간
- `repositoryHealth`: 연결 저장소 health
- `domainSummary`: 현재 우선 DomainBinding의 hostname, URL, 유형, 상태
- `cloudSummary`: 프로젝트에 선택된 CloudConnection과 실제 검증 상태
- `operationActions`: 배포, 도메인, cloud, AI Workspace, 설정, 제거 조치

트래픽/분석 기능은 PRD 현재 범위에서 제외되어 `trafficSummary`를 응답하지 않는다.

### 6.6 Infrastructure 설정

- 프로젝트당 하나의 CloudConnection만 선택할 수 있다.
- 실제 검증 상태가 `CONNECTED`인 connection만 선택 가능하다.
- 이 설정은 "어떤 연결을 쓸지" 선택만 담당하며, 실제 AWS/GCP 배포·운영 실행과는 아직 연결되어 있지 않다(§12 참고).

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

- 이 설정은 "적용하고 싶은 desired state"를 저장할 뿐이다. 실제 AWS/GCP 리소스 프로비저닝과는 아직 연결되어 있지 않다(EPIC 15 Cloud-Ops 후속). Cloud Ops Agent(§9.4)의 `STATUS_CHECK` 응답도 매번 이 사실을 명시한다.

### 6.8 Infrastructure 비용 추정 + 예산 (Cost & Budget)

`GET /{projectId}/settings/cost-budget` 응답 예:

```json
{
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
| `domainApprovalRequired` | DOMAIN_BIND(도메인 연결/해제) 실행 전 |
| `infraApprovalRequired` | INFRA_OPERATE 중 영향 있는 작업(인프라 설정 변경, Cloud Ops `RESTART`) 실행 전 |
| `resultApprovalRequired` | CODE 실행 **후**, preview→main 반영(RESULT 승인, §9.5) 전 |

- `changeApprovalRequired`~`infraApprovalRequired` 4개는 `PATCH`에서 전체 문서 `PUT`과 동일하게 필수다(부분 수정 불가, 4개 모두 매번 지정).
- `resultApprovalRequired`만 예외적으로 nullable이다 — `null`이면 현재 값을 유지한다(이 필드를 모르는 구버전 FE 요청도 그대로 동작하도록 하는 하위호환 설계).
- 모든 정책은 기본값 `true`(가장 안전한 fail-safe)다.

---

## 7. Environment API

Base path: `/api/v1/projects/{projectId}/environment-variables`

| Method | Path | 기능 |
|---|---|---|
| GET | `/api/v1/projects/{projectId}/environment-variables` | 환경변수 목록 조회(`scope` 쿼리로 필터, scope asc → key asc 정렬) |
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

### 7.2 수정

```json
{
  "value": "https://api2.example.com",
  "secret": true
}
```

- `key`/`scope`는 불변이다(이름을 바꾸려면 삭제 후 재생성).
- `secret`은 `true → false`로 되돌릴 수 없다(`400`).

### 7.3 Secret 마스킹 규칙

- 값은 `secret` 여부와 무관하게 항상 AES-256-GCM으로 전체 암호화되어 저장된다.
- 응답에서 `secret=true`인 변수의 `value`는 목록/생성/수정 어디에서도 `null`이다. `secret=false`인 변수는 복호화된 평문이 반환된다.
- 변경 이력(`/history`)에는 값 자체를 저장하지 않는다. `action`(`CREATED`/`UPDATED`/`DELETED`)과 `valueChanged`(불리언)만 기록해, 이력에서 secret 값을 유추할 수 있는 경로를 차단한다.

### 7.4 현재 한계

- `EnvironmentValueResolver`는 애플리케이션 내부 port로만 존재하며 HTTP로 노출되지 않는다.
- Docker Preview 컨테이너나 Deployment(GitHub Actions) workflow에 이 값을 실제로 주입하는 연결은 아직 없다(§ROADMAP 참고, `state.md` §3.5).

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
| GET | `/api/v1/conversations/{conversationId}/messages` | 메시지 목록 |
| POST | `/api/v1/conversations/{conversationId}/messages` | USER 메시지 저장 후 Agent task 제출 |

메시지 생성:

```json
{
  "content": "헤더 크기를 줄여줘"
}
```

현재 동작:

- USER 메시지를 저장한 뒤 conversation 전체 메시지를 Decision Agent context로 전달한다.
- 승인 대기, 질문, 결과, 오류는 ASSISTANT 메시지로 저장한다.
- 생성된 task는 conversation/project/owner 문맥을 보관한다.
- 첫 사용자 메시지를 기반으로 대화 제목을 자동 생성하고 80자로 제한한다(`Conversation.MAX_AUTO_TITLE_LENGTH`).
- 메시지 저장/조회 응답(`MessageResponse`)에 nullable `taskId`가 포함된다. 큐잉이 정상 접수된 경우에만 값이 있고(승인 정책에 따라 즉시 실행/승인 대기 모두 포함), 판단 자체가 실패했거나 `GET messages` 조회에서는 `null`이다. 값이 있으면 `GET /api/v1/agent/tasks/{taskId}`로 진행 상황을 폴링할 수 있다.

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
| GET | `/tasks/{taskId}` | task 상태 폴링 |
| POST | `/tasks/{taskId}/input` | `WAITING_INPUT` task에 값 제출 |
| DELETE | `/tasks/{taskId}` | 소유한 대기/실행 task 취소 |
| DELETE | `/session` | 현재 사용자의 PreviewSession과 Docker 컨테이너 종료 |

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

응답 주요 필드:

- `steps`: `CODE`, `DEPLOY`, `DOMAIN_BIND`, `CHAT`, `INFRA_OPERATE`
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

- `previewUrl`
- `summary`
- `error`
- `question`
- `failureLog`
- `suggestedFix`
- `attempt`
- `maxAttempts`
- `retryable`: `POST /tasks/{taskId}/retry` 호출이 실제로 성공할지 여부(Issue #57). `true`가 되려면 `pendingApprovalId`가 `null`(대기 중인 승인 없음)**이면서** `attempt < maxAttempts`여야 한다. 이 두 조건은 `/retry`가 실제로 검사하는 것과 동일한 기준(`AgentOrchestrator.findPendingApprovalId`를 응답 조립과 `/retry` 게이트가 함께 사용)이라, 응답의 `retryable`과 실제 `/retry` 결과가 서로 어긋나지 않는다
- `pendingApprovalId` (nullable): 이 task에 걸린 `PENDING` 승인의 ID(예: `BuildFailureRecoveryService`가 만드는 "자동 수정 및 재build" 승인). 값이 있으면 `retryable`은 항상 `false`이며, `/retry`를 호출해도 `409`로 거부된다 — 먼저 `POST /approvals/{pendingApprovalId}/approve`로 그 승인을 처리해야 한다(승인 자체가 재실행을 트리거함). `null`이면 승인 대기가 없다는 뜻이며, 그 경우에만 `attempt < maxAttempts` 여부로 `retryable`이 결정된다

추가 task API:

| Method | Path | 기능 |
|---|---|---|
| GET | `/api/v1/agent/tasks/{taskId}/events` | event ID 기반 polling |
| GET | `/api/v1/agent/tasks/{taskId}/events/stream` | 영속 이벤트 SSE |
| POST | `/api/v1/agent/tasks/{taskId}/retry` | 실패 task 수동 재시도. `pendingApprovalId`가 non-null이면 항상 `409`(먼저 그 승인을 처리해야 함). `retryable:true`일 때만 호출할 것 |

### 9.3 실제 Agent 동작

- Decision Agent: LLM으로 다중 intent를 분류
- Code Agent: Docker `node:20-alpine`에서 파일 생성/수정, npm build, 정적 preview 실행
- Deploy Agent: 승인 완료 후 요청 단위 commit을 `preview` 브랜치에 non-force push하고 PR/merge/tag/workflow 배포 실행
- Domain Agent: 관리형 서브도메인 또는 커스텀 도메인 연결
- Chat Agent(`ChatAgentService`): Decision Agent가 CODE/DEPLOY/DOMAIN_BIND로 분류하지 못한 일반 대화·질문에 LlmRouter 경유로 실제 LLM 응답을 1회 생성해 반환한다(Docker/GitHub 등 인프라를 건드리지 않는다). 프로젝트별 커스텀 지침은 아직 없고 고정 system prompt만 사용한다.
- Cloud Ops Agent(`InfraOpsAgentService`, `AgentType.INFRA_OPERATE`): 서버/서비스 운영을 묻는 자연어 요청을 처리한다. §9.4 참고.

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
| `STATUS_CHECK` | 배포/Preview/클라우드 연결/인프라 설정 상태를 고정 템플릿으로 요약 | 아니오 | 각 항목 독립적으로 degrade, "실 클라우드 리소스는 아직 프로비저닝되지 않았습니다" 문구 항상 포함 |
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
- 신규/미연결 프로젝트(아직 BOUND 아님)는 게이트 대상이 아니다 — 반영할 저장소가 없으므로 기존 DEPLOY step 흐름 그대로 첫 배포를 진행한다.
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

## 10. Deployment API

| Method | Path | 기능 |
|---|---|---|
| POST | `/api/v1/projects/{projectId}/deployments` | GitHub Pages 배포 시작 |
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
- 이미 저장된 분석 결과가 있으면 LLM을 다시 호출하지 않고 그대로 반환한다(멱등).
- 신규 분석은 GitHub Actions 로그 수집 + LLM 호출로 응답까지 약 15~30초가 걸릴 수 있다.
- 로그는 최대 12,000자만 발췌하고, 전송 전에 시크릿으로 보이는 패턴을 정규식으로 레닥션(`***REDACTED***`)한 뒤 LLM에 전달한다.
- LLM 호출은 60초 타임아웃이며, 타임아웃·전송 실패·응답 파싱 실패 시 룰 기반(rule-based) 분석으로 자동 fallback해 항상 응답을 반환한다. 응답의 `source` 필드로 `LLM`/`RULE_BASED`를 구분한다.

`GET /deployments/{deploymentId}/failure-analysis`:

- 저장된 결과만 반환한다(부작용 없음, LLM/GitHub 재호출 없음). 분석을 실행한 적이 없으면 `404`.

`POST /deployments/{deploymentId}/retry`:

- FAILED 상태 배포만 대상이다(아니면 `409`). 요청 본문은 없다.
- `201`을 반환하며, 원본과 동일한 `deployTargetType`(VERSION이면 동일 버전)으로 새 `DeploymentHistory`를 생성한다.
- 새 이력은 `retriedFromHistoryId`로 원본 이력과 연결된다. 원본 이력은 되돌리지 않고 감사 목적으로 그대로 보존된다.
- 재시도는 직접 Deployment API와 동일하게 Approval 없이 즉시 큐잉된다(승인 미적용, 직접 배포와 대칭적인 설계).

---

## 11. DomainBinding API

| Method | Path | 기능 |
|---|---|---|
| GET | `/api/v1/domain-search?keyword={label}` | 지원 중인 관리형 도메인 후보 조회 |
| GET | `/api/v1/projects/{projectId}/domains` | 프로젝트 도메인 목록 |
| POST | `/api/v1/projects/{projectId}/domains` | 도메인 연결 Agent task 제출 |
| GET | `/api/v1/domains/{domainId}` | 도메인 상태 조회 |
| GET | `/api/v1/domains/{domainId}/verification-guide` | DNS 레코드 가이드 |
| POST | `/api/v1/domains/{domainId}/verification-checks` | DNS, hosting, 인증서, HTTPS 재검증 |
| DELETE | `/api/v1/domains/{domainId}` | 도메인 연결 해제 Agent task 제출 |

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

현재 동작:

- 연결/해제 요청은 `202 Accepted`와 `taskId`, 상태, approval ID 목록 반환
- Domain Approval 정책이 켜져 있으면 승인 완료 후 Agent가 실행
- 관리형: Cloudflare CNAME 생성, 선택 hosting adapter 설정
- 커스텀: 선택 hosting adapter 설정, DNS 가이드 제공, A/CNAME 확인
- GitHub Pages: custom domain, 인증서 상태/만료일, HTTPS 강제 적용 상태 조회
- GitHub Pages 인증서 활성화 후 HTTPS 강제 적용 자동 활성화
- 삭제: hosting adapter에서 custom domain 해제, 관리형이면 Cloudflare record도 삭제
- Domain 응답과 Overview에 `hostingTarget`, `httpsEnforced`, `certificateStatus`, `certificateExpiresAt` 포함

현재 한계:

- Cloudflare 기본값과 profile 설정은 `qeploy.com`이다.
- Agent prompt와 사용자 안내 예시는 `qeploy.com`으로 통일되어 있다.
- 구매형 도메인은 registrar 연동 전까지 검색 결과와 연결 API에서 제외한다.
- AWS/GCP target은 계약에 포함되지만 실제 cloud 배포 adapter 추가 전까지 명시적으로 거절한다.
- www/apex redirect 정책은 아직 없다.

---

## 12. CloudConnection API

| Method | Path | 기능 |
|---|---|---|
| GET | `/api/v1/cloud-connections` | 내 AWS/GCP 연결 목록 |
| POST | `/api/v1/cloud-connections` | 클라우드 연결 정보 등록 |
| GET | `/api/v1/cloud-connections/{cloudConnectionId}` | 연결 상세 |
| GET | `/api/v1/cloud-connections/{cloudConnectionId}/health` | 저장된 실제 검증 결과 조회 |
| POST | `/api/v1/cloud-connections/{cloudConnectionId}/verification-jobs` | 실제 권한 검증 Job 발급(재검증) |
| GET | `/api/v1/cloud-connection-verification-jobs/{jobId}` | 검증 Job 상태 조회 |
| DELETE | `/api/v1/cloud-connections/{cloudConnectionId}` | 연결 삭제 |

지원 credential:

- AWS `ACCESS_KEY`
- AWS `ROLE_ARN`
- GCP `SERVICE_ACCOUNT_KEY`
- GCP `SERVICE_ACCOUNT_EMAIL`

민감정보 저장:

- AWS secret access key
- AWS session token
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

현재 한계:

- CloudConnection과 실제 AWS/GCP 배포 실행이 연결되어 있지 않다(선택 및 설정 저장/비용 추정/자연어 운영 질의까지는 가능하지만, 실제 리소스 프로비저닝은 없다). 인프라 설정 저장(§6.7), 비용 추정(§6.8), 운영 질의(§9.4)는 모두 구현되어 있다.
- 실제 AWS/GCP 배포(IaC plan/apply)와 오토스케일링/로드밸런서/DB 설정 API는 없다.

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

이 흐름에서 메시지 저장 직후 conversation 전체 문맥이 Decision Agent에 전달되고, 필요한 승인을 거쳐 Agent가 실행된다(§8, §9 참고). 승인 대기/질문/결과/오류는 ASSISTANT 메시지로 저장된다.

### 13.3 Agent 직접 호출

```text
POST /agent/decision
GET  /agent/tasks/{taskId}
POST /agent/tasks/{taskId}/input
DELETE /agent/tasks/{taskId}
DELETE /agent/session
```

### 13.4 Preview 운영

```text
GET  /projects/{projectId}/preview-session      # 프로젝트의 현재 프리뷰(없으면 204)
POST /projects/{projectId}/preview-session      # 프리뷰 띄우기(200 연결 / 202 준비 시작 / 409 저장소 미연결 / 503 Docker)
POST /preview-sessions/{sessionId}/access       # 열람 권한 발급 — iframe 표시 직전 필수(404 없음·타유저 / 409 종료·준비중)
GET  /preview-sessions/{sessionId}/status
GET  /preview-sessions/{sessionId}/logs
DELETE /preview-sessions/{sessionId}
```

프리뷰 세션은 두 경로로 만들어진다(Issue #94).

- **작업 결과 프리뷰**: Agent CODE 스텝이 내부적으로 생성(`task_id` 있음). 생성 API 없음, `previewUrl`은 `GET /agent/tasks/{taskId}`에서 얻는다.
- **현재 상태 프리뷰**: 위 `POST`로 사용자가 직접 생성(`task_id` NULL, V31). `preview` 브랜치를 clone → 빌드 → 서빙하며, 준비 중에는 세션 상태가 `PROVISIONING`이고 `previewUrl`은 null이다. 실패하면 `FAILED` + `failureReason`(빌드 로그 꼬리 포함).

`previewUrl`의 기준 오리진은 `qeploy.preview.gateway-base-url`이다(운영 기본값 `https://qeploy.com`, 미설정 시 CORS 허용 오리진에서 유추 — Issue #95). Docker 데몬에 닿지 못하면 500이 아니라 **503 `PREVIEW_ENVIRONMENT_UNAVAILABLE`**로 원인을 적어 응답한다.

게이트웨이(`GET /api/v1/previews/{sessionId}/{accessToken}/**`)는 URL만으로 열리지 않는다(Issue #77). `POST /preview-sessions/{sessionId}/access`가 소유자를 확인하고 `HttpOnly` 소유권 쿠키(`Path=/api/v1/previews/{sessionId}/`)를 `Set-Cookie`로 내려주며, 쿠키 없이 접근하면 **401**이다(`qeploy.preview.require-access-cookie`, 기본 true). 이 호출은 accessToken을 **회전**시키므로 응답 `{ sessionId, previewUrl, expiresAt }`의 `previewUrl`만 유효하고, 이전에 받은 주소(작업 응답의 `previewUrl` 포함)는 즉시 404가 된다.

### 13.5 GitHub Pages 배포

```text
GET  /projects/{projectId}/repository-health
POST /projects/{projectId}/deployments
GET  /deployments/{deploymentId}
GET  /deployments/{deploymentId}/logs
GET  /projects/{projectId}/versions
```

배포가 FAILED로 끝나면 다음 흐름으로 복구한다(§10.2 참고):

```text
POST /deployments/{deploymentId}/failure-analysis
GET  /deployments/{deploymentId}/failure-analysis
POST /deployments/{deploymentId}/retry
```

### 13.6 도메인

```text
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
- `GET /api/v1/changes/{changeId}`
- `GET /api/v1/changes/{changeId}/diff`
- `DELETE /api/v1/preview-sessions/{previewSessionId}`
- `GET /api/v1/preview-sessions/{previewSessionId}/status`
- `GET /api/v1/preview-sessions/{previewSessionId}/logs`
- `GET /api/v1/projects/{projectId}/preview-session` — 프로젝트의 현재 프리뷰(작업 프리뷰/현재 상태 프리뷰 중 최근 것). ACTIVE 세션은 컨테이너 생존을 확인한 뒤에만 반환한다
- `POST /api/v1/projects/{projectId}/preview-session` — 작업 지시 없이 `preview` 브랜치 현재 상태로 프리뷰 생성
- `POST /api/v1/preview-sessions/{previewSessionId}/access` — 열람 권한 발급(소유권 쿠키 + accessToken 회전, Issue #77). iframe 표시 직전 필수

Preview URL은 `/api/v1/previews/{previewSessionId}/{accessToken}/...` gateway 형식으로 발급되며, 오리진은 서버 설정(`qeploy.preview.gateway-base-url`)에서 온다. 게이트웨이는 위 access 발급으로 받은 소유권 쿠키가 없으면 **401**을 반환한다(§13.4 참고).

예정:

- `POST /api/v1/changes/{changeId}/rebuild`

### 14.3 Approval

구현됨:

- `GET /api/v1/projects/{projectId}/approvals`
- `GET /api/v1/approvals/{approvalId}`
- `POST /api/v1/approvals/{approvalId}/approve`
- `POST /api/v1/approvals/{approvalId}/reject`

승인 유형:

- `CHANGE`
- `DEPLOYMENT`
- `DOMAIN_BINDING`
- `INFRA_OPERATION`
- `RESULT` — 결과 승인(Issue #56, §9.5 참고). 다른 4개 타입은 모두 실행 **전** 계획을 승인하지만, `RESULT`는 유일하게 이미 실행된 CODE 결과를 main에 반영할지 승인한다. 항상 Agent task 기반(`taskId` 있음)이며 standalone으로는 생성되지 않는다.

승인 출처는 두 가지다:

- Agent 기반: `approval.taskId`가 채워짐. Agent plan 실행 중 생성된다(§9 참고). `RESULT`는 항상 이 경로다.
- **Standalone**: `approval.taskId = null`. Agent task 없이 API에서 직접 생성된다. `StandaloneApprovalHandler` SPI를 구현하는 도메인(현재는 Project/`InfrastructureChangeApprovalHandler`)이 승인/거절 후속 처리를 소유한다.
- `INFRA_OPERATION` 타입은 두 출처를 모두 가질 수 있는 유일한 승인 유형이다: §6.7의 `PUT .../settings/infrastructure/configuration`이 만드는 승인은 standalone(`taskId=null`)이고, §9.4 Cloud Ops Agent의 `RESTART`가 만드는 승인은 Agent 기반(`taskId` 있음, 일반 Agent plan 승인과 동일 경로)이다. `ApprovalType`은 같지만 `taskId` 유무로 두 흐름을 구분한다.
- 승인/거절 결정에는 동시 요청 경합 방지를 위한 비관적 잠금이 걸려 있다.

### 14.4 Project Settings

구현됨:

- `GET/PATCH /api/v1/projects/{projectId}/settings/chat` — 5개 승인 정책 필드(`resultApprovalRequired` 포함), §6.9 참고
- `GET/PUT/DELETE /api/v1/projects/{projectId}/settings/infrastructure` — CloudConnection 선택(§6.6)
- `GET/PUT /api/v1/projects/{projectId}/settings/infrastructure/configuration` + `GET .../configuration/history` — 인프라 설정 저장(§6.7)
- `GET /api/v1/projects/{projectId}/settings/repository`
- `GET/PUT/DELETE /api/v1/projects/{projectId}/settings/cost-budget` — 비용 추정 + 예산(§6.8)
- `GET/POST/PATCH/DELETE /api/v1/projects/{projectId}/environment-variables`(+ `/history`) — Environment/Secrets 설정, §7 참고

아래 설정 API는 목표 상태다.

- `GET/PATCH /api/v1/projects/{projectId}/settings/general`
- `GET/PATCH /api/v1/projects/{projectId}/settings/release-policy`
- `GET/PATCH /api/v1/projects/{projectId}/settings/deployment`
- `GET/PATCH /api/v1/projects/{projectId}/settings/domain`

### 14.5 AWS/GCP 실제 프로비저닝과 운영

§6.7의 인프라 "설정 저장"(desired state)과 §6.8의 비용 추정(정적 가격표), §9.4의 Cloud Ops Agent(자연어 상태/로그/장애분석/재시작)는 모두 구현되어 있다. 아래는 그 설정을 실제 클라우드 리소스로 만들고 운영하는, 여전히 없는 API다.

- `POST /api/v1/projects/{projectId}/cloud-deployments` — 실제 IaC plan/apply 실행
- `GET /api/v1/projects/{projectId}/infrastructure/status` — 실제 프로비저닝된 리소스 상태 조회(현재 §9.4 STATUS_CHECK는 "프로비저닝되지 않았다"만 알린다)
- `GET /api/v1/projects/{projectId}/infrastructure/logs` — 실제 서버 로그(현재 §9.4 LOG_VIEW는 Preview 컨테이너 로그만 조회한다)
- `POST /api/v1/projects/{projectId}/infrastructure/operations` — `RESOURCE_SCALING`/`AUTOSCALING_CHANGE`/`RESOURCE_CLEANUP` 등 실제 실행(현재 §9.4에서 감지·거부만 구현됨)

### 14.6 Persistent Job

AgentRun 구현:

- `GET /api/v1/agent/tasks/{taskId}`
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

이 엔드포인트는 감사 로그를 **읽기만** 한다. 감사 행을 만들거나 수정·삭제하는 공개 API는 없다 — 기록은 GitHub 저장소 생성/연결/해제/삭제, preview 브랜치 push, RESULT merge, 배포 요청/재시도/성공/실패, 도메인 연결/해제, preview 재시작, 인프라 설정 변경 요청/적용/거절 등 16종 액션(`AuditAction`, `category`는 `GITHUB`/`DEPLOYMENT`/`DOMAIN`/`INFRA` 4종)이 발생할 때 서버 내부 훅에서만 남는다.

- `detail`/`errorSummary`에는 토큰·시크릿·환경변수 값·로그 본문을 저장하지 않는다. `errorSummary`는 저장 전 시크릿 패턴 레닥션(`SecretRedactor`)을 거친다.
- 보관 기간은 기본 180일(`qeploy.audit.retention-days`)이며, 만료 행은 시간당 배치 스케줄러(`qeploy.audit.retention-sweep-interval-ms`, 기본 3600000ms)가 삭제한다. 삭제 결과를 조회하는 API는 없다(애플리케이션 로그로만 확인 가능).
- 감사 기록 자체가 실패해도 원래 요청(레포 연결, 배포 등)은 실패하지 않는다(비차단 계약) — 이 API의 조회 대상에 누락이 생길 수 있는 유일한 경우이며, 그 경우 애플리케이션 로그의 `AUDIT_FALLBACK` 라인에 남는다.

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

### 16.4 미등록 시 동작

키 없이 코딩 에이전트를 요청하면 `400 AI_CREDENTIAL_NOT_REGISTERED`. 서버가 운영자 키로 대신 채우지 않는다 — 사용자를 대신한 결제·재판매·중개는 제공사 약관이 금지한다. FE 는 이 코드를 키 등록 화면 유도에 쓴다.

### 16.5 저장 스키마

`ai_provider_credentials`(V44): `user_id`, `provider`, `encrypted_api_key`, `label`, timestamps. `UNIQUE(user_id, provider)` + 사용자 삭제 시 CASCADE.

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
    "contentHints": [
      { "key": "hero.title", "where": "index.html", "desc": "히어로 대제목 — 한 문장으로 무엇인지" }
    ]
  }
]
```

`demoUrl` 은 **iframe 으로 띄울 수 있다.** GitHub Pages 는 정상 문서에 `X-Frame-Options` 도 `CSP frame-ancestors` 도 보내지 않는다(2026-09-10 실측). 고르기 전에 조작해보게 하는 것이 이 필드의 목적이다.

`contentHints` 는 "어디가 바꿔도 되는 내용인지" 에 대한 템플릿 자신의 선언이다. 없으면 코딩 에이전트가 무엇이 내용이고 무엇이 구조인지 추측한다.

씨앗 tarball 주소(`sourceUrl`)는 **응답에 담지 않는다.** 서버가 컨테이너에 풀 때만 쓰는 내부 경로다.

### 17.2 단건 조회

`GET /api/v1/templates/{templateId}`

목록과 같은 형태의 단건. 없는 ID 는 `404`.

### 17.3 카탈로그를 읽지 못할 때

갱신에 실패해도 **직전에 읽어둔 목록으로 계속 응답한다**(stale-while-error). 카탈로그는 정적 문서라 잠깐 낡은 목록을 보여주는 편이 기능을 멈추는 것보다 낫다. 낡은 것을 쓰는 동안은 WARN 로그가 남는다.

`503 TEMPLATE_CATALOG_UNAVAILABLE` 은 **한 번도 읽지 못한 경우에만** 나간다.
