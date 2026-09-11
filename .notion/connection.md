# Qeploy 모듈 연계 흐름

> 기준일: 2026-08-15 · 기준 커밋: 8ba8c11 · 코드 실측 기반

## 1. 문서 목적

이 문서는 `api.md`의 엔드포인트 목록을 반복하지 않고 다음을 설명한다.

- 현재 코드에서 모듈이 실제로 어떻게 연결되는가
- PRD의 목표 흐름과 현재 흐름은 어디가 다른가
- 앞으로 어떤 연결을 추가해야 전체 사용자 여정이 완성되는가

기준일: 2026-07-18

표기:

- `현재`: 코드로 연결되어 있음
- `부분`: 일부 동작만 연결되어 있음
- `예정`: PRD에는 있으나 코드 연결 없음
- Controller 간 HTTP 호출이 아니라 Application Service/Port 간 호출을 의미한다.

---

## 2. 현재 전체 구조

```text
GitHub OAuth/App
    ↓
Auth ─────────────→ User
                       ↓
Project ──────────→ GitHub Repository (연결/해제, settings/repository)
   ↓                   ↓
Chat              Deployment ──→ GitHub Pages / Actions ──→ 실패 시 원인 분석(LLM+룰) + 재시도
   ↓                   ↑ (정책 ON이면 신규 프로젝트 첫 배포만 자동 merge, §9.3)
Decision Agent    Webhook(workflow_run/push/pull_request/installation)
   ├─→ Code Agent ──→ Docker PreviewSession ──→ Change(diff, PREVIEW_READY)   ← task 단위
   │       │                 ↓
   │       │            Preview 운영 API(상태/로그) + 격리 정책(BI-194)
   │       └─(마지막 CODE step)→ 결과 승인 게이트(§9.3) ──→ preview push → RESULT Approval
   │                                                          → 승인: preview→main merge, Change MERGED
   │                                                          → 거절: Change REJECTED, preview에 누적
   ├─→ Deploy Agent ──→ preview branch → Deployment
   ├─→ Domain Agent ──→ DomainBinding
   ├─→ Chat Agent(ChatAgentService, LLM 응답)
   └─→ Cloud Ops Agent(InfraOpsAgentService) ──→ 상태/로그/장애분석/재시작(§8.6)
        ↓
   Approval (CHANGE/DEPLOYMENT/DOMAIN_BINDING/INFRA_OPERATION/RESULT)
        ↑ Agent 기반(taskId 있음, Cloud Ops RESTART·RESULT 포함) 또는 standalone(taskId=NULL, Infrastructure Configuration 저장)

DomainBinding ──→ Cloudflare + GitHub Pages custom domain

CloudConnection ──→ DB 저장 + 실제 STS/IAM 검증(verification job)
    └─→ Project Infrastructure Settings에서 선택(연결) 가능
        └─→ 인프라 설정(Configuration) 저장 ──→ standalone INFRA_OPERATION Approval
                ├─→ 비용 추정(정적 가격표, 온더플라이) + 예산 평가(§14.3)
                └─ 실제 클라우드 프로비저닝과는 아직 미연결(desired state와 추정치만 저장/계산)

Environment ──→ 프로젝트별 환경변수/Secret CRUD + 이력(암호화 저장)
    └─ EnvironmentValueResolver(port)만 존재, Preview/Deployment 런타임 실주입 미연결

Project Settings(Chat/Infrastructure/Repository/Environment/Infrastructure Configuration/Cost & Budget) ──→ Agent 승인 정책 / CloudConnection 선택 / 저장소 정보 / 환경변수 / 인프라 설정 / 비용·예산

AuditRecorder(횡단) ──→ Project/Deploy Agent/ResultApprovalGate/ResultApprovalService/Deployment/Webhook/DomainBinding/InfraOpsAgent/InfrastructureConfiguration/InfrastructureChangeApprovalHandler(10개 서비스, §17)
    └─→ audit_logs(FK 없음, REQUIRES_NEW 별도 트랜잭션) ──→ GET /projects/{projectId}/audit-logs 조회 API
```

현재 중요한 경계:

- Chat 메시지 저장은 Decision Agent 실행으로 바로 이어진다(더 이상 별도 진입점이 아니다. §7 참고). CHAT으로 분류된 요청은 `ChatAgentService`가, 서버/서비스 운영 질의는 Cloud Ops Agent가 처리한다(§8 참고).
- Agent task, plan, PreviewSession은 DB에 영속화되며 DB queue worker가 lease로 실행을 claim한다(메모리 전용이 아니다. §8 참고).
- PreviewSession은 Agent를 거치지 않는 경로로도 만들어진다(Issue #94): `Project → ProjectPreviewService → Docker PreviewSession(task_id NULL)`. 준비(clone/build/serve)는 `previewExecutor`에서 비동기로 돌고, 절차 자체는 CODE 스텝과 `PreviewWorkspaceService`를 공유한다.
- Deployment는 GitHub Pages만 실행한다.
- CloudConnection은 등록 후 실제 STS/IAM 검증을 거치며, 프로젝트 Infrastructure 설정에서 선택할 수 있다. 다만 실제 배포/운영에는 아직 사용되지 않는다.
- Environment/Secrets는 저장·조회·이력까지는 완비되어 있으나, Preview/Deployment 실행에 실제로 주입되는 연결은 아직 없다(§8.5, §14.2 참고).
- 배포가 실패하면 온디맨드 원인 분석(LLM, 실패 시 룰 기반 fallback)과 승인 없는 재시도가 가능하다(§10.5 참고).
- Approval은 Agent task 기반뿐 아니라 API 직접 생성 standalone 승인도 지원한다. `INFRA_OPERATION` 타입은 두 출처를 모두 가지는 유일한 유형이다(standalone: 인프라 설정 저장, §13.2 / Agent 기반: Cloud Ops RESTART, §8.6).
- Cloud Ops Agent(§8.6)는 새 HTTP 엔드포인트 없이 기존 Chat/Agent 흐름을 확장한다. 실제 프로비저닝된 서버가 없다는 사실을 매 상태 조회마다 명시한다(정직한 전제).
- CODE 작업 결과를 main에 반영(git merge)하는 것과 실제로 배포(Pages 공개)하는 것은 이제 서로 다른 단계다(§9.3, 결과 승인 게이트). merge 권한은 기본적으로 결과 승인에 있으며, 직접 배포 API는 신규 프로젝트의 첫 배포에서만 예외적으로 자동 merge한다.
- Approval, Change, Preview(Gateway/Session/운영 API), Project Settings(Chat/Infrastructure/Repository/Environment/Infrastructure Configuration/Cost & Budget) 모듈은 존재한다. Import와 실제 Cloud 프로비저닝(IaC apply, 실 서버 운영) API는 아직 없다.

---

## 3. 모듈 책임

| 모듈 | 현재 책임 | PRD 목표 대비 |
|---|---|---|
| Auth | GitHub OAuth, GitHub App, JWT/Refresh, logout | 핵심 기반 구현 |
| User | 내 프로필과 GitHub App token 상태 | 핵심 기반 구현 |
| Project | 프로젝트 CRUD, 저장소 연결/해제, Overview, Chat/Infrastructure(선택+설정 저장)/Repository/Cost & Budget 설정 | 부분 구현 |
| Chat | 대화/메시지 저장, 7일 휴지통/복구/자동삭제, Agent 실행 연결, task 폴링용 taskId 노출 | Change/diff와 conversation 직접 연결이 남음 |
| Agent | intent 분류, Code/Deploy/Domain/Chat/InfraOperate 실행, 영속 task/event, Approval 연동(계획 승인 1단계 + 결과 승인 2단계) | 실제 프로비저닝 서버 대상 운영은 아직 없음(정직한 거부까지만 구현) |
| Approval | Change/Deployment/DomainBinding/InfraOperation/**Result** 승인·거절, Agent 기반+standalone 정책 연동 | 핵심 흐름 구현 |
| Change | Code Agent 산출물의 diff/summary 저장·조회, 결과 승인 결과(MERGED/REJECTED) 반영 | rebuild API가 남음 |
| Preview | task 단위 + **프로젝트 단위** Docker PreviewSession(Issue #94), gateway proxy(호스트 포트 loopback 바인딩, BI-081/G1), 상태/로그 조회, 자원·권한 격리, 기준 오리진 해석·Docker 가용성 점검(Issue #95) | websocket/backend preview, 게이트웨이 인가 강화(소유권/JWT, Issue #77), 서빙 중 세션 갱신(`?refresh=true`) 확장이 남음 |
| Environment | 프로젝트별 환경변수/Secret CRUD, scope 분리, 암호화, 이력 | Preview/Deployment 런타임 실주입 연결이 남음 |
| Deployment | preview→main PR/merge, tag, GitHub Pages, 이력/로그, 실패 원인 분석(LLM+룰)+재시도 | GitHub Pages 중심 부분 구현, 취소/직렬화 미구현 |
| Webhook | workflow_run/push/pull_request/installation 반영, delivery 재시도 | 다른 GitHub 이벤트 확장 여지 있음 |
| DomainBinding | 관리형/커스텀 도메인, DNS 검증, Approval 연동 | Pages만 지원, HTTPS www/apex 정책 미구현 |
| CloudConnection | AWS/GCP credential 저장, 실제 STS/IAM 검증, 프로젝트 선택 연결 | 실제 cloud 배포/비용 실집행/운영 미연결(비용 추정·운영 질의 자체는 구현됨) |
| AuditLog | GitHub/배포/도메인/인프라 작업 16종 기록(비차단 REQUIRES_NEW), 프로젝트별 조회 API, 180일 retention 배치 | 계정 수준 감사(installation·로그인 이력)·`/me/audit-logs`는 범위 밖 |
| Template | 템플릿 저장소가 Pages 로 발행한 `catalog.json` 취득·캐시(stale-while-error), 카탈로그 API, 프로젝트 생성 시 `templateType` 실재 검증, 첫 CODE 스텝 씨딩 | FE 갤러리·썸네일 자동화가 남음 |

---

## 4. 인증과 GitHub 권한 흐름

### 4.1 현재 로그인

```text
프론트
→ GET /auth/github/url
→ GitHub OAuth
→ GET /auth/github/callback
→ AuthFacade
→ GithubOAuthPort: code를 OAuth token으로 교환
→ GithubUserPort: 사용자 정보 조회
→ UserRepository: 사용자 저장/갱신
→ TokenPort: 서비스 access/refresh token 발급
→ githubAppInstalled 반환
```

### 4.2 GitHub App 연결

```text
프론트
→ GET /auth/github/app/install-url
→ GitHub App 설치
→ GET /auth/github/app/callback
→ installationId + GitHub App User Token 저장
→ 프론트 redirect
```

만료된 User Token은 다음 흐름으로 갱신한다.

```text
GET /users/me
→ githubAppTokenExpired 확인
→ GET /auth/github/app/reauthorize-url
→ GitHub callback
→ 새 User Token 저장
```

GitHub API를 사용하는 Project, Agent, Deployment, DomainBinding은 저장된 GitHub App User Token을 사용하며, 일부 서비스는 만료 시 `AuthCommandService.refreshGithubUserToken`을 호출한다.

---

## 5. 프로젝트 생성과 저장소 연결

### 5.1 현재 빈 프로젝트 생성

```text
POST /projects
→ ProjectFacade
→ ProjectCommandService
→ ProjectDomainService.create: startMode/templateType/draftMode 검증·정규화
→ TemplateCatalogGuard.ensureExists: 정규화된 templateType 을 카탈로그와 대조(blank 면 no-op)
→ DRAFT + NOT_BOUND 프로젝트 저장
```

프로젝트 생성은 **프로젝트 행만 만든다.** 초기 CODE Agent task 를 함께 제출하던 경로는 제거됐다 — 그 task 는 conversationId 없이 제출돼 아무도 볼 수 없는 승인 뒤에 영구히 남았고, 한 번도 실행된 적이 없다. 첫 코드는 사용자의 첫 요청 때 CodeAgentService 가 만든다.

템플릿 실재 검증이 **도메인이 아니라 애플리케이션 계층**에 있는 이유는 네트워크 호출이기 때문이다. 형식 정규화는 도메인이 끝내고, 정규화된 결과를 카탈로그에 묻는다 — 정규화 전 값으로 물으면 `"E Commerce"` 가 카탈로그의 `e-commerce` 와 어긋난다.

```text
PagesTemplateCatalogClient
→ GET https://dvely.github.io/qeploy-templates/catalog.json  (10분 주기)
→ 실패 시 직전 스냅샷 유지 + WARN (stale-while-error)
→ 한 번도 못 읽었을 때만 503 TEMPLATE_CATALOG_UNAVAILABLE
```

이 시점에는:

- GitHub 저장소를 만들지 않는다.
- Chat을 자동 생성하지 않는다.
- ZIP 처리는 하지 않는다(§5.3 "예정" 참고, `api.md` §14.1도 함께 확인).
- 초기 코드 생성은 위 CODE Agent task를 통해 비동기로 진행된다(더 이상 메타데이터로만 저장되지 않는다).

### 5.2 현재 저장소 연결/해제

```text
POST /projects/{projectId}/repository
→ 프로젝트 소유권 확인
→ create: GitHub 저장소 생성
   또는 existing: GitHub 저장소 접근 확인
→ preview 브랜치 준비
→ sourceRepository 설정
→ deploymentRepository도 같은 저장소로 설정
→ BOUND / HEALTHY 저장, repositoryConnectedAt 기록

DELETE /projects/{projectId}/repository
→ 프로젝트 소유권 확인
→ 저장소 연결 정보만 제거 (GitHub 저장소/workflow/Pages는 그대로 유지, GitHub API 호출 없음)
→ 배포 이력·도메인 연결 등은 별도로 정리하지 않음(자연 단절)
→ 이후 POST /repository로 재연결 가능

GET /projects/{projectId}/settings/repository
→ 연결 정보 + GitHub에서 defaultBranch 실시간 조회
→ 미연결 프로젝트도 200 + connected=false
```

과거 알려진 한계였던 동시 쓰기 lost-update(이 쓰기 경로와 webhook head-sync가 겹치면 나중에 commit한 쪽이 이긴다)는 Issue #45(§5.4 참고)에서 해소했다.

### 5.3 PRD와의 차이

구현됨:

- 프로젝트와 GitHub 저장소를 별도 객체처럼 생성/연결
- 기존 저장소 목록 조회
- 새 저장소 생성과 기존 저장소 연결
- 저장소 연결 해제와 Repository 설정 조회
- 프로젝트 생성 직후 CODE Agent task 제출
- 동시 쓰기 lost-update 해소(Issue #45)

예정:

- ZIP 업로드와 분석
- GitHub 레포 가져오기 확인 단계
- import 진행 상태

### 5.4 Project 동시성 제어 (Issue #45)

`projects.version` 컬럼(V26) + JPA `@Version`으로 낙관적 잠금을 2겹으로 적용한다.

```text
Case A — @Transactional 경로 (ProjectCommandService, WebhookEventHandler.handle, deploy())
→ 트랜잭션 시작 시 읽은 영속성 컨텍스트의 Project 인스턴스를 그대로 재사용
→ save 시 Hibernate가 UPDATE ... WHERE version=?로 flush
→ 다른 트랜잭션이 그 사이 커밋했으면 0건 갱신 → OOLFE

Case B — 무tx 경로 (DeploymentCommandService.execute(), 비동기 worker)
→ 매 저장이 별도 auto-commit 트랜잭션 → findById가 항상 최신 행을 가져옴
→ @Version 검사만으로는 "낡은 스냅샷으로 저장 시도"를 못 잡음(행과 항상 일치)
→ ProjectRepositoryAdapter.save()가 project.getVersion()과 DB 현재 version을 명시적으로 비교
→ 불일치 시 OOLFE를 직접 throw
```

두 경로 모두 같은 `ObjectOptimisticLockingFailureException`을 던지며, `GlobalExceptionHandler`가 이를 `404`가 아닌 `409`로 응답한다(과거엔 `@Version`이 없어 OOLFE = "동시 삭제"였지만, 도입 이후엔 "버전 경합"이 지배적 의미가 되어 404가 부정확해졌다. 삭제 경합도 재조회 시 자연스럽게 404로 수렴한다).

경로별 사후 처리 정책:

- 사용자 직접 요청: `409`를 그대로 반환, 클라이언트 재조회/재시도에 맡긴다.
- 배포 워커(`DeploymentCommandService`): 새 재시도 장치 없이 기존 배포 retry/backoff 머신에 편입시킨다(`handleExecutionFailure`).
- Agent(`DeployAgentService`): 경합 시 인라인 1회 재시도 후, 재실패하면 기존 Agent task 실패 처리로 전파한다.

부가 수정: `DeploymentCommandService.execute()`가 자기 자신이 유발한 webhook과 경합하던 문제(GitHub push → webhook이 먼저 버전을 올려버림)를 저장 직전 재조회로 축소했고, `ProjectRepositoryAdapter.save()`가 동시 삭제된 행을 새로 재삽입하던 버그를 OOLFE로 대체했다.

이 잠금은 Project를 쓰는 모든 경로(직접 API, webhook head-sync, Agent, 배포 워커)에 공통 적용된다. 신규 공개 엔드포인트는 없다.

---

## 6. Projects와 Overview

### 6.1 프로젝트 목록

```text
GET /projects
→ ProjectRepository
→ 삭제되지 않은 사용자 프로젝트를 updatedAt 내림차순 조회
→ deployStatus/currentUrl/상대시간 반환
```

### 6.2 Overview

```text
GET /projects/{projectId}/overview
→ Project 조회
→ 최신 DeploymentHistory, 현재 DomainBinding, 선택 CloudConnection 조회
→ Deployment/Change/Approval/Domain 최근 이벤트 3개 조립
→ GitHub 최근 커밋 조회 + 저장소 health 확인
→ Overview 반환
```

Overview는 Deployment, Change, Approval, DomainBinding, CloudConnection 이력을 실제로 통합 조회한다(2026-06-13 완료, `state.md` §4.7 참고). 남은 차이는 다음과 같다.

```text
Project
└─→ 영속 Activity/Job event를 배포·도메인 이력보다 더 세밀하게 노출
```

---

## 7. Chat 흐름

### 7.1 현재 대화 저장과 Agent 연결

```text
POST /projects/{projectId}/conversations
→ 프로젝트 소유권 확인
→ Conversation 저장

POST /conversations/{conversationId}/messages
→ 대화 소유권 확인
→ USER ChatMessage 저장
→ 첫 메시지면 80자 제한 대화 제목 자동 생성
→ conversation 전체 context 조회
→ Decision Agent plan 생성
→ owner/project/conversation이 연결된 task 생성
→ 필요한 Approval 생성 또는 즉시 실행
→ 응답의 taskId(nullable)로 진행 상황 폴링 가능
```

Chat과 Agent는 별도 진입점이 아니다. 메시지 저장 직후 같은 요청 흐름 안에서 Decision Agent 실행까지 이어진다.

### 7.2 현재 삭제/복구

```text
DELETE /conversations/{conversationId}
→ soft delete
→ 계정 휴지통에서 조회 가능

POST /trash/conversations/{conversationId}/restore
→ 7일 보관 기간 확인(ChatTrashPolicy.RETENTION_DAYS = 7)
→ 원 프로젝트가 없으면 동일 저장소를 연결한 활성 프로젝트 탐색
→ 복구

DELETE /trash/conversations/{conversationId}
→ 즉시 영구 삭제
→ 메시지 삭제, AgentRun/Approval/Change/PreviewSession의 대화 참조만 해제

ChatTrashCleanupScheduler (기본 1시간 주기)
→ 7일 경과 대화 자동 영구 삭제
```

휴지통 보관 기간은 PRD 기준 7일로 코드와 일치한다(2026-06-12 완료, `state.md` §4.6 참고). 과거 문서에 있던 "30일" 표기는 오기이며 이번 개정으로 수정했다.

### 7.3 Agent와 Assistant 메시지 연결

```text
사용자 메시지 저장
→ Decision Agent
→ 승인 필요 시 WAITING_APPROVAL
→ 승인 대기 내용을 Assistant 메시지로 저장
→ 모든 승인 완료 후 Agent 실행
→ 질문/결과/오류를 Assistant 메시지로 저장
```

남은 연결:

- Change/diff와 conversation의 직접 연결(현재는 project 단위로 조회)
- 프로젝트별 Chat 지침(커스텀 system prompt)과 응답 상세도 조절

---

## 8. Agent와 Docker Preview

### 8.1 현재 Agent 실행

```text
POST /agent/decision
→ DecisionAgentService
→ LLM이 CODE / DEPLOY / DOMAIN_BIND / CHAT / INFRA_OPERATE steps 생성
→ AgentOrchestrator
→ Project/Conversation 소유권 확인
→ ownerUserId/projectId/conversationId가 포함된 UUID task 생성
→ 프로젝트 승인 정책에 따라 Approval 생성
→ 승인이 필요하면 WAITING_APPROVAL
→ 모든 승인 완료 후 QUEUED
→ DB worker가 lease를 획득하고 AgentPlanExecutor 실행
→ step/event/입력/retry 상태를 agent_runs와 agent_run_events에 저장
```

worker의 lease 갱신은 `AgentExecutionRegistry`(Issue #55)에 실제로 제출된 task로만 한정된다 — claim은 했지만 실행자 풀 포화로 dispatch가 거부된 task는 `RETRY_WAIT`로 되돌아가며 lease를 자가 갱신하지 않으므로, 만료 lease 회수(`recoverExpiredLeases`)가 막히는 RUNNING 좀비 상태가 발생하지 않는다.

### 8.2 Code Agent

```text
CODE step
→ task 전용 PreviewSession 조회 또는 생성
→ project/conversation/task label이 있는 Docker container 사용
→ 기존 projectId가 있으면 GitHub 저장소 fetch 후 preview branch checkout
→ Claude/OpenAI tool loop
→ 파일 읽기/쓰기/command 실행
→ npm build
→ 정적 build directory를 npx serve로 실행
→ Change summary와 git diff 저장
→ access token 기반 backend gateway URL 반환
```

PreviewSession은 DB에 container/host port/TTL을 저장하고 task마다 격리된다. gateway 접근 시 TTL이 연장되며 만료 또는 명시적 종료 시 container가 제거된다.

### 8.3 사용자 입력 대기

```text
Deploy/Domain Agent가 값 필요
→ agent_runs 상태 WAITING_INPUT과 질문 저장
→ 소유 사용자가 /agent/tasks/{taskId}/input 호출
→ 입력값 저장 후 QUEUED
→ worker가 완료된 step 다음부터 실행 재개
```

task 상태 조회, 입력, retry, event, 취소는 소유 사용자만 가능하다. `DELETE /agent/tasks/{taskId}`는 task와 pending Approval을 함께 `CANCELLED`로 전환한다.

입력 대기 중 thread를 점유하지 않는다. 실행 중인 외부 호출은 즉시 중단하지 못하지만 step 경계에서 취소 상태를 확인한다.

### 8.4 Chat Agent

```text
CHAT step
→ ChatAgentService
→ TaskStore에서 task의 conversationId 조회
→ conversation 히스토리에서 중복 user 턴 제거(Decision Agent의 재작성 instruction과 겹치지 않게)
→ LlmRouter 경유 LLM 1회 호출(system prompt 고정, Qeploy 어시스턴트 역할)
→ 응답을 CodeResult.summary로 반환 → AgentPlanExecutor가 assistant 메시지로 저장
```

Docker/GitHub 등 외부 인프라를 건드리지 않는 순수 응답 step이다. 프로젝트별 커스텀 지침은 아직 연결되어 있지 않다(§7.3 참고).

### 8.5 Preview 운영 API와 컨테이너 격리

```text
GET /preview-sessions/{sessionId}/status
→ PreviewContainerOpsService
→ 소유권 확인
→ Docker stats one-shot 조회(컨테이너 실행 여부/OOM/exit code/리소스 사용량)
→ 종료된 세션도 200 + containerRunning=false로 응답
→ stats 조회 3초 초과 시 resources만 null로 degrade (상태 필드는 정상 반환)

GET /preview-sessions/{sessionId}/logs
→ 소유권 확인
→ Docker stdout/stderr 조회, tail 기본 200([1,2000] 클램프), sinceSeconds는 상대값
→ 로그는 비영속(컨테이너 제거 시 소멸), 컨테이너 제거된 세션은 404 대신 containerRunning=false/logText="" 200
```

컨테이너 생성 시점의 격리·바인딩 정책(BI-194 격리 + BI-081/G1 호스트 포트 바인딩, `DockerContainerService`):

- 메모리 1GiB(스왑도 동일 상한, 추가 스왑 없음), CPU 1.0 vCPU, `pidsLimit` 256
- `capDrop(ALL)` 후 `capAdd(CHOWN, SETUID, SETGID)` 3개만 재부여(npm 라이프사이클 스크립트의 권한 강등/파일 소유권 변경용)
- `no-new-privileges` 보안 옵션
- 전용 브리지 네트워크 `qeploy-preview`(`enable_icc=false`)로 프리뷰 컨테이너 간 통신을 차단(기존 네트워크가 이 옵션 없이 존재하면 경고만 남기고 계속 진행)
- `docker-java` 3.3.6 → 3.7.1: 최신 Docker Engine의 capability 응답 역직렬화 결함 대응, 버전 하한을 회귀 테스트로 고정
- 호스트 포트 바인딩은 `bindIpAndPort("127.0.0.1", 0)`(BI-081/G1, Issue #76/PR #78) — 컨테이너 3000 포트를 loopback에만 퍼블리시해 게이트웨이·accessToken·Spring Security를 우회하는 호스트 직접 접근을 차단한다. 게이트웨이(`PreviewGatewayService`)가 이미 `127.0.0.1:hostPort`로만 프록시하므로 정상 경로는 무영향이며, 이 바인딩은 게이트웨이와 Docker 데몬이 동일 호스트라는 기존 전제를 코드로 강제한다(멀티호스트/원격 Docker 전환 시 재검토 필요)

남은 항목: 게이트웨이 인가 강화(소유권·JWT 미검증 + `permitAll` — Issue #77, 무헤더 accessToken이 iframe 임베딩을 위한 의도된 설계라 FE 조율 후 착수), dependency/build/image cache.

### 8.6 Cloud Ops Agent

```text
INFRA_OPERATE step (operation="STATUS_CHECK" 등)
→ AgentOrchestrator
→ InfraOperation.parse(operation)으로 화이트리스트 조회 — 실패하면 IDENTIFIED 안내 응답, 예외 아님
→ approvalRequired()가 true인 작업(RESTART)만 Approval 생성 (ApprovalType.INFRA_OPERATION, taskId 있음 — Agent 기반)
→ 승인 불필요/이미 승인 완료 → QUEUED → worker → InfraOpsAgentService.execute()
→ (projectId, ownerUserId)로 대상(DeploymentHistory/PreviewSession)을 서버가 직접 재조회 — LLM이 준 값은 operation 이름뿐
```

operation별 실행:

```text
STATUS_CHECK → 배포/Preview/클라우드연결/인프라설정 4개 항목을 각각 독립 조회해 고정 템플릿으로 조립 (한 항목 실패해도 "확인 불가"만 표시, 전체 실패 아님)
              → "실 클라우드 리소스는 아직 프로비저닝되지 않았습니다" 고정 문구 항상 포함
LOG_VIEW      → ACTIVE PreviewSession의 Docker 컨테이너 stdout/stderr, tail 50줄/2000자로 축약
FAILURE_ANALYSIS → DeploymentFailureAnalysisService(§10.5) 재사용, 신규 구현 없음
RESTART       → ACTIVE PreviewSession 재조회 → Docker 컨테이너 재시작 → 승인 필요 시 위 흐름을 통해서만 도달
RESOURCE_SCALING/AUTOSCALING_CHANGE/RESOURCE_CLEANUP → InfraOperation.supported=false → 승인 생성 없이 즉시 감지·설명 후 거부
```

`INFRA_OPERATION` `ApprovalType`은 U7(§14.2)의 standalone 승인과 여기 Cloud Ops Agent의 Agent 기반 승인, 두 출처를 모두 가진다. `ApprovalType`은 같지만 `approval.taskId`(있음/없음)로 두 흐름이 구분되며, 승인/거절 시 실행 경로는 서로 다르다(전자는 `InfrastructureChangeApprovalHandler` standalone 콜백, 후자는 일반 Agent plan 재개).

---

### 8.x 템플릿 씨딩 (Issue #318)

```text
CodeAgentService.execute
→ previewSessionService.acquire(taskId)            컨테이너 확보
→ previewWorkspaceService.prepareProject(...)      저장소가 있으면 clone/pull
→ TemplateSeedingService.seedIfNeeded(...)         비어 있을 때만 씨앗을 푼다
    · startMode != template     → 건너뜀
    · /workspace/app 이 비지 않음 → 건너뜀 (두 번째 요청 · clone 해온 저장소)
    · wget -qO- <sourceUrl> | tar -xz -C /workspace/app
    · 실패하면 예외 — 조용히 백지 생성으로 넘어가지 않는다
→ 씨딩됐으면 지시문 앞에 템플릿 맥락을 붙인다
    "이미 깔려 있다 · 스캐폴더를 돌리지 마라 · contentHints 가 '내용' 이다"
→ LLM 루프
```

**순서가 중요하다.** clone 이 씨딩보다 먼저다 — 반대면 clone 이 씨앗을 덮거나, 비어 있지 않은 디렉터리에 clone 하려다 실패한다. 씨딩이 "비었을 때만" 이므로 저장소가 있는 프로젝트에서는 자연히 건너뛴다.

`sourceUrl` 은 셸 명령에 들어간다. 카탈로그는 우리 저장소가 발행하지만 그 값을 그대로 셸에 넘기는 구조를 두지 않는다 — 형식(`https://…​.tar.gz`, 안전 문자만)을 먼저 검증한다.

---

## 9. 코드 변경과 Git 흐름

### 9.1 Direct Deployment API의 현재 흐름

```text
POST /projects/{projectId}/deployments (LATEST)
→ preview와 main 비교
→ 새 커밋이 있으면 preview→main PR 생성/조회
→ PR merge
→ main HEAD에 순차 tag 생성
→ workflow 생성/갱신
→ GitHub Pages 설정
→ Actions 실행
```

이 경로는 PRD의 preview→PR→merge 방향과 가장 가깝다.

### 9.2 Agent Deploy 경로의 현재 흐름

CODE 이후 DEPLOY step:

```text
Docker /workspace/app
→ git add/commit
→ 요청 ID 단위 commit
→ git push origin preview
→ 승인 완료된 plan이면 DeploymentCommandService 실행
→ preview→main PR/merge
→ tag/workflow 배포
```

현재 보완된 점:

- `main --force` push를 제거했다.
- 승인 전에는 코드 변경/production 배포를 실행하지 않는다.
- 프로젝트별 승인 정책을 비활성화한 작업만 승인 없이 실행한다.
- 기존 프로젝트 작업은 remote `preview`를 기준으로 시작한다.

남은 문제:

- 외부 GitHub/Docker 동작의 idempotency를 더 강화해야 한다.
- 직접 Deployment API는 정책이 꺼져 있을 때만 Approval 없이 PR merge와 배포를 실행한다(정책 ON일 때의 동작은 §9.3 참고).

과거 "목표"였던 흐름(Code Agent → preview commit → Change/diff 저장 → CHANGE Approval → DEPLOYMENT Approval → merge/tag/workflow)은 §9.3의 결과 승인 게이트로 그 취지("실행 결과를 확인한 뒤에만 main에 반영")가 실질적으로 달성되었다. 다만 정확히 같은 형태는 아니다 — CHANGE Approval은 여전히 **실행 전** 계획 승인이고(정책이 켜져 있으면 CODE 실행 자체를 막음), RESULT 승인은 **실행 후** preview 결과를 보고 반영 여부를 결정하는 별도 게이트다. 두 승인은 함께 켜둘 수 있다(계획 승인 → 실행 → 결과 승인 → 반영).

### 9.3 결과 승인 2단계 게이트 (Issue #56)

제품 모델(4단계, `z-result-approval-design.md` D1/D2):

```text
[컨테이너/preview 서버] ──①──▶ [git preview 브랜치] ──②──▶ [git main] ──③──▶ [GitHub Pages 공개]
   "만들어진 것"                "기록된 것"           "제품으로 수용된 것"   "공개된 것"
                                             ↑                      ↑
                                       결과 승인(RESULT)         배포 승인/배포 요청
```

② git 반영과 ③ Pages 배포는 서로 다른 단계다 — main에 merge되어도 라이브 사이트는 즉시 바뀌지 않는다(Pages는 별도 workflow 산출물을 서빙).

```text
AgentPlanExecutor: plan의 마지막 CODE step 완료
→ ResultApprovalGate.requestIfRequired
→ project.repositoryBindingStatus == BOUND ? (아니면 게이트 생략, 기존 DEPLOY step 흐름 그대로 진행)
→ resultApprovalRequired 정책 ON ? (아니면 게이트 생략)
→ (게이트 진입 직전 취소 여부 재확인 — 느린 push 직전 마지막 체크)
→ PreviewBranchPushService로 CODE 결과를 preview 브랜치에 push
→ TaskStore.markStepCompleted (currentStep을 다음으로 이동 — 재시도 시 이 CODE step을 다시 안 밟도록)
→ TaskStore.markWaitingResultApproval → task 상태 WAITING_RESULT_APPROVAL (워커 claim 대상 아님)
→ Approval(RESULT) 생성, taskId 연결(Agent 기반 — standalone 아님)
→ "미리보기와 변경 내역을 확인해 주세요" assistant 메시지
```

승인/거절 처리(`ApprovalCommandService.approve/reject`의 RESULT 분기, 같은 트랜잭션 안에서 실행):

```text
approve
→ AgentOrchestrator.verifyResumableAfterResult (task가 여전히 WAITING_RESULT_APPROVAL인지 재확인 — 비가역 작업 전 마지막 방어선)
→ ResultApprovalService.reflect
   → preview에 main 대비 새 커밋 있음 → PR 생성/조회 + merge (githubRepoPort)
   → 새 커밋 없음(멱등 재시도 또는 순수 결과 없음) → main HEAD SHA만 기록, merge 생략
→ Change.markMerged (approvalId/prNumber/mergeCommitSha/mergedAt 기록, 상태 MERGED)
→ AgentOrchestrator.resumeAfterResult → task를 currentStep부터 재개(남은 step 있으면 계속, 없으면 DONE)

reject
→ Change.markRejected (merge 없음, 상태 REJECTED)
→ task CANCELLED
→ 거절된 커밋은 preview 브랜치에 그대로 남음(삭제/reset 안 함)
```

**누적 시맨틱(중요)**: RESULT 승인의 대상은 "이 task의 변경분"이 아니라 "그 시점 preview 브랜치 전체 상태"다. 거절된 커밋도 preview에 남아 있으므로, 다음 작업이 그 위에 이어서 커밋되고 그 다음 RESULT 승인은 (과거 거절분을 포함한) preview 전체를 main에 반영한다. 되돌리려면 명시적으로 되돌리는 수정을 다시 요청해야 한다.

직접 Deployment API(§10.1)와의 관계 — merge 권한 이관:

```text
resultApprovalRequired 정책 ON
→ mergeAllowed = (project.currentVersion == null) AND (이 프로젝트가 RESULT 게이트를 거친 적 없음 — hasResultGateHistory == false)
   → true(한 번도 배포/게이트 이력 없는 신규 프로젝트의 첫 배포)일 때만 직접 배포 API가 자동 merge
   → false면 직접 배포는 main의 현재 상태만 공개, preview는 끌어오지 않음
정책 OFF
→ mergeAllowed = true 항상 (기존 동작 유지, 회귀 없음)
```

`hasResultGateHistory`(project가 `REJECTED` 또는 `MERGED` Change를 하나라도 가졌는지)가 없으면, 사용자가 명시적으로 거절한 내용을 직접 배포가 실수로 merge할 위험이 있었다(리뷰 확정 결함, BLOCKING-1).

Chat 설정(§14.1)에 `resultApprovalRequired` 필드가 추가되어(기본 `true`) 이 게이트를 프로젝트별로 켜고 끌 수 있다.

---

## 10. Deployment와 Webhook

### 10.1 현재 배포 실행

```text
POST /projects/{projectId}/deployments
→ Project 소유권/repository binding 확인
→ DeploymentHistory(PENDING, correlation ID) 저장
→ 202 Accepted
→ DB worker가 lease 획득
→ User/GitHub token 확인
→ framework/package manager/node version 감지
→ workflow 파일 생성/갱신
→ preview→main PR/merge
→ 기존 순차 tag 재사용 또는 새 vN 생성
→ commit/PR/merge 메타데이터 저장
→ GitHub Pages source 설정
→ correlation ID를 포함해 workflow_dispatch
→ correlation ID + workflow head SHA로 run_id polling
→ DeploymentHistory에 version/run ID/metadata 저장
```

### 10.2 완료 상태 반영

```text
GitHub workflow_run webhook
→ 서명 검증
→ X-GitHub-Delivery 기준 DB queue 저장
→ 202 Accepted
→ worker claim/lease
→ workflow 이름 확인
→ workflow run ID로 DeploymentHistory 조회
→ run ID 미저장 시 run-name의 correlation ID로 조회
→ Project sourceRepository와 workflow head SHA 검증
→ success: history/project LIVE
→ 그 외: history/project FAILED
→ 실패 시 backoff retry와 오류 기록(최대 5회)
```

### 10.3 저장소와 installation 동기화

```text
push(default branch)
→ Project repository health HEALTHY
→ 최신 commit SHA/message/author/time snapshot 저장

push(refs/tags/vN)
→ repositoryVersion 갱신
→ 낮은 순차 버전의 지연 이벤트는 무시

pull_request(closed + merged)
→ 기본 브랜치 merge commit snapshot 저장

installation(deleted/suspend)
→ 사용자 App 연결/token 정리
→ 연결 프로젝트 ACCESS_DENIED

installation(created/unsuspend/new_permissions_accepted)
→ installation ID 유지
→ 프로젝트 health를 재확인 상태로 전환
```

### 10.4 보완할 연결

- 동일 프로젝트 동시 배포 제어(queue 직렬화)
- 배포 취소(cancel) API
- Approval 승인 후에만 실제 배포(직접 Deployment API는 여전히 Approval 미적용)

### 10.5 배포 실패 복구 흐름 (ROADMAP U6)

```text
POST /deployments/{deploymentId}/failure-analysis
→ DeploymentFailureAnalysisService
→ 소유권 확인, FAILED 상태 확인(아니면 409)
→ 기존 저장된 분석이 있으면 그대로 반환(멱등, LLM 재호출 없음)
→ 신규 분석: GitHub Actions 로그 조회
→ job/step 로그를 최대 12,000자로 발췌
→ 시크릿으로 보이는 패턴을 정규식으로 레닥션(***REDACTED***)
→ LlmRouter 경유 LLM 호출(60초 타임아웃)
→ 성공: summary/logExcerpt/suggestedFix를 deployment_failure_analyses에 저장(source=LLM)
→ 타임아웃/실패/파싱 실패: 룰 기반 분석으로 fallback해 항상 응답 생성(source=RULE_BASED)

GET /deployments/{deploymentId}/failure-analysis
→ 저장된 결과만 조회, 없으면 404 (부작용 없음)

POST /deployments/{deploymentId}/retry
→ 소유권 확인, FAILED 상태 확인(아니면 409)
→ 원본과 동일한 deployTargetType(VERSION이면 동일 버전)으로 새 DeploymentHistory 생성
→ retriedFromHistoryId로 원본 이력과 연결(원본은 감사 목적으로 보존, 변경 없음)
→ 201 응답
→ 이후 처리는 §10.1 신규 배포 흐름과 동일 (Approval 미적용, 직접 배포와 대칭)
```

---

## 11. DomainBinding

### 11.1 관리형 서브도메인

```text
POST /projects/{projectId}/domains
→ hostname 중복 확인
→ 배포 URL/Pages host를 DNS target으로 결정
→ Cloudflare CNAME 생성
→ GitHub Pages custom domain 설정
→ Pages workflow 재실행 시도
→ DomainBinding 저장
→ verification check에서 Cloudflare + Pages 설정 확인
```

### 11.2 커스텀 도메인

```text
POST /projects/{projectId}/domains
→ hostname 정규화/중복 확인
→ GitHub Pages custom domain 설정
→ CNAME 또는 A record guide 저장
→ 사용자가 DNS 설정
→ verification-checks
→ DNS lookup + Pages custom domain 확인
→ CONNECTED 또는 VERIFYING
```

### 11.3 배포 대상 adapter와 HTTPS

```text
DomainBinding
→ Domain Approval Agent task
→ DomainHostingAdapterRegistry
├─→ GitHubPagesDomainHostingAdapter
├─→ AWS adapter (미구현 시 명시적 거절)
└─→ GCP adapter (미구현 시 명시적 거절)
→ DNS 검증
→ Certificate 상태/만료일
→ HTTPS 강제 적용
→ DNS provider
```

GitHub Pages adapter는 Pages API의 custom domain, 인증서, HTTPS 상태를 실제 조회한다.
AWS/GCP는 실제 cloud 배포 adapter가 추가된 뒤 target별 domain adapter를 등록한다.

Cloudflare 기본값, profile 설정, Agent prompt와 사용자 안내 예시는 `qeploy.com`/Qeploy 정책으로 통일되어 있다.

---

## 12. CloudConnection

### 12.1 현재 흐름

```text
POST /cloud-connections
→ 사용자 확인
→ provider/credential 형식 검증
→ secret AES-GCM 암호화 저장
→ VALIDATED 상태와 verification job 발급

worker: cloud_connection_verification_jobs claim/lease
→ AWS Access Key: STS GetCallerIdentity
→ AWS Role: AssumeRole 후 STS 신원 확인
→ GCP Service Account Key: OAuth token 발급 후 Resource Manager projects.get
→ GCP Service Account Email: IAM Credentials impersonation 후 projects.get
→ 성공: CONNECTED, 실패: INVALID_CREDENTIAL/REGION_UNSUPPORTED 등 저장

GET /cloud-connections/{id}/health
→ 저장된 검증 결과만 조회 (상태를 바꾸지 않음)

PUT /projects/{projectId}/settings/infrastructure
→ CONNECTED 상태 connection만 프로젝트에 선택 저장
```

`CONNECTED`는 이제 실제 외부 계정 권한 확인 완료를 의미한다(2026-06-15 이후, `state.md` §4.5 참고). 과거 "입력 형식 통과만 의미" 서술은 이번 개정으로 수정했다.

### 12.2 현재 단절

```text
CloudConnection
→ Project Infrastructure 설정에서 선택(연결) 가능
    X
Deployment 실행
    X
Agent
    X
Cost Estimate
```

### 12.3 목표 흐름

```text
CloudConnection 등록
→ AWS STS/AssumeRole 또는 GCP IAM 실제 검증 Job (구현됨)
→ Project Cloud Infrastructure 설정에서 연결 선택 (구현됨)
→ architecture/resource/network/database 구성 (예정)
→ provider price API/price table 기반 예상 비용 (예정)
→ DEPLOYMENT Approval
→ IaC plan/apply
→ cloud deployment
→ 상태/로그/비용을 Overview에 반영
→ Infra Agent가 운영 명령 수행
→ 영향/비용 증가 작업은 INFRA_OPERATION Approval
```

---

## 13. Approval과 Job

### 13.1 현재

- Approval entity/API와 프로젝트별 승인 정책이 있다.
- AgentRun/plan/current step/input/event/retry/lease가 DB에 영속화된다.
- Change/diff와 PreviewSession이 task/project/conversation에 연결된다.
- DB queue worker가 lease와 heartbeat로 실행을 claim한다.
- CloudConnection verification job도 동일한 claim/lease DB queue 모델을 사용한다.
- Deployment만 별도 history table을 가진다.
- Approval은 두 출처를 모두 지원한다: Agent task 기반(`taskId` 있음)과 API 직접 생성 standalone(`taskId = null`, ROADMAP U7). 결정(승인/거절)에는 비관적 잠금이 걸려 있다.
- 승인은 두 시점으로 나뉜다: 실행 **전** 계획을 승인하는 1단계(CHANGE/DEPLOYMENT/DOMAIN_BINDING/INFRA_OPERATION)와, CODE 실행이 끝난 뒤 그 결과를 main에 반영할지 결정하는 2단계(`RESULT`, Issue #56, §9.3 참고). RESULT는 항상 Agent 기반이다.
- **동시성 하드닝(Issue #55, ADR-Y1)**: 승인 결정(`ApprovalCommandService.approve/reject`)은 항상 `taskStore.lockTask`로 해당 task 행을 먼저 잠근 뒤(task 단위 락 계층) 그 task의 승인들을 잠금 상태로 재조회한다 — 같은 task의 승인 2건이 동시에 결정되어도 이제 완전히 직렬화되므로, "형제 승인이 아직 PENDING"으로 서로 오판해 task가 `WAITING_APPROVAL`에 영구 고착되는 write-skew가 구조적으로 발생하지 않는다. `StuckApprovalSweeper`(기본 60초 주기)가 그 이전에 이미 고착된 task를 찾아 복구하는 안전망 역할을 겸한다.

### 13.2 Standalone 승인 흐름 (ROADMAP U7)

```text
PUT /projects/{projectId}/settings/infrastructure/configuration
→ ProjectInfrastructureConfigurationService
→ CONNECTED CloudConnection 선택 여부 확인(아니면 409)
→ 현재 값과 동일하면 no-op(이력/승인 없이 현재 상태 반환)
→ 프로젝트에 PENDING_APPROVAL 변경이 이미 있으면 409
→ infraApprovalRequired(Chat 설정) 확인
   ├─ true: ProjectInfrastructureSettingChange(PENDING_APPROVAL) 저장
   │        → ApprovalCommandService에 INFRA_OPERATION 승인 생성 요청 (taskId=null)
   │        → 요청은 기존 설정 유지 + pendingChange로 응답
   └─ false: 즉시 ProjectInfrastructureSetting에 적용, action(CREATED/UPDATED) 이력만 기록

POST /approvals/{approvalId}/approve 또는 /reject
→ ApprovalCommandService.approve/reject (@Lock(PESSIMISTIC_WRITE)로 동시 결정 방지)
→ Approval 상태 저장
→ StandaloneApprovalHandler.supports(INFRA_OPERATION) 매칭
→ InfrastructureChangeApprovalHandler.onApproved/onRejected 호출 (같은 트랜잭션 내부)
   ├─ approve: 대기 변경 값을 ProjectInfrastructureSetting에 적용, 상태 APPLIED
   └─ reject: 적용 없이 상태 REJECTED
→ 핸들러가 예외를 던지면 Approval 상태 변경까지 롤백(승인 결정과 효과 적용의 일관성 보장)
```

Agent 기반 승인과의 차이: Agent plan에서 생성되는 Approval은 `taskId`가 채워지고 승인 후 `AgentPlanExecutor`가 재개하지만, standalone 승인은 Agent 실행 없이 `StandaloneApprovalHandler` 구현체가 직접 효과를 적용한다. 새 standalone 승인 유형을 추가하려면 이 SPI를 구현하기만 하면 된다.

### 13.3 목표

```text
자연어 요청 또는 직접 UI 작업
→ Job 생성
→ 산출물(Change/DeploymentPlan/DomainPlan/InfraPlan) 생성
→ Approval 생성
→ 사용자 승인/거절
→ 승인된 산출물만 실행
→ Job event와 최종 상태 저장
→ Chat/Overview에서 동일 상태 조회
```

권장 상태 소유:

| 상태 | 소유 모듈 |
|---|---|
| 자연어 실행 상태 | Job/AgentRun |
| 코드 변경과 diff | Change |
| Docker 실행 상태 | PreviewSession/PreviewBuild |
| 사용자 승인 | Approval |
| 배포 실행/결과 | Deployment |
| 도메인 검증 | DomainBinding |
| 클라우드 권한 | CloudConnection |
| 환경변수/Secret | Environment |
| 인프라 구성/상태 | Infrastructure(예정) |

---

## 14. Project Settings 연결

### 14.1 개요

현재 Project Settings는 Chat, Infrastructure, Repository, Environment 네 영역이 구현되어 있다. 나머지는 PRD의 설정 구조를 목표로 각 실행 모듈이 읽을 수 있는 프로젝트 정책으로 저장되어야 한다.

```text
ProjectSettings
├─ General → Project (예정)
├─ Repository → Project/GitHub (구현됨: 조회 + 연결 해제)
├─ Release Policy → Change/Approval/Deployment (예정)
├─ Deployment Defaults → Deployment (예정)
├─ Domain & HTTPS → DomainBinding (예정)
├─ Cloud Infrastructure → CloudConnection (구현됨: 연결 선택/해제)
├─ Infrastructure Configuration → Project (구현됨: 4개 provider-중립 enum 저장 + standalone Approval)
├─ Cost & Budget → Project/InfrastructureCostEstimator (구현됨: 온더플라이 추정 + 예산, §14.3)
├─ Environment & Secrets → Environment (구현됨: CRUD+이력, 런타임 실주입은 예정)
├─ Chat Settings → Chat/Agent/Approval (구현됨: 승인 정책 5필드 — CODE/DEPLOY/DOMAIN_BIND/INFRA_OPERATE 실행 전 승인 + RESULT 실행 후 결과 승인, §9.3 참고)
└─ Danger Zone → Project/Repository/Deployment/Infrastructure (예정)
```

### 14.2 Environment/Secrets 도메인과 Resolver seam

```text
GET/POST /projects/{projectId}/environment-variables
PATCH/DELETE /projects/{projectId}/environment-variables/{variableId}
→ EnvironmentVariableController
→ EnvironmentVariableFacade
→ EnvironmentVariableCommandService / EnvironmentVariableQueryService
→ EnvironmentVariableRepository (JPA, V22 environment_variables/environment_variable_histories)
→ 값은 secret 여부와 무관하게 AES-256-GCM 암호화 저장
→ 응답 조립 시 EnvironmentVariableQueryService.toResult에서 secret ? null : plaintext 마스킹
→ 모든 변경은 CREATED/UPDATED/DELETED 이력을 값 없이(valueChanged만) append-only 기록
```

Resolver seam(런타임 주입을 위한 확장 지점):

```text
EnvironmentValueResolver (application/port/in, HTTP 미노출)
→ EnvironmentValueResolverService가 구현
→ 현재 호출자 없음 — Docker Preview 컨테이너 생성, GitHub Actions workflow 환경변수 주입
   어느 쪽에서도 아직 이 port를 사용하지 않는다
→ 향후 연결 지점: 8.2 Code Agent의 컨테이너 생성 단계, 9.x Deploy Agent의 workflow dispatch 단계
```

이 seam이 실제로 연결되기 전까지 Environment 도메인은 "안전하게 값을 저장/조회하는 저장소"이지 "실행에 영향을 주는 설정"은 아니다.

### 14.3 Cost & Budget 흐름 (Issue #53)

```text
GET/PUT/DELETE /projects/{projectId}/settings/cost-budget
→ ProjectController
→ ProjectCostBudgetService
→ 프로젝트 소유권 확인
→ (GET/PUT 공통) ProjectInfrastructureSettingRepository + ProjectCloudConnectionSettingRepository + CloudConnectionRepository 조회
   → 인프라 설정과 CONNECTED 클라우드 연결이 모두 있어야 costAvailable=true
→ InfrastructureCostEstimator.estimate(provider, configuration)
   → 코드 상수 정적 가격표 조회 (외부 호출 없음, DB 조회도 없음)
   → resourceType별 monthlyCost 합산 → totalMonthlyCost
→ ProjectBudgetSettingRepository에서 저장된 예산 조회
→ estimatedMonthlyCost와 budgetAmount를 BigDecimal.compareTo로 비교(엄격한 >)
→ budgetStatus 결정: NO_BUDGET / NOT_EVALUABLE / OVER_BUDGET / WITHIN_BUDGET
→ (PUT만) ProjectBudgetSetting upsert 후 위 계산을 다시 수행해 최신 상태 반환
```

`ProjectCostBudgetService`는 §14.2의 Environment 설계와 같은 원칙(단위 간 서비스 직접 결합 금지)을 따라, `ProjectInfrastructureConfigurationService`/`ProjectInfrastructureSettingsService`를 거치지 않고 그 리포지토리를 직접 읽는다 — 세 번째로 독립적으로 진화하는 read 확장.

예산은 인프라 설정보다 먼저 저장할 수 있다(design D5) — 인프라 미구성 상태에서도 `PUT`이 성공하며, 이후 인프라가 구성되면 같은 `GET`이 자동으로 `costAvailable=true`와 함께 실제 평가를 시작한다.

---

## 15. PRD 목표 사용자 여정

```text
1. GitHub 로그인/App 연결
2. 빈 프로젝트, ZIP, GitHub 레포 중 하나로 프로젝트 준비
3. Conversation 생성
4. 자연어 메시지 저장
5. Persistent Job + Decision Agent 실행
6. Code/Deploy/Domain/Infra Agent 분기
7. 코드 변경이면 preview 브랜치 commit + Docker preview + diff
8. 변경 Approval
9. 배포 요청이면 버전/환경/예상 URL/비용을 포함한 DeploymentPlan
10. 배포 Approval
11. PR/merge/tag/workflow 또는 IaC 실행
12. Domain Approval과 DNS/HTTPS 처리
13. Overview에 URL, 버전, 상태, 최근 이력, 운영 진입점 통합
14. 이후 자연어로 클라우드 운영
```

---

## 16. 구현 우선 연결 순서

이 순서는 `.notion/ROADMAP.md`의 단위(Unit) 백로그로 이관되었다. 최신 우선순위와 진행 상태는 `ROADMAP.md`를 SSOT로 확인한다.

1. Agent task 소유권과 Deployment 조회 소유권 보완 (완료)
2. Agent의 `main --force` 제거, 모든 코드 변경을 `preview` 브랜치로 통일 (완료)
3. Chat → Agent → Assistant message 연결 (완료)
4. Persistent Job/AgentRun/PreviewSession 도입 (완료)
5. Change와 Approval 도입 (완료)
6. Overview를 Deployment/Domain/Job 이력과 실제 연결 (완료)
7. Chat CHAT step 실제 응답 Agent 연결 (완료, ROADMAP U2)
8. Environment/Secrets 저장·조회·이력 (완료, ROADMAP U3)
9. Preview 운영 API + 컨테이너 격리 정책 (완료, ROADMAP U4)
10. Repository Settings 조회 + 연결 해제 (완료, ROADMAP U5)
11. ZIP/GitHub import workflow → ROADMAP 이후 별도 백로그
12. CloudConnection 실제 provider health (완료)
13. 배포 실패 원인 분석(LLM+룰 fallback) + 재시도 (완료, ROADMAP U6, §10.5 참고)
14. 인프라 설정 저장(provider-중립 4개 enum) + standalone INFRA_OPERATION 승인 (완료, ROADMAP U7, §13.2 참고)
15. 비용 추정(정적 가격표) + 예산 저장/평가 (완료, Issue #53, §14.3 참고)
16. Cloud Ops Agent — 자연어 서버 상태/로그/장애분석/재시작(RESTART만 실행, 나머지는 정직한 거부) (완료, Issue #54, §8.6 참고)
17. 결과 승인 2단계 게이트 — preview 결과 확인 후 main 반영, 배포와 분리 (완료, Issue #56, §9.3 참고)
18. 실제 AWS/GCP 프로비저닝, IaC apply, cloud deployment → 다음 백로그(EPIC 15 Cloud-Ops 실행 계층), 착수 전 사용자 확인
19. Environment/Secrets의 Preview/Deployment 런타임 실주입 → §14.2 참고, 아직 미편성
20. Project 동시 쓰기 lost-update 해소 (완료, Issue #45, PR #52, §5.4 참고)
21. Audit Log(EPIC 17 나머지, BI-188~192) — 완료(2026-07-25, Issue #74/PR #75, §17 참고). BI-195는 부분 완료(§17 참고)
22. Issue #55(오케스트레이션 하드닝)·#57(E2E 결함 H1/M3)·#62(결과 승인 리뷰 Low 등급 잔여) → 다음 백로그, 우선순위 사용자 확인 필요

---

## 17. AuditLog 연결 (Issue #74)

### 17.1 훅을 사용하는 서비스

`AuditRecorder`(공개 API는 `record(AuditEvent)` 1개, 실패를 절대 호출자에 전파하지 않는다)를 주입받아 사용하는 서비스는 코드 실측으로 10개다:

```text
ProjectCommandService(레포 생성/연결/해제/삭제)
DeployAgentService(Agent preview push)
ResultApprovalGate(게이트 preview push)
ResultApprovalService(RESULT 승인 집행 merge)
DeploymentCommandService(배포 요청/재시도/워커 실패 확정)
WebhookEventHandler(workflow_run 성공/실패 확정)
DomainBindingCommandService(도메인 연결/해제)
InfraOpsAgentService(Cloud Ops RESTART)
ProjectInfrastructureConfigurationService(인프라 설정 즉시 적용/승인 요청)
InfrastructureChangeApprovalHandler(인프라 설정 승인/거절 집행)
    └─→ AuditRecorder.record(event)
            └─→ AuditLogWriter.write (@Transactional(REQUIRES_NEW), SELECT 0회·INSERT 1회만)
                    └─→ audit_logs (FK 0개 — 락 계층의 리프, #55 ADR-Y1과 무접점)
```

- 기록은 각 메서드에서 외부효과·상태 확정이 끝난 뒤 마지막 문장으로 붙는다. 실패해도 `AuditRecorder`가 예외를 삼키고 `AUDIT_FALLBACK` 로그로 폴백하므로 원래 요청(레포 연결, 배포, 도메인 연결 등)은 절대 실패하지 않는다.
- `ResultApprovalService`는 승인 처리 트랜잭션이 task 락(#55 ADR-Y1)을 보유한 상태에서 호출되는 유일한 지점이다 — `REQUIRES_NEW` 삽입 1회(~1ms)만 추가되어 기존 락 보유 시간에 미치는 영향은 무시할 수준으로 설계되었다(`ad-audit-log-design.md` §5.2).
- `DomainBindingCommandService`의 actor 판정(`taskId` 유무 → AGENT/USER)은 `BindDomainCommand.taskId` 신설로 가능해졌으나, 실측상 이 경로는 항상 `DomainBindingSubmissionService → AgentOrchestrator.submit` 을 거쳐 도달해 현재는 사실상 항상 AGENT다(§11 참고).

### 17.2 SecretRedactor 공용화 — deployment와의 연결

기존 `DeploymentFailureAnalysisService`(§10.5)가 자체 보유하던 시크릿 레닥션 정규식(`SECRET_PATTERN`: GitHub PAT류·AWS AKIA/ASIA·Slack xox·JWT eyJ·Bearer)이 `common/security/SecretRedactor`로 추출되었다. `DeploymentFailureAnalysisService`는 이제 이 공용 클래스에 위임만 하고(동작 동일, 기존 레닥션 테스트 무회귀), `AuditLog` 도메인 모델도 저장 계층(`AuditLog.from(event)`)에서 `errorSummary`에 같은 `SecretRedactor.redact()`를 항상 강제 적용한다 — 두 모듈이 동일한 레닥션 규칙을 공유하는 첫 사례다.

### 17.3 조회 API

`GET /projects/{projectId}/audit-logs`(`api.md` §15)는 `AuditLogQueryService`가 소유자 404 + `category`/`limit` 필터로 제공한다. 기록 경로(위 10개 서비스)와 조회 경로는 완전히 분리되어 있다 — 조회 API는 읽기 전용이며 감사 행을 만들지 않는다.

### 17.4 현재 한계

- 계정 수준 감사(GitHub App installation 이벤트, 로그인 이력)와 `/me/audit-logs`는 이번 단위 범위 밖이다(§16 목록의 후속 검토 대상).
- 환경변수 변경은 audit에 편입되지 않았다 — `environment_variable_histories`(§14.2)가 이미 자체 이력(action+valueChanged)을 보유해 충분하다고 판단했다.
- BI-195(권한 최소화)는 이번 단위에서 감사 접근 최소화(소유자 404)·데이터 최소화(레닥션·화이트리스트)·권한 사용 가시화(카탈로그 16종)까지만 처리한 **부분 완료**다. GitHub App 권한 재검토와 컨테이너 git credential 평문(`/tmp/.git-credentials`) 개선은 보안 성격의 별도 후속 항목으로 분리되어 있다(`ROADMAP.md` "이후 백로그" 참고, 아직 이슈 미신설).

---

## 18. 요청 경로 성능 연결 (Issue #345)

### 18.1 인증 필터 한 요청의 비용

`RequestIdFilter`(가장 앞) → Spring Security 체인 → `JwtAuthenticationFilter` 순서다.

`JwtAuthenticationFilter`는 PAT(`qp_` 접두사)와 서비스 JWT를 접두사로 가른다. JWT 경로는 이제 `TokenPort.parseClaims`를 **한 번** 불러 `userId`·`jti`를 함께 얻는다(이전에는 `getUserId`/`getJti`가 각각 파서를 세워 서명 검증이 두 번 돌았다). `JwtProvider`는 `SecretKey`와 `JwtParser`를 생성자에서 1회 만들어 재사용한다 — `JwtParser`는 상태가 없어 스레드 안전하다.

PAT 경로(`ApiTokenAuthenticator`)는 SHA-256 + UNIQUE 인덱스 조회 1회 + `last_used_at` 1시간 스로틀로 이미 가벼워 이번 단위에서 건드리지 않았다.

### 18.2 폐기 토큰: 캐시와 DB의 관계

```
JwtAuthenticationFilter
  └─ TokenBlacklistPort.isRevoked(jti)
       └─ RevokedTokenRepositoryAdapter
            ├─ RevokedTokenCache.lookup(jti)   TRUE/FALSE → 즉시 반환
            └─ null(모름)                       → SpringDataRevokedTokenRepository.findByJti → 캐시에 적재
```

**정본은 `revoked_access_tokens` 테이블이고 캐시는 그 앞의 단축 경로다.** 캐시가 모른다고 답하면 반드시 DB를 본다 — 이 성질이 재기동·항목 축출에도 폐기가 유지되는 근거다. 완전한 목록을 메모리에 들고 "없으면 폐기 안 된 것"이라 답하는 설계는 목록이 한 번이라도 불완전해지는 순간 조용히 열리는 쪽으로 틀리기 때문에 택하지 않았다.

캐시는 둘로 나뉜다. 두 방향의 위험이 다르기 때문이다 — "폐기됨"을 놓치면 로그아웃한 토큰이 계속 통과하고(되돌릴 수 없다), "폐기 안 됨"을 잘못 기억하면 사용자가 다시 로그인하면 된다.

| | 만료 | 넘칠 때 |
|---|---|---|
| 폐기됨(positive) | 토큰 자체의 만료 시각 | 축출돼도 다음 조회에서 DB가 다시 알려준다 |
| 폐기 안 됨(negative) | `not-revoked-ttl`(기본 60초, `0`이면 비활성) | 같음 |

`revoke`는 **DB 저장보다 먼저** 캐시를 갱신하고 부정 캐시 항목을 지운다. 로그아웃은 `@Transactional` 안에서 일어나 행 커밋이 나중이라, 그 사이에 같은 토큰이 통과하면 안 된다. 조회가 폐기됨을 먼저 확인하는 것도 같은 이유다 — 두 캐시에 같은 jti가 잠깐 함께 있을 수 있고, 그때 닫히는 쪽이 이겨야 한다.

`not-revoked-ttl`은 **다중 인스턴스에서 로그아웃이 다른 인스턴스로 전파되는 지연 상한**이다. 폐기를 수행한 인스턴스 자신은 즉시 반영된다.

### 18.3 감사 로그 쓰기 경로

```
훅(10개 서비스) → AuditRecorder.record  ── 즉시 큐 투입, 요청 스레드는 바로 복귀
                                          └─ auditLogExecutor(스레드 1개)
                                               └─ AuditLogWriter.write  @Transactional(REQUIRES_NEW)
                                                    └─ audit_logs INSERT 1건
```

스레드를 하나만 두는 것이 핵심이다. 감사 쓰기가 쓰는 커넥션이 앱 전체를 통틀어 최대 1개로 묶여, 쓰기 요청마다 순간적으로 커넥션을 하나 더 빌리던 결합이 끊긴다. 부수로 INSERT가 호출 순서를 유지한다.

**커밋 후로 미루지 않는다.** `REQUIRES_NEW`는 "감사 실패가 요청을 죽이지 않는다"(ADR-A2)만이 아니라 "바깥 트랜잭션이 되감겨도 감사 기록은 남는다"도 함께 뜻한다. `RepositoryProvisioningService.bindToProject`처럼 **이미 일어난 외부 효과**(GitHub 저장소 생성)를 기록하는 훅이 있고, 되감겨도 그 저장소는 GitHub에 남는다 — `afterCommit`으로 미루면 되돌릴 수 없는 효과의 유일한 증거가 사라진다. `AuditRecorderIntegrationTest.auditRowSurvivesOuterTransactionRollback`이 이 계약을 고정한다.

큐가 넘치면 **버리지 않고** 호출 스레드에서 실행한다. 과부하에서 최악이 "예전처럼 느려지는 것"이지 "기록이 사라지는 것"이면 안 된다. 동시에 자연스러운 배압이라 큐가 무한정 자라지 않는다. `ThreadPoolExecutor.CallerRunsPolicy`는 실행기가 이미 종료된 경우 말없이 버리므로 쓰지 않았다.

**비동기가 새로 만드는 것은 롤백 문제가 아니라 가시성 지연이다.** `record()` 가 돌아온 시점에 행은 아직 없을 수 있다. `GET /projects/{id}/audit-logs`(`api.md` §15)가 이 지연에 노출되는 유일한 조회 경로다 — `/settings/infrastructure/configuration/history` 는 `infrastructure_change` 를 읽으므로 무관하다. 실무상 지연은 INSERT 한 건 수준이지만 순서가 보장되지는 않는다.

테스트는 이 경계를 `AuditLogExecutor.awaitDrained` 로 **확정적으로** 넘는다. FIFO 큐 + 워커 1개라는 성질을 그대로 쓴다 — 지금 넣은 표식이 워커에서 실행됐다면 그보다 먼저 들어간 작업은 다 끝난 것이다. 시간을 재지 않으므로 CI 부하에 흔들리지 않는다. 큐가 가득 차 표식이 호출 스레드에서 실행된 경우엔 `false` 를 돌려준다(참을 돌려주면 그 순간부터 거짓말이 된다).

**바뀐 것 하나 더**: 프로세스가 갑자기 죽으면 큐에 남은 이벤트가 사라진다. 정상 종료에서는 실행기가 큐를 흘려보내고 내려간다(§17의 "유실 창"의 연장).

### 18.4 요청 상관관계 ID

`RequestIdFilter`가 MDC `requestId`를 심고 응답 `X-Request-Id`로 돌려준다. 로그 패턴은 Spring Boot 기본 패턴이 참조하는 `LOG_LEVEL_PATTERN` 자리만 바꿔 얹었다(`logging.pattern.level`) — 콘솔 패턴 전체를 갈아엎지 않아 부트 기본값이 달라져도 따라간다.

`AuditRecorder`는 MDC를 감사 스레드로 넘긴다. 감사 스레드가 남기는 `AUDIT_FALLBACK` 로그가 어느 요청의 것인지 모르면 정작 추적이 필요할 때 추적이 안 된다.

### 18.5 현재 한계

- `RequestIdFilter`는 `OncePerRequestFilter` 기본값을 따라 **ASYNC 디스패치에서는 다시 돌지 않는다**. SSE의 비동기 디스패치 구간 로그에는 `requestId`가 비어 나온다.
- 폐기 토큰 부정 캐시의 다중 인스턴스 전파 지연(기본 60초)은 설계상 남는 창이다. 없애려면 `not-revoked-ttl: 0`으로 매 요청 DB 조회로 돌아가거나, 인스턴스 간 무효화 전파(pub/sub) 수단이 필요하다 — 후자는 현재 인프라에 없다.
