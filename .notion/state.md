# Qeploy 개발 상태

> 기준일: 2026-08-15 · 기준 커밋: 47bb5fb · 코드 실측 기반

## 1. 기준

- 기준일: 2026-08-15
- 기준 커밋: `47bb5fb` (PR #114 프리뷰 CDN beacon 주입 차단 반영, main)
- 제품 기준: `.notion/prd.md` (§15.2는 2026-07-18 사용자 확정으로 예외 개정, §2.23 참고)
- 구현 기준: 현재 Spring Boot 코드, Flyway migration, 테스트
- 테스트 결과: 통합 main 737/737, Flyway V1~V31 연속 (PR #114 머지 시점, V31은 §2.27에서 신설). 직전 기준(710) 대비 +27 — §2.30 게이트웨이 인가(+16)·§2.31 서브리소스 인가·CORS 수정(+5)·§2.32 빌드 base 흡수(+5)·§2.33 CDN beacon 차단(+1) 신설분
- 현재 공개 API: 94개, 직전 기준(93개, PR #92 시점) 대비 +1 — `POST /preview-sessions/{sessionId}/access`(프리뷰 열람 권한 발급, Issue #77, §2.30 참고) 신설. 재집계 방식은 `api.md` §1과 동일
- 단위 작업 백로그와 우선순위 SSOT는 `.notion/ROADMAP.md`이다. 이 문서는 기능별 완료 여부를, ROADMAP.md는 다음에 무엇을 어떤 순서로 할지를 다룬다.

이 문서는 항목을 세 종류로 구분한다.

- **한 것**: 현재 코드에서 핵심 동작이 실제로 이어지는 기능
- **해야 할 것**: PRD에 있으나 대응 구현이 없는 기능
- **수정해야 할 것**: 구현은 있으나 PRD와 다르거나 운영/보안상 보완이 필요한 기능

---

# 2. 한 것

## 2.1 인증과 사용자

- GitHub OAuth 로그인 URL/callback
- 사용자 생성/갱신
- 서비스 JWT와 Refresh Token rotation
- 로그아웃과 Access Token blacklist
- GitHub App 설치 callback
- GitHub App User Token 저장과 재인증 URL
- 내 프로필 및 GitHub App 연결/만료 상태 조회
- GitHub token과 주요 secret의 AES-GCM 암호화 저장

## 2.2 프로젝트와 GitHub 저장소

- GitHub 저장소 없이 DRAFT 프로젝트 생성
- 프로젝트 생성은 프로젝트 행만 만든다 — 초기 CODE task 제출 경로는 제거됨(제출돼도 실행된 적이 없었다). 첫 코드는 사용자의 첫 요청 때 CODE Agent 가 만든다
- `startMode=template` 이면 `templateType` 을 템플릿 카탈로그와 대조한다(§4.22). 없는 ID 는 400
- 프로젝트와 저장소를 분리해 관리
- 새 GitHub 저장소 생성 후 연결
- 기존 GitHub 저장소 접근 확인 후 연결
- 접근 가능한 GitHub 저장소 목록 조회
- `preview` 브랜치 준비
- 프로젝트 목록 최신 수정순 조회
- 프로젝트 상세/이름 수정
- 프로젝트 soft delete
- 선택 시 연결 GitHub 저장소 포함 삭제
- 저장소 health와 최근 커밋 조회
- 프로젝트 Chat 승인 정책 조회/수정 (`settings/chat`)
- 프로젝트 Infrastructure(CloudConnection) 선택 조회/설정/해제 (`settings/infrastructure`)
- 프로젝트 Repository 설정 조회, GitHub 저장소 연결 해제 (`settings/repository`, `DELETE /repository`) — §2.17 참고

## 2.3 Chat 데이터

- 프로젝트별 대화 생성/조회
- 사용자 메시지 저장/조회, 저장 즉시 Decision Agent 실행 연결(2.4 참고)
- 첫 메시지 기반 대화 제목 자동 생성(80자 제한)
- 대화 soft delete와 계정 휴지통(7일 보관)
- 삭제 대화 복구
- 휴지통 대화 즉시 영구 삭제 API
- 매시간 주기(`ChatTrashCleanupScheduler`, 기본 3600000ms) 만료 대화 자동 영구 삭제
- 삭제 프로젝트의 동일 저장소 재연결 시 복구 대상 프로젝트 탐색
- 메시지 응답에 큐잉된 Agent task의 `taskId`(nullable) 노출 — §2.14 참고

## 2.4 AI Agent 기본 골격과 영속화

- Claude/OpenAI provider routing
- 자연어를 `CODE`, `DEPLOY`, `DOMAIN_BIND`, `CHAT` step으로 분류
- 여러 intent를 순서 있는 plan으로 생성
- 비동기 task 시작과 상태 polling, event polling/SSE
- 추가 입력이 필요한 task의 입력 대기/재개(blank/중복 입력 방어 포함)
- 기존 프로젝트 저장소 clone/pull
- `agent_runs`/`agent_run_events`에 task/plan/step/입력/retry/lease/이벤트 영속화
- DB claim과 worker lease/heartbeat로 다중 인스턴스 중복 실행 방지, 만료 lease `RETRY_WAIT` 복구
- Agent 제출 흐름을 `AgentFacade`로 분리해 conversation/approval 결과 유지(PR #33)
- 실패 task 수동 retry, task/Approval 동시 cancel
- CHAT step은 `ChatAgentService`가 실제 LLM 응답을 생성한다(더 이상 미구현이 아니다) — §2.14 참고

## 2.5 Docker Preview와 PreviewSession

- `node:20-alpine` container 생성
- LLM tool을 통한 파일 읽기/쓰기/command 실행
- npm 의존성 설치와 build
- `dist`, `build`, `out` 결과물 감지
- 정적 preview server 실행
- task마다 격리된 `preview_sessions`(container/port/TTL/access token) 영속화
- token 기반 backend gateway URL 응답, TTL 만료/명시적 종료 시 container 자동 제거
- 사용자 단위 `UserContainerRegistry` 제거, Docker 포트 바인딩 누락·컨테이너 제거 예외 흐름 보강(PR #33)
- 사용자 session container 종료 API
- 컨테이너 상태/로그 조회 API와 자원·권한 격리 정책(BI-194), `docker-java` 3.7.1 업그레이드 — §2.16 참고

## 2.6 Change와 build 실패 복구

- Code step 성공 시 `project_changes`에 task/preview session/summary/diff 저장
- 프로젝트 Change 목록/상세/diff 조회 API
- 배포 완료(workflow 성공 webhook) 시 Change를 `DEPLOYED`로 전환
- build 실패를 사용자용 설명, 로그 일부, 최선의 수정안으로 저장
- 기본 승인 정책에서는 복구용 CHANGE Approval 생성, 승인 후 자동 재build
- 승인 정책이 꺼진 프로젝트는 제한된 횟수 안에서 자동 retry
- **task 응답의 `retryable`·`pendingApprovalId` 정합(Issue #57, PR #65, 2026-07-19 병합)**: `GET /agent/tasks/{taskId}` 응답에 `pendingApprovalId`(nullable) 필드를 추가하고, `retryable`을 `POST /tasks/{taskId}/retry`가 실제로 검사하는 것과 **동일한 기준**("PENDING 승인이 하나도 없음 AND `attempt < maxAttempts`")으로 재정의했다. 이전에는 `retryable`이 `attempt < maxAttempts`만 보고 계산되어, `BuildFailureRecoveryService`가 만든 "자동 수정 및 재build" 승인이 아직 PENDING인 상태에서도 `retryable:true`를 반환해 FE가 `/retry`를 호출하면 항상 409가 나는 결함이 있었다(수동 재시도와 승인 기반 재실행이라는 두 경로가 응답에서 구분되지 않았음). `pendingApprovalId`가 채워져 있으면 그 승인을 먼저 `POST /approvals/{id}/approve`로 처리해야 하며(승인 자체가 재실행을 트리거), 승인 대기가 없을 때만 `/retry`를 사용한다

## 2.7 Approval

- `Approval` 모델과 조회/승인/거절 API
- `CHANGE`, `DEPLOYMENT`, `DOMAIN_BINDING`, `INFRA_OPERATION`, `RESULT` 승인 유형(RESULT는 Issue #56, §2.23 참고)
- 프로젝트별 승인 On/Off 정책(Chat Settings API, `resultApprovalRequired` 포함 5필드)과 연동
- 필요한 승인이 모두 완료된 뒤에만 Agent plan 실행, 거절 시 task `CANCELLED`
- 승인은 실행 **전** 계획을 승인하는 1단계(CHANGE/DEPLOYMENT/DOMAIN_BINDING/INFRA_OPERATION)와, 실행이 **끝난 뒤** 결과를 승인하는 2단계(RESULT)로 나뉜다 — 후자는 이미 실행된 CODE 결과를 main에 반영할지 결정하는 게이트다
- 승인 결정에는 비관적 잠금이 걸려 있다(U7 이후 공통)

## 2.8 GitHub Pages 배포

- `preview`와 `main` 비교
- PR 생성/조회와 merge
- main HEAD 순차 tag 생성
- framework/package manager/node version 감지
- GitHub Actions workflow 생성/갱신과 실행
- GitHub Pages 활성화/source 변경
- 배포 이력 저장, 요청 시 `PENDING` Job 선저장 후 202 응답, DB worker가 lease로 실행
- workflow run ID/correlation ID 기반 정확한 매칭과 상태 갱신
- 배포 상태/이력/로그 조회
- `workflow_run` webhook으로 LIVE/FAILED 갱신
- 특정 tag 배포와 과거 LIVE 버전 후보 조회

## 2.9 Webhook 완성

- `X-GitHub-Delivery` GUID PK 기반 중복 처리 방지, 서명 검증 후 큐 저장, 즉시 202 응답
- DB claim/lease, 만료 lease 복구로 다중 인스턴스 중복 실행 방지
- 실패 시 backoff 후 최대 5회 재시도, 상태/오류 기록(`PENDING/PROCESSING/RETRY_WAIT/COMPLETED/IGNORED/FAILED`)
- push(기본 브랜치/태그), pull_request(merge), installation(삭제/중단/복구) 반영

## 2.10 도메인

- 관리형 서브도메인 검색과 중복 확인
- Cloudflare CNAME 생성/삭제
- GitHub Pages custom domain 설정/해제
- 사용자 커스텀 도메인 연결
- A/CNAME DNS 가이드
- DNS와 Pages 설정 재검증
- 도메인 상태 저장, HTTPS 강제 적용/인증서 상태 조회
- 연결/해제 모두 Agent task 제출 + Domain Approval 정책 연동

## 2.11 CloudConnection

- AWS/GCP 연결 CRUD
- AWS Access Key/Role ARN 입력 모델
- GCP Service Account Key/Email 입력 모델
- provider별 입력 형식 검증, 민감 credential 암호화 저장, secret 비노출 응답
- `cloud_connection_verification_jobs` 발급과 worker claim/lease 기반 비동기 실제 검증
- AWS STS `GetCallerIdentity`/`AssumeRole`, GCP OAuth/`projects.get`, IAM impersonation 실제 호출
- 실제 검증 성공 시에만 `CONNECTED`, `GET health`는 저장된 결과만 조회
- 프로젝트별 Infrastructure 설정에서 `CONNECTED` connection 선택/해제(연결 또는 프로젝트 삭제 시 자동 정리)

## 2.12 API/응답/브랜드 정합성

- 공통 `status/code/message/data` envelope 자동 변환, 공통 오류 계약 통일
- CORS origin을 `qeploy.cors` profile 환경설정으로 분리(dev/local 기본 제공, prod 환경변수 명시)
- Spring application name/Swagger/README/Agent prompt/commit 작성자를 Qeploy로 통일, `QEPLOY_AI_*` 우선 + 기존 `ANTHROPIC_API_KEY`/`OPENAI_API_KEY` fallback

## 2.13 FE 회귀 검증 및 결함 수정 — ROADMAP U1 (PR #38, 2026-07-17 병합)

- FE 정본(`/Users/otter/Dvely_FE_test`) 기준 회귀 검증: 테스트 143/143 기준선, 58개 엔드포인트 중 41개 실측(~71%). QA 리포트 `Dvely_springboot/.agent-team/11-qa/fe-regression-report.md`
- 경로 변수/쿼리 파라미터 타입 불일치 시 500 → 400 (`GlobalExceptionHandler`에 `MethodArgumentTypeMismatchException` 핸들러 + 로그 새니타이즈 + 회귀 테스트 3건, 최종 146/146)
- Chat `POST /conversations/{id}/messages` Swagger 설명을 실동작(Decision Agent 동기 실행·Agent task 큐잉 포함)에 맞게 정정 — 동작 변경 없음
- FE 측 정합화는 Dvely_FE_test PR #4로 병합(타입 보강, `API_TEST_FLOW.md` 휴지통 7일 정정)
- 이관: `MessageResponse.taskId` 노출 검토는 U2(`danto/agent-chat`)로 → §2.14에서 완료

## 2.14 Agent CHAT 스텝 + 메시지 taskId 노출 — ROADMAP U2 (PR #40, 2026-07-17 병합)

- `ChatAgentService` 신설: Decision Agent가 CHAT으로 분류한 대화형 요청에 LlmRouter 경유 실제 LLM 응답 생성. 대화 맥락 주입 시 재작성 instruction과 중복되는 최근 user 턴 제거 + Anthropic user-first 턴 계약 방어
- `AgentPlanExecutor.handleChat` null 스텁 해소 — 응답이 CodeResult.summary로 실행기 흐름에 탑승, 완료 시 assistant 메시지로 기록
- `MessageResponse`에 nullable `taskId` 추가(큐잉 성공 시 값·판단 실패 시 null·GET messages는 null) + Swagger를 폴링 안내로 갱신 — U1 D-DOC-1 이관분 해소
- FE 반영: Dvely_FE_test PR #5 (타입 + Agent task 폴링 자동 연동)
- 테스트 155/155 (ChatAgentServiceTest·ChatMapperTest 등 +9), 코드리뷰 APPROVE

## 2.15 Environment / Secrets 도메인 — ROADMAP U3 (Issue #41 / PR #44, 2026-07-18 병합)

- `environment` 도메인 신설(V22): `environment_variables`, `environment_variable_histories` 2개 테이블
- 엔드포인트 5개: `GET/POST /projects/{id}/environment-variables`(목록·생성), `PATCH/DELETE /projects/{id}/environment-variables/{variableId}`(수정·삭제), `GET /projects/{id}/environment-variables/history`(이력)
- 스코프는 `PREVIEW`/`PRODUCTION` 두 가지만 존재(설계상 `COMMON` 없음 — 두 환경 모두 필요하면 두 번 생성)
- 값은 `secret` 여부와 무관하게 전체 AES-256-GCM 암호화 저장(`env_value` 컬럼)
- `secret=true`인 변수는 목록/생성/수정 응답 어디에서도 평문이 노출되지 않는다(`value=null` 고정, `EnvironmentVariableQueryService.toResult`에서 `secret ? null : plaintext`로 마스킹)
- `secret`은 `true → false`로 되돌릴 수 없음(400)
- 변경 이력은 값 자체를 저장하지 않고 CREATED/UPDATED/DELETED 액션과 `valueChanged` 플래그만 append-only로 기록(secret 값을 이력에서 유추할 수 있는 경로 차단)
- 동일 (프로젝트, scope, key) 조합 중복 생성은 409
- `EnvironmentValueResolver`는 애플리케이션 내부 port로 존재하며 HTTP로 노출되지 않는다 — Docker Preview/Deployment 워크플로에 실제 주입하는 연결은 아직 없다(§3.5 참고)
- 리뷰에서 확정된 5건 수정 완료(값 미저장 이력 설계로 secret 값 유추 경로 차단 포함)

## 2.16 Preview 운영 API + 컨테이너 격리 정책 — ROADMAP U4 (Issue #42 / PR #47, 2026-07-18 병합)

- `GET /preview-sessions/{sessionId}/status`: 컨테이너 실행 여부, OOM 여부, exit code, 시작/만료 시각, 리소스 사용량(메모리 사용량/한도/퍼센트, CPU 퍼센트)을 반환. 종료된 세션도 404가 아닌 200 + `containerRunning=false`로 응답. stats one-shot 샘플링 특성상 CPU 델타 계산에 ~1초가 필요해 이 API의 p95 지연은 약 1.5초(FE 폴링 주기 5초 이상 권장), stats 조회가 3초를 넘기면 `resources`만 null로 degrade
- `GET /preview-sessions/{sessionId}/logs`: 컨테이너 stdout/stderr를 타임스탬프 포함 텍스트로 반환. `tail` 기본 200, `[1, 2000]` 범위로 클램프(에러 아님). `sinceSeconds`는 "최근 N초" 상대값. 로그는 비영속(컨테이너 제거 시 소멸, 다운로드/스트리밍 미지원). 컨테이너가 이미 제거된 세션은 404가 아닌 `containerRunning=false, logText=""` 200 응답
- BI-194 컨테이너 격리 정책(`DockerContainerService`): 메모리 1GiB(`MEMORY_LIMIT_BYTES = 1<<30`) + swap도 동일 상한(추가 스왑 없음), CPU 1.0 vCPU(`NANO_CPUS = 1_000_000_000`), `pidsLimit` 256(fork bomb 방지), `capDrop(ALL)` 후 `capAdd(CHOWN, SETUID, SETGID)` 3개만 재부여(npm 라이프사이클 스크립트의 권한 강등/파일 소유권 변경용), `no-new-privileges` 보안 옵션, 전용 브리지 네트워크 `qeploy-preview`(`enable_icc=false`로 프리뷰 컨테이너 간 통신 차단). 네트워크가 기존에 이 옵션 없이 존재하면 경고 로그만 남기고 계속 진행(수동 재생성 필요)
- `docker-java` 3.3.6 → 3.7.1 업그레이드: 최신 Docker Engine의 capability 응답 역직렬화 결함을 해소하기 위함이며 버전 하한을 회귀 테스트로 고정. DB 스키마 변경 없음
- 리뷰 확정 결함 수정: 네트워크 존재 확인의 substring 매칭 오류(정확한 이름 매칭으로 수정), 상태/로그 조회 트랜잭션 분리, 로그 수집 안전화

## 2.17 Repository Settings — ROADMAP U5 (Issue #43 / PR #46, 2026-07-18 병합)

- `GET /projects/{id}/settings/repository`: 연결된 GitHub 저장소 정보와 `defaultBranch`를 조회. 저장소 미연결 프로젝트도 200 + `connected=false`로 응답. `defaultBranch`는 매 요청마다 GitHub에서 실시간 조회하므로 GitHub 왕복 지연(p95 약 500ms)이 추가되며, 조회 실패 시 null로 degrade
- `DELETE /projects/{id}/repository`: 프로젝트에서 GitHub 저장소 연결 정보만 제거(비파괴). GitHub 저장소·workflow·Pages는 삭제하지 않으며, 배포 이력/도메인 연결 등 다른 도메인 상태도 별도 정리하지 않는다(자연 단절). 해제 후 `POST /repository`로 동일하거나 다른 저장소를 다시 연결 가능
- V23: `projects.repository_connected_at` 컬럼 추가(연결 시각, 해제 시 NULL, 레거시 행은 백필 없이 NULL)
- 과거 알려진 한계였던 동시 쓰기 lost-update(webhook head-sync와의 경합)는 Issue #45(§2.20 참고)에서 `projects.version` + `@Version` 2겹 잠금으로 해소했다. 경합 시 `409`를 반환한다
- 리뷰 후속 반영: V23 마이그레이션 번호 재조정, 설정 매핑 테스트 실효화

## 2.18 배포 실패 복구 — ROADMAP U6 (Issue #48 / PR #50, 2026-07-18 병합)

- `deployment_failure_analyses` 테이블 신설(V24, 이력당 최대 1건)과 `deployment_histories.retried_from_history_id` 컬럼 추가
- `POST /deployments/{id}/failure-analysis`: FAILED 배포만 대상(아니면 409). 이미 저장된 분석이 있으면 LLM을 다시 호출하지 않고 그대로 반환(멱등, 리뷰 확정으로 중복 호출 차단)
- 분석 로그는 GitHub Actions job/step 로그에서 최대 12,000자만 발췌하고, 전송 전 시크릿으로 보이는 패턴을 정규식으로 레닥션(`***REDACTED***`)한 뒤 LLM에 전달
- LLM 호출은 60초 타임아웃(`CompletableFuture#orTimeout`)을 두고, 타임아웃·전송 실패·응답 파싱 실패 시 룰 기반(rule-based) 분석으로 자동 fallback해 항상 응답을 반환한다(`source`: `LLM` 또는 `RULE_BASED`)
- `GET /deployments/{id}/failure-analysis`: 저장된 결과만 반환(부작용 없음). 분석 이력이 없으면 404
- `POST /deployments/{id}/retry`: FAILED 배포만 대상(아니면 409), `201` 응답. 원본과 동일한 `deployTargetType`(VERSION이면 동일 버전)으로 새 `DeploymentHistory`를 생성하고 `retriedFromHistoryId`로 원본과 연결한다. 원본 이력은 되돌리지 않고 감사 목적으로 보존
- 재시도는 직접 Deployment API와 동일하게 Approval 없이 즉시 큐잉된다(대칭적 동작, 의도적 설계)
- 리뷰에서 확정된 7건 수정 완료(분석 중복 호출 차단, 시크릿 레닥션, LLM timeout degrade 등)

운영 한계:

- 배포 취소 API와 동일 프로젝트 queue 직렬화는 아직 없다(§3.9 참고).

## 2.19 인프라 설정 저장 + INFRA_OPERATION Standalone 승인 — ROADMAP U7 (Issue #49 / PR #51, 2026-07-18 병합)

- `project_infrastructure_settings`(적용된 설정, PK=project_id), `project_infrastructure_setting_changes`(변경 이력 + 승인 대기) 테이블 신설(V25)
- 4개 provider-중립 enum: `deploymentArchitecture`(SERVER/CONTAINER/SERVERLESS), `computeTier`(MICRO/SMALL/MEDIUM/LARGE), `storageType`(NONE/OBJECT_STORAGE), `networkAccess`(PUBLIC/PRIVATE). 오토스케일링/로드밸런서/DB 설정(BI-125~127)은 `BACKLOG_STATUS.md` Removed 지침에 따라 이번 단위 범위에서 명시적으로 제외
- `GET /projects/{id}/settings/infrastructure/configuration`: CONNECTED 클라우드 연결이 없는 프로젝트도 200 + `configurable=false`로 응답
- `PUT /projects/{id}/settings/infrastructure/configuration`: CONNECTED 연결이 선택되어 있어야 함(아니면 409). Chat 설정의 `infraApprovalRequired`(기본 true)가 켜져 있으면 즉시 적용하지 않고 `INFRA_OPERATION` 승인을 생성하며 응답은 기존 값 유지 + `pendingChange`로 대기 건을 함께 반환. 꺼져 있으면 즉시 적용. 현재 값과 완전히 동일한 요청은 이력·승인 생성 없이 no-op으로 현재 상태만 반환. 프로젝트당 `PENDING_APPROVAL` 변경은 1건만 허용(이미 있으면 409)
- `GET /projects/{id}/settings/infrastructure/configuration/history`: `limit` 기본 50/최대 200, PENDING_APPROVAL·REJECTED를 포함한 모든 상태를 최신순으로 반환(감사 목적)
- `approvals.task_id`를 NULL 허용으로 변경해 **standalone 승인**(Agent task 없이 API 요청에서 직접 생성되는 승인, task_id=NULL)을 도입. `StandaloneApprovalHandler` SPI(`supports`/`onApproved`/`onRejected`)를 `ApprovalCommandService.approve/reject`의 기존 트랜잭션 경계 안에서 호출하도록 연결하고, `InfrastructureChangeApprovalHandler`가 INFRA_OPERATION 타입을 구현해 승인 시 설정 적용/거절 시 변경 폐기를 처리
- Approval 승인/거절 결정에 `@Lock(PESSIMISTIC_WRITE)` 비관적 잠금을 적용해 동시 승인 요청 경합을 방지
- 리뷰에서 확정된 7건 수정 완료(승인 결정 비관적 잠금, 저장 전 전제조건(CONNECTED 연결·PENDING 유무) 재검증, dispatch 테스트 보강 등)

운영 한계:

- 실제 AWS/GCP 프로비저닝(EPIC 15 Cloud-Ops)과는 아직 연결되어 있지 않다. 이 설정은 "적용을 원하는 desired state"를 저장할 뿐, 실제 클라우드 리소스를 바꾸지 않는다.
- tier-to-instance(예: MEDIUM ↔ AWS t3.medium/GCP e2-medium) 매핑은 아직 없다.

## 2.20 Project 동시 쓰기 lost-update 해소 (Issue #45, PR #52, 2026-07-18 병합)

- `projects.version` 컬럼 추가(V26)와 JPA `@Version`으로 낙관적 잠금을 도입하되, 방어를 2겹으로 겹쳤다:
  1. `@Transactional` 경로(`ProjectCommandService`, `WebhookEventHandler.handle`, `deploy()`): 같은 트랜잭션 안에서 처음 읽은 영속성 컨텍스트의 엔티티를 재사용하므로 Hibernate `@Version`의 flush 시점 `UPDATE ... WHERE version=?` 검사만으로 경합을 감지한다.
  2. 무tx 경로(`DeploymentCommandService.execute()`, 비동기 worker): 매 저장이 새 auto-commit 트랜잭션이라 `findById`가 항상 최신 행을 가져와 `@Version` 검사가 사실상 항상 통과해버린다. `ProjectRepositoryAdapter.save()`가 호출자가 들고 있던 `project.getVersion()`과 DB의 현재 `version`을 명시적으로 비교해 이 구멍을 막는다.
- 두 경로 모두 동일한 `ObjectOptimisticLockingFailureException`(OOLFE)을 던지도록 통일해 처리 코드가 하나로 수렴한다.
- `GlobalExceptionHandler`의 OOLFE 응답을 기존 `404` → `409`로 전환. 과거(U3 시점)엔 `@Version`이 없어 OOLFE의 사실상 유일한 발생 원인이 "동시 삭제"였으나, `@Version` 도입 이후로는 "행은 존재하며 버전이 경합했다"가 지배적 원인이 되어 404가 부정확해졌다. 삭제 경합도 클라이언트가 재조회하면 자연스럽게 404로 수렴하므로, 전 도메인 공용 핸들러이기 때문에 environment 등 다른 도메인의 "수정 중 동시 삭제" 응답도 함께 404→409로 바뀐다(재조회 흐름은 동일하게 유지).
- 경로별 정책:
  - 사용자 직접 요청 경로: `409`를 그대로 반환하고 클라이언트 재조회/재시도에 맡긴다.
  - 백그라운드 워커 경로(`DeploymentCommandService.handleExecutionFailure` 등): 새 재시도 장치를 만들지 않고 기존 배포 retry/backoff 머신에 그대로 편입시킨다.
  - Agent 경로(`DeployAgentService`): 버전 경합 시 인라인 1회 재시도(재조회 후 재저장) 후, 그래도 실패하면 기존 Agent task 실패 처리로 전파한다.
- `DeploymentCommandService.execute()`의 자기 유발(self-inflicted) 경합 제거: 기존에는 `execute()` 시작 시점에 읽은 오래된 `Project` 스냅샷으로 배포 상태를 저장했는데, 같은 메서드의 GitHub I/O(`ensureWorkflow`/`prepareRelease`가 유발하는 push)가 트리거한 webhook이 그 사이 먼저 버전을 갱신해버리는 통상적인(드물지 않은) 경합이 있었다. 저장 직전에 프로젝트를 다시 조회하도록 바꿔 경합 창을 "execute() 전체 GitHub I/O 구간"에서 "수 밀리초"로 축소했다.
- `ProjectRepositoryAdapter.save()`의 삭제 경합 재삽입 버그 봉합: 저장 대상 행이 동시에 삭제된 경우 과거에는 `orElseGet`이 이를 새 행으로 재삽입해 프로젝트가 중복 생성될 수 있었다. 이제는 다른 버전 경합과 동일하게 OOLFE를 던져 저장을 거부한다.
- 리뷰에서 확정된 결함 수정 완료(자기 유발 경합 제거, OOLFE 정책 완결 등)
- 테스트 366/366. 신규 공개 엔드포인트는 없다(내부 동시성 제어만 변경).

## 2.21 Cost & Budget (Issue #53, PR #58, 2026-07-18 병합)

- `project_budget_settings`(V27, 월 예산 금액+통화만 영속) 테이블 신설
- `GET/PUT/DELETE /projects/{id}/settings/cost-budget` 3개 엔드포인트
- 비용 추정은 저장하지 않고 매 요청마다 온더플라이로 계산한다(`InfrastructureCostEstimator`가 코드 상수 정적 가격표를 사용, 외부 API 호출 없음 — 실시간 클라우드 요금이 아니라 가정 기반 추정치임을 응답 설명에 명시)
- U7 `ProjectInfrastructureSetting`(architecture/tier/storage/network)과 선택된 CONNECTED CloudConnection의 provider를 읽어 계산. 인프라 미구성이거나 CONNECTED 연결이 없어도 `200` + `costAvailable=false`(추정 필드 null/빈 배열)로 응답
- 통화는 `USD` 고정
- `budgetStatus` 4상태: `NO_BUDGET`/`NOT_EVALUABLE`/`OVER_BUDGET`/`WITHIN_BUDGET`. 판정은 `BigDecimal.compareTo`(엄격한 `>`이어야 OVER_BUDGET, 같으면 WITHIN)로 비교하며 `equals`는 쓰지 않는다(스케일이 달라도 값이 같으면 동일하게 취급하기 위함)
- `PUT`은 upsert(멱등)이며, 인프라가 아직 구성되지 않은 프로젝트도 예산을 먼저 설정할 수 있다(design D5). 저장 직후 재계산된 비용/예산 상태를 `GET`과 동일한 shape로 함께 반환해 FE가 즉시 갱신할 수 있게 한다
- `DELETE`는 미설정 상태에서 호출해도 `204`(멱등)
- 오토스케일링/불필요 리소스 정리 등 고급 비용 최적화(BI-148~151)는 범위 밖(`BACKLOG_STATUS.md` Removed 참고)

## 2.22 Cloud Ops Agent (Issue #54, PR #59, 2026-07-18 병합)

- `AgentType.INFRA_OPERATE` 신설. 신규 HTTP 엔드포인트 없이 기존 Chat(`/conversations/{id}/messages`)과 Agent 직접 호출(`/agent/decision`) 흐름만으로 접근한다
- `InfraOpsAgentService`가 실행을 담당하며, 다루는 대상은 항상 `(projectId, ownerUserId)`로 DB에서 재조회한 결과다 — LLM이 넘겨주는 값은 `operation` 이름 문자열뿐이며, 리소스 식별자(컨테이너 ID, 배포 이력 ID 등)를 LLM에서 직접 받는 경로는 없다(프롬프트 인젝션 방어, design D3)
- `InfraOperation` 화이트리스트: `STATUS_CHECK`, `LOG_VIEW`, `FAILURE_ANALYSIS`, `RESTART`(이상 지원) / `RESOURCE_SCALING`, `AUTOSCALING_CHANGE`, `RESOURCE_CLEANUP`(미지원, BI-174/175/PRD §21.2 — 감지·설명만 하고 실행하지 않음)
- `STATUS_CHECK`: 배포/Preview/클라우드 연결/인프라 설정 4개 행을 고정 템플릿으로 응답(LLM이 요약하지 않음 — 환각된 "정상입니다" 방지). 각 행은 독립적으로 조회하며 한 항목이 실패해도 "확인 불가"로만 표시하고 나머지는 정상 응답(리뷰 후속 수정으로 확정)
- `STATUS_CHECK` 응답에는 항상 "저장된 인프라 설정의 실 클라우드 리소스는 아직 프로비저닝되지 않았습니다"라는 고정 문구가 포함된다 — 실제 프로비저닝된 서버가 없다는 사실을 숨기지 않는다
- `LOG_VIEW`: ACTIVE preview 컨테이너의 stdout/stderr를 tail 50줄/최대 2000자로 잘라 chat 말풍선에 맞게 축약(§2.16의 Preview 운영 API tail=200보다 훨씬 작음)
- `FAILURE_ANALYSIS`: U6의 `DeploymentFailureAnalysisService`를 그대로 재사용(로직 중복 없음)
- `RESTART`: 유일한 변경(mutating) 작업. 대상은 항상 DB에서 재조회한 **ACTIVE** preview 세션으로 한정되며, `serviceImpact=true`이므로 프로젝트 승인 정책이 켜져 있으면 `INFRA_OPERATION` Approval을 요구한다 — U7과 같은 `ApprovalType`을 재사용하지만 이 승인은 Agent task 흐름에서 생성되므로 `taskId`가 채워진 Agent 기반 승인이다(U7의 standalone과 출처가 다름)
- 지원되지 않는 작업(`RESOURCE_SCALING`/`AUTOSCALING_CHANGE`/`RESOURCE_CLEANUP`)은 승인을 만들지 않고 즉시 감지·설명 후 거부한다 — 실행 경로가 없는데 승인만 만드는 것은 "정직하지 않다"는 설계 원칙(design)
- `Approval` 생성 여부는 `InfraOperation.approvalRequired()`(지원되며 serviceImpact 또는 costImpact가 있는 작업만) 규칙 하나로 결정된다
- `@Transactional`이 아님(STATUS_CHECK/LOG_VIEW가 Docker+GitHub Actions I/O로 fan-out하므로 커넥션 풀 점유를 피함) — `PreviewContainerOpsService`/`DeploymentFailureAnalysisService`와 동일한 설계 선택

## 2.23 결과 승인 2단계 게이트 (Issue #56, PR #61, 2026-07-18 병합)

제품 모델(4단계): 컨테이너/preview 서버 → ①git preview 브랜치 → ②git main(**결과 승인**의 대상) → ③GitHub Pages 공개(**배포 승인**의 대상). git 반영(②)과 Pages 배포(③)는 서로 다른 단계이며, main에 merge되어도 라이브 사이트는 자동으로 바뀌지 않는다(Pages는 별도 workflow 산출물을 서빙).

- V28: `project_approval_policies.result_approval_required`(기본 `true`) + `project_changes`에 `approval_id`/`pr_number`/`merge_commit_sha`/`merged_at` 컬럼 추가
- `ApprovalType.RESULT` 신설 — 다른 4개 타입(CHANGE/DEPLOYMENT/DOMAIN_BINDING/INFRA_OPERATION)은 모두 "실행 전 계획"을 승인하지만, RESULT는 유일하게 "이미 실행된 결과"를 승인한다
- `ResultApprovalGate`(agent 도메인): plan의 **마지막 CODE step**이 끝난 직후 1회만 평가한다. 발동 조건: 프로젝트가 GitHub 저장소에 BOUND && `resultApprovalRequired` 정책이 켜져 있음. 신규/미연결 프로젝트(BOUND 아님)는 게이트 대상에서 제외되어 기존 DEPLOY step 흐름 그대로 첫 배포를 진행한다(반영할 저장소가 아직 없으므로)
- 게이트 발동 시: `PreviewBranchPushService`(DeployAgentService와 공유하는 신규 서비스)로 CODE 결과를 preview 브랜치에 push → task를 새 상태 `WAITING_RESULT_APPROVAL`로 전환(`TaskStore.RUNNABLE_STATUSES` 밖이라 워커가 절대 claim할 수 없음, 오직 사람의 승인/거절/취소로만 벗어남) → `RESULT` Approval 생성 → "미리보기와 변경 내역을 확인해 주세요" assistant 메시지 발송
- 여러 CODE step이 있는 plan(예: build 실패 후 재시도)도 RESULT 승인은 1번만 열린다 — 마지막 CODE step 완료 시점까지의 누적 결과 전체를 대상으로 한다
- 승인(`ApprovalCommandService.approve`, RESULT 분기) → `ResultApprovalService.reflect`(같은 트랜잭션 안): `preview`에 main 대비 새 커밋이 있으면 PR 생성+merge, 없으면(멱등 재시도·순수 no-op) main HEAD SHA만 기록 → `Change`를 `MERGED`로 전환(`approvalId`/`prNumber`/`mergeCommitSha`/`mergedAt` 기록) → `AgentOrchestrator.resumeAfterResult`로 task를 이어서 진행(남은 step이 있으면 계속, 없으면 DONE)
- 거절 → `Change`를 `REJECTED`로 전환(merge 없음), task는 `CANCELLED`. 거절된 커밋은 preview 브랜치에서 지우지 않고 그대로 남긴다(**누적 시맨틱**) — 다음 작업은 그 위에 이어서 커밋되고, 다음 RESULT 승인은 "그 시점 preview 브랜치 전체 상태"를 main에 반영한다(거절분이 자동으로 함께 포함될 수 있음, 사용자가 되돌리는 수정을 다시 요청하지 않는 한)
- `ChangeStatus`에 `MERGED`/`REJECTED` 2종 추가(기존 `PREVIEW_READY`/`DEPLOYED`에 더해)
- 직접 Deployment API(`DeploymentCommandService.prepareRelease`)의 자동 merge 규칙 변경: `resultApprovalRequired` 정책이 켜져 있으면, 프로젝트가 "한 번도 배포된 적 없고(`currentVersion == null`) RESULT 게이트를 거친 적도 없는" 경우에만 예외적으로 자동 merge를 허용한다(신규 프로젝트 첫 배포용 예외 — D9 게이트가 아직 적용된 적 없는 프로젝트의 초기 공개까지 막으면 안 되므로). 그 외에는 merge 권한이 RESULT 승인으로 완전히 이관되어, 직접 배포 요청은 **main의 현재 상태만 공개**할 뿐 preview를 끌어오지 않는다. 정책이 꺼져 있으면 기존 동작(배포 시 자동 merge) 유지 — 회귀 없음
- `hasResultGateHistory`(project가 REJECTED 또는 MERGED Change를 하나라도 가졌는지)로 "한 번도 게이트를 거치지 않은 신규 프로젝트"인지 정확히 판별한다 — 이 값이 없으면 사용자가 명시적으로 거절한 내용을 직접 배포가 실수로 merge할 위험이 있었음(리뷰에서 확정된 결함, BLOCKING-1)
- 프로젝트 Chat 설정(`GET/PATCH .../settings/chat`) 응답에 `resultApprovalRequired` 필드 추가(4필드 → 5필드). `PATCH`에서는 이 필드만 예외적으로 nullable(null이면 현재 값 유지, 나머지 4필드는 여전히 필수)
- 리뷰에서 확정된 결함 수정(PR #61 후속 커밋): 거절된 변경이 다음 배포 시 자동 merge로 우회되지 않도록 차단, 취소된 task의 게이트 상태 복구, 게이트 진입 직전 취소 여부 재확인(느린 preview push 직전 두 번째 취소 체크), merge의 비가역성을 고려한 상태 전이 순서 재조정(승인 상태 저장 → merge 실행 → task 재개, 이 순서가 뒤바뀌면 merge 실패 시에도 승인이 이미 확정된 것처럼 보일 위험)
- **신규 HTTP 엔드포인트 없음.** 기존 `POST/GET /agent/tasks/{taskId}`(상태에 `WAITING_RESULT_APPROVAL` 추가), `GET/POST /approvals/...`(타입에 `RESULT` 추가), `GET .../changes`(`MERGED`/`REJECTED` 상태와 병합 메타데이터 추가) 위에서 동작한다
- **PRD 정합화**: 이 기능은 사용자 확정 의도에 따라 PRD §15.2 문구도 함께 개정했다("배포 요청 시점에 merge" → "결과 승인 시점에 merge"). §15.2 외 PRD는 정본으로 유지된다

## 2.24 오케스트레이션 동시성/복구 하드닝 (Issue #55, PR #63, 2026-07-18 병합)

Approval/Change/PreviewSession 등 승인 파이프라인이 커지면서 드러난 감사(orchestration-audit) 결함을 구조적으로 해소했다. DDL 변경 없음, 신규 HTTP 엔드포인트 없음.

- **D1 write-skew 고착 해소(ADR-Y1, 락 계층 통일)**: 같은 task의 승인 2건이 동시에 결정되면(예: 두 명이 거의 동시에 승인 클릭) 각자 "다른 승인은 아직 PENDING"으로 잘못 판단해 실행을 트리거하지 못하고 task가 `WAITING_APPROVAL`에 영원히 멈추는 write-skew 버그가 있었다. `ApprovalCommandService.approve/reject`가 이제 **항상 먼저 `taskStore.lockTask`로 해당 task 행을 선점(비관적 잠금)한 뒤에** 그 task에 속한 승인들을 잠금 상태로 재조회한다 — task 단위 mutex가 같은 task의 모든 승인 결정을 완전히 직렬화하므로, 두 결정이 동시에 "형제 승인이 아직 PENDING"이라고 착각할 여지가 구조적으로 사라진다. 결과 승인(#56)·인프라 설정 standalone 승인(#62 B3)도 같은 락 순서를 따르도록 통일했다.
- **D1 복구(방어 심층화)**: `StuckApprovalSweeper`가 기본 60초 주기(`qeploy.agent.approval.sweep-interval-ms`, 환경변수 `QEPLOY_AGENT_APPROVAL_SWEEP_INTERVAL_MS`)로 "모든 승인이 이미 APPROVED인데 여전히 WAITING_APPROVAL인 task"를 찾아 `AgentOrchestrator.recoverStuckApprovedTask`로 재검증·복구한다. ADR-Y1 이후에는 이 상태가 새로 발생할 수 없으므로, 정상 운영에서는 매 스윕마다 후보가 0건이어야 한다 — 이 스윕은 (a) 배포 이전에 이미 고착돼 있던 것을 1회성으로 배출하고 (b) 향후 회귀가 재도입될 경우의 영구 안전망 역할만 한다.
- **D2 RUNNING 좀비 해소**: worker가 task를 claim했지만 실행자 풀이 포화 상태라 dispatch가 거부되면, 그 task를 `RETRY_WAIT`로 되돌린다. `AgentExecutionRegistry`(JVM 로컬, 실행자에 실제로 제출한 taskId만 등록)를 도입해 heartbeat 갱신 대상을 "이 worker가 실제로 실행 중인 task"로만 한정한다 — 이전에는 claim된 모든 task의 lease를 갱신해, 실행자 큐에 갇혀 실제로는 돌고 있지 않은 task도 계속 살아있는 것처럼 보여 `recoverExpiredLeases`가 만료 lease를 절대 회수하지 못하는(중복 실행 위험) 버그가 있었다. 등록은 실행자 제출 **직전**에 이루어져(실행 스레드 시작 시점이 아님) 큐 대기 구간도 보호한다.
- **부수 수정**: 거절 시 같은 task의 형제 `PENDING` 승인도 함께 `CANCELLED`로 전환(이전에는 거절만 취소되고 형제는 방치되는 비대칭이 있었음, 감사 G6). Build 실패 복구의 dedupe 요약 조회 로직 병합.
- 신규 통합 테스트(`OrchestrationConcurrencyIntegrationTest`, `ResultApprovalCancelRaceIntegrationTest`)로 동시 승인 결정·취소 경합 시나리오를 재현/검증.

## 2.25 감사 로그(Audit Log) 도메인 신설 (Issue #74, PR #75, 2026-07-25 병합)

BACKLOG EPIC 17 잔여(BI-188~192) 완료. `audit` 도메인 4계층 신설(V30, `audit_logs` 테이블 1개, 기존 테이블 무변경) + 서비스 10곳에 기록 훅 삽입. 설계서 `.agent-team/04-architecture/ad-audit-log-design.md`, 구현 노트 `.agent-team/08-impl-notes/backend.md` §26~27, 리뷰 `.agent-team/10-review/ad-audit-review.md`(Thomas, **APPROVE**, Blocking/High 0건) 참고.

- **모델(BI-188)**: `audit_logs`(V30) — `category`(GITHUB/DEPLOYMENT/DOMAIN/INFRA) · `action`(16종 고정 카탈로그, `AuditAction`) · `outcome`(SUCCEEDED/FAILED) · `actor_type`(USER/AGENT/SYSTEM) · `actor_user_id`/`project_id`/`resource_type`/`resource_id`/`task_id`/`approval_id`(전부 nullable, **FK 0개** — 삭제된 리소스에도 감사 행이 생존하고, 락 계층(#55 ADR-Y1)의 완전한 리프가 되도록 하는 의도적 설계) · `detail`(호출부 화이트리스트 조립, 최대 1000자) · `error_summary`(레닥션+500자 절단) · `created_at`. 인덱스 3개(`project_id[,category], id desc` 조회용 2개 + `created_at` retention 삭제용 1개).
- **기록 지점(BI-189~192, 훅 12곳·서비스 10개)**: 기존 이력 테이블(`deployment_histories` 등)을 대체하지 않고 횡단 보완한다 — "누가·무엇을·무엇에·언제·어떤 결과로"만 담고 도메인 상세는 `resource_id`로 참조만 한다.

  | 카테고리 | 기록 지점(서비스) | 액션 |
  |---|---|---|
  | GITHUB | `ProjectCommandService`(레포 생성/연결/해제/삭제) · `DeployAgentService`(Agent preview push) · `ResultApprovalGate`(게이트 preview push) · `ResultApprovalService`(RESULT 승인 집행 merge) | `REPOSITORY_CREATED/CONNECTED/DISCONNECTED/DELETED`, `PREVIEW_BRANCH_PUSHED`, `RESULT_MERGED` |
  | DEPLOYMENT | `DeploymentCommandService`(요청/재시도 큐잉, 워커 실패 확정) · `WebhookEventHandler`(workflow 성공/실패 확정) | `DEPLOYMENT_REQUESTED/RETRY_REQUESTED/SUCCEEDED/FAILED` |
  | DOMAIN | `DomainBindingCommandService`(연결/해제) | `DOMAIN_BOUND/DELETED` |
  | INFRA | `InfraOpsAgentService`(Cloud Ops RESTART, 실패도 기록) · `ProjectInfrastructureConfigurationService`(즉시 적용/승인 요청) · `InfrastructureChangeApprovalHandler`(승인/거절 집행) | `PREVIEW_RESTARTED`, `INFRA_CONFIG_CHANGE_REQUESTED/APPLIED/REJECTED` |

  읽기 작업·폴링·비터미널 실패(RETRY_WAIT 등)·승인 결정 자체(`approvals`가 정본)·환경변수 변경(U3 자체 이력으로 충분)은 의도적으로 기록하지 않는다(카탈로그는 닫힌 경계 — 확장은 코드 리뷰를 거친 카탈로그 개정으로만).
- **기록 메커니즘·비차단 계약(설계 ADR-A2)**: `AuditRecorder.record(event)`(공개 API 1개) → `AuditLogWriter.write`(`@Transactional(REQUIRES_NEW)`, 이 메서드는 SELECT 0회·INSERT 1회만 수행) 순서의 2-빈 구조. `AuditRecorder`가 `writer.write()` 호출을 try/catch로 감싸 **REQUIRES_NEW 커밋 실패까지 포함해 삼키고** `AUDIT_FALLBACK` 로그로 폴백한다 — 감사 기록 실패가 원래 요청(레포 연결, 배포, 도메인 연결 등)을 절대 실패시키지 않는다. 리뷰가 `REQUIRES_NEW→REQUIRED` 뮤테이션 실험으로 "외부 tx 롤백에도 감사 행 생존" 계약이 실제로 이 설정에 의존함을 직접 검증했다(원본 3 tests 0 failed → 뮤테이션 1 failed, 정확히 그 계약을 검증하는 테스트만 빨갛게 됨).
- **락 계층 정합(#55 ADR-Y1)**: `audit_logs`는 FK 0개 + `AuditLogWriter.write`가 SELECT 없이 INSERT 1회만 수행해 락 계층의 리프다. `git diff main..danto/audit-log`로 `approval/**`·`agent/infrastructure/store/**`(TaskStore)·오케스트레이터 락 경로 변경 0줄을 리뷰가 직접 확인했다. task 락 보유 중 호출되는 훅은 `ResultApprovalService.reflect`(RESULT 승인 집행) 1곳뿐이며, 추가 락 보유 시간은 INSERT 1회 왕복(≈1ms) 수준이라 기존 상한(reflect p95 <10s)에 무시 가능하다.
- **민감정보 정책**: `detail`은 호출부가 조립한 화이트리스트 텍스트만(자유 입력·외부 응답 원문 이어붙이기 금지). `error_summary`는 저장 계층(`AuditLog.from`)에서 **무조건** `common/security/SecretRedactor.redact()`(U6 `DeploymentFailureAnalysisService`의 `SECRET_PATTERN`을 이관해 공용화, GitHub PAT·AWS AKIA/ASIA·Slack xox·JWT eyJ·Bearer) + 500자 절단을 거친다. 토큰/시크릿/환경변수 값/로그·webhook payload 원문은 절대 저장하지 않는다.
- **조회 API(설계 §6, 리드 확정)**: `GET /api/v1/projects/{projectId}/audit-logs` 1개 신설(공개 API 90→91, `api.md` §15 참고) — 소유자 404, `category`/`limit`(기본 50/최대 200) 필터, `audit_log_id desc` 고정, offset 페이징 없음. 신규 `ErrorCode` 없음.
- **retention(설계 ADR-A7, 제안값)**: `AuditLogRetentionScheduler`가 `qeploy.audit.retention-days`(기본 180일) 이전 행을 `qeploy.audit.retention-sweep-interval-ms`(기본 3600000ms=1시간) 주기로 500행씩 반복 삭제. `AuditLogRepositoryAdapter.deleteBatch`에 설계서에 없던 `@Transactional`을 실 DB 테스트로 보완(Spring Data 커스텀 `@Modifying` 쿼리는 `@Scheduled` 메서드 자체엔 암묵적 트랜잭션이 없어 최초 구현이 매번 실패했던 지점).
- **BI-195(권한 최소화) 분할**: 이번 단위는 ①감사 로그 접근 최소화(소유자 404, 관리자/전체 조회 표면 없음) ②감사 데이터 최소화(레닥션·화이트리스트) ③권한 사용의 가시화(카탈로그 16종이 PRD §11.4가 열거한 GitHub 권한 사용 작업과 파괴적 작업을 사후 열람 가능하게 함)까지만 **부분 완료**했다. GitHub App 설치 권한 재검토·G5(컨테이너 `/tmp/.git-credentials` 평문 개선)는 보안 성격의 별도 후속으로 분리되어 아직 이슈 미신설이다(`ROADMAP.md` "이후 백로그" 참고).
- **리뷰 후속 정리(PR #75, 7840821)**: Thomas 리뷰의 Medium 1건(`AUDIT_FALLBACK` 폴백 로그에 `errorSummary`가 빠져 있던 결함 — 감사 자체가 실패했을 때 원인을 알 수 있는 유일한 경로였던 만큼 우선 수정, 로그 노출 전 레닥션 적용)과 Low 3건(주석의 존재하지 않는 줄 번호/테스트클래스 참조 정정, `AuditLog`의 restore 생성자를 `private`+`restore()` 팩토리로 강화)을 커밋 1개로 정리. 641/641.
- **설계서와 다르게 구현한 지점(보수적 선택, 구현 노트 §26.2)**: H4(`DeployAgentService`)는 3개 push 분기 각각이 아니라 공통 `if (sourceChanged)` 합류 지점 1곳에서 기록(분기당 1회 보장은 동일). H10(`DomainBindingCommandService.bindDomain`)도 두 사설 메서드 내부가 아니라 공개 메서드 1곳에서 기록. `AuditLogRetentionScheduler`/어댑터에 설계서에 없던 `@Transactional` 보완. H9는 `isLatestProjectDeployment` 가드와 무관하게 감사 기록을 독립시킴.
- **테스트**: `./gradlew test` → **641 tests, 0 failures**(기존 594 + 신규 47). Flyway V1~V30 연속.

## 2.26 Preview 컨테이너 호스트 포트 외부 노출 차단 (Issue #76, BI-081/G1, PR #78, 2026-07-25 병합)

BACKLOG BI-081(외부 공개 URL 차단) 중 G1 — 컨테이너 호스트 포트가 게이트웨이·accessToken·Spring Security를 전부 우회해 외부에 직접 노출되던 결함을 해소했다. §2.16 BI-194(컨테이너 간 격리) 정책과 같은 계열이며, BI-194가 컨테이너↔컨테이너 통신을 막는 것과 달리 이번 단위는 호스트→외부 방향의 노출을 막는다. DDL 변경 없음, 신규 엔드포인트·프로퍼티 없음.

- 결함: `DockerContainerService`가 호스트 포트를 `Ports.Binding.bindPort(0)`(HostIp 미지정)으로 바인딩해, Docker가 이를 `0.0.0.0` + IPv6 `::` 전체 인터페이스에 퍼블리시했다. 게이트웨이(`/api/v1/previews/{sessionId}/{accessToken}/**`)를 거치지 않고 호스트의 동적 포트에 직접 접속하면 인증 로직이 없는 컨테이너 내부 정적 서버(`npx serve`)에 그대로 도달할 수 있었다(감사 `.agent-team/01-reverse/preview-exposure-audit.md` G1).
- 수정: 바인딩을 `Ports.Binding.bindIpAndPort("127.0.0.1", 0)`으로 전환해 HostIp를 loopback으로 명시(포트는 여전히 0=동적 할당). 컨테이너 내부 포트(3000)와 포트 퍼블리시 자체는 유지 — `PreviewGatewayService`가 이미 `127.0.0.1:hostPort`로만 프록시하므로 정상 경로(게이트웨이 접근)는 무영향.
- 범위: G1(컨테이너 포트 노출)만. 게이트웨이가 accessToken 일치 검사 하나로만 인가하고 세션 소유권·JWT를 검증하지 않는 **G2**, accessToken 회전·폐기가 없어 유출 시 TTL 동안(접근할 때마다 touch로 연장) 재사용 가능한 **G4**는 **Issue #77**로 분리해 미착수로 남겼다 — 무헤더 accessToken이 iframe 임베딩을 위한 의도된 설계라 수정 시 FE(`Dvely_FE_test`) 조율이 필요하다(§3.5 참고).
- 검증: 실 Docker 통합 테스트 2건(최초 생성 시 단일 loopback 바인딩 / restart로 포트가 재할당된 뒤에도 loopback 유지 + 포트 변경 자체를 단언) + mock 단위 테스트 1건 신설. 리뷰(Thomas, **APPROVE** — `.agent-team/10-review/ae-preview-exposure-review.md`)가 뮤테이션 실험(수정을 되돌려 신규 테스트 3건 모두 red 확인)과 별도 실 Docker 재현(loopback 접속 성공/호스트 LAN IP 접속 거부, `docker exec` 경유 npm install 등 아웃바운드·bridge egress NAT 무영향)으로 회귀 가드 실효성과 정상 경로 무회귀를 재확인.
- 운영 전제: 게이트웨이(Spring)와 Docker 데몬이 **동일 호스트**여야 한다는 기존 전제(`PreviewGatewayService`가 이미 `127.0.0.1`로 프록시)를 이번 변경이 코드로 명시적으로 강제하게 되었다(신규 제약 아님). 멀티호스트/원격 Docker로 전환할 경우 이 loopback 바인딩과 게이트웨이 프록시 대상을 함께 재검토해야 한다.
- 테스트: **644 tests, 0 failures**(기존 641 + 신규 3). Flyway V1~V30 연속(스키마 변경 없음).

운영 한계:

- G2(게이트웨이 소유권·JWT 미검증 + `permitAll`)·G4(accessToken 회전·폐기 없음)는 Issue #77로 남아 있다(§3.5 참고).

---

## 2.27 프로젝트 단위 프리뷰 — 작업 지시 없이 현재 상태 보기 (Issue #94, PR #92, 2026-08-14 병합)

프리뷰 세션을 만들 수 있는 주체가 Agent CODE 스텝뿐이라(`PreviewSessionService#acquire(taskId)`의 유일한 호출처), 프로젝트에 진입하거나 저장소를 막 연결한 시점에는 볼 수 있는 화면이 없었다. 배포 전에 "지금 이 프로젝트가 어떻게 보이는지"를 확인할 경로를 열었다.

- 엔드포인트 2개: `GET /projects/{id}/preview-session`(있으면 200, 없으면 204), `POST /projects/{id}/preview-session`(살아 있는 세션에 붙으면 200, 새로 준비 시작하면 202, 저장소 미연결이면 409). 컨테이너를 새로 띄우는 비용 때문에 생성은 사용자의 명시적 행동(버튼)에만 묶여 있다.
- 세션 종류 구분은 `task_id` NULL 여부다(V31: `task_id` NOT NULL → NULL 허용, FK 유지). NULL이면 프로젝트에 매달린 "현재 상태" 프리뷰, 값이 있으면 그 작업이 만든 결과 프리뷰이며, 조회는 둘 중 가장 최근에 접근된 하나를 돌려준다 — 작업 이후에는 자연스럽게 그 결과가 보인다.
- 준비 절차(clone → npm install → build → serve)를 `PreviewWorkspaceService`로 분리해 CODE 스텝과 공유한다. 각자 들고 있으면 한쪽만 고쳐졌을 때 두 프리뷰가 서로 다른 브랜치·디렉터리를 서빙하게 된다.
- 준비는 수 분이 걸려 `previewExecutor`(core 2 / max 3)에서 비동기로 돈다. 이를 위해 세션 상태에 `PROVISIONING`·`FAILED`를 추가했고, 실패 사유는 빌드 로그 꼬리와 함께 `failure_reason`(V31)에 남긴다 — 실패한 컨테이너는 즉시 제거되어 `/logs`로는 원인을 볼 수 없기 때문이다. 준비 중 앱이 재시작되어 남은 세션은 만료 청소기가 `FAILED`로 회수한다.
- 조회는 ACTIVE 행이어도 컨테이너 생존(`inspect` 1회)을 확인한 뒤에만 URL을 준다. 세션 행은 컨테이너보다 오래 살 수 있어(데몬 재시작 등) 행만 보고 답하면 FE가 죽은 주소를 iframe에 걸고 502를 본다.
- 동시 요청(버튼 더블클릭)은 잠금 대신 저장 후 중재로 푼다: 준비 중인 세션 중 가장 먼저 만들어진 하나만 남고 진 요청은 컨테이너를 즉시 반납한다.
- 테스트: **700 tests, 0 failures**. Flyway V1~V31 연속(V31 신설).

운영 한계:

- 이미 서빙 중인 세션은 갱신하지 않는다. 외부에서 repo에 push한 뒤라면 붙은 프리뷰가 구버전일 수 있고, 현재는 `DELETE /preview-sessions/{id}` 후 재요청으로 해결한다(`?refresh=true`는 미착수).
- 프로젝트 프리뷰와 작업 프리뷰가 동시에 떠 있을 수 있다(각 1 GiB · 1 vCPU). TTL 30분과 접근 시 연장 규칙은 기존과 동일하다.
- 감사 로그(`AuditAction`)에는 남기지 않는다 — 카탈로그가 설계 차원에서만 개정하는 닫힌 목록이다(§2.25).

---

## 2.28 배포 환경 프리뷰 주소 오리진·실행 환경 오류 보정 (Issue #95, PR #96, 2026-08-15 병합)

§2.27을 실제 배포 환경에서 쓰자 두 가지가 드러났다. 운영은 프리뷰 생성이 `500 서버 내부 오류`로 끝났고, 개발 서버는 성공했지만 `previewUrl`이 `http://localhost:8080/...`이라 브라우저가 연결을 거부했다. 스키마 변경 없음, 신규 엔드포인트 없음.

- 주소 원인: `qeploy.preview.gateway-base-url`의 기본값이 로컬 주소인데 `QEPLOY_PREVIEW_GATEWAY_BASE_URL`이 dev/prod yml과 `ecosystem.config.js.example` 어디에도 없었다. 이 값은 틀려도 API가 200을 주므로 잘못된 주소가 조용히 발급됐고, Agent 작업이 만드는 `previewUrl`도 같은 문제를 갖고 있었다.
- `PreviewGatewayUrlResolver` 신설: 설정값 → CORS 허용 오리진 → 로컬 기본값 순으로 기준 오리진을 정하고 결정 근거를 기동 로그에 남긴다. CORS를 두 번째 후보로 쓰는 근거는 FE와 API가 같은 오리진을 쓴다는 점과, FE가 API를 호출하고 있다는 사실 자체가 그 값이 맞다는 증거라는 점이다 — 서버에 환경변수를 추가하지 않아도 열리는 주소가 나온다. 로컬은 FE(5173)와 API(8080) 오리진이 달라 루프백은 후보에서 제외한다.
- 운영 프로파일 기준 오리진을 `https://qeploy.com`으로 명시하고 `ecosystem.config.js.example`에 키를 추가했다. `previewUrl` 생성은 작업 프리뷰·프로젝트 프리뷰가 같은 리졸버를 쓰도록 한 곳으로 모았다.
- 500 원인: Docker다. `DockerContainerService`가 첫 컨테이너 요청까지 연결을 미루므로 미설치·소켓 권한 없음 상태로도 앱이 정상 기동하고(`deploy/README.md` §4의 기존 경고), 컨테이너를 띄우는 순간 처음 실패한 예외가 catch-all 500으로 뭉개져 원인이 사라졌다.
- `PreviewEnvironmentUnavailableException` → **503 `PREVIEW_ENVIRONMENT_UNAVAILABLE`**로 갈라내 원인 메시지를 응답에 싣고, `PreviewEnvironmentHealthLogger`가 기동 직후 데몬 핑을 한 번 넣어 설치·권한 명령이 담긴 경고를 남긴다. 서버에 붙어 `docker ps`를 치는 확인을 앱이 대신하게 하는 것이 목적이다.
- 테스트: **707 tests, 0 failures**(기존 700 + 신규 7). 스키마 변경 없음(Flyway V1~V31 유지).

운영 한계:

- 이 단위는 500의 **원인을 드러낼 뿐 원인을 없애지는 못한다.** 운영 서버에 Docker가 없거나 앱 계정이 `/var/run/docker.sock`에 접근하지 못하는 상태라면 `deploy/README.md` §4의 설치·권한 절차가 필요하며, 이는 코드가 아닌 운영 조치다.

---

## 2.29 프리뷰 문서의 오리진 격리 (Issue #102, PR #103, 2026-08-15 병합)

프리뷰 문서가 서비스 본체와 같은 오리진(`https://qeploy.com/api/v1/previews/...`)에서 실행되고 있었다. FE(`Dvely_FE` `dcef18f` 실측)는 이 문서를 `sandbox` 속성 없는 iframe으로 띄우고 서비스 JWT를 `localStorage`에 두며, 게이트웨이 응답에도 격리 헤더가 없었다. 그 조합에서 프리뷰로 서빙되는 사용자·Agent 작성 코드가 `parent.localStorage.getItem('accessToken')` 한 줄로 부모 창의 토큰을 읽어갈 수 있었다 — #77의 G2(URL을 아는 자 = 접근 허가)보다 심각하며, URL 유출조차 필요 없다.

- 프록시 응답에 `Content-Security-Policy: sandbox allow-scripts allow-forms allow-popups allow-modals; frame-ancestors 'self'` 추가. `allow-same-origin`을 넣지 않아 문서가 불투명 오리진을 갖게 되는 것이 핵심이며, 이 조건을 회귀 테스트로 고정했다.
- HTML뿐 아니라 모든 프록시 응답에 적용한다 — 프리뷰 앱이 자기 JS를 어떤 Content-Type으로 내보내든 실행 컨텍스트는 같아야 한다.
- 서버 헤더만으로 닫히므로 FE 배포를 기다리지 않는다. FE는 iframe `sandbox` 속성을 따로 걸 필요가 없다.
- 테스트: **710 tests, 0 failures**(기존 707 + 신규 3). 스키마 변경 없음.

운영 한계:

- 불투명 오리진이므로 **프리뷰 앱 자신의** `localStorage`·쿠키도 동작하지 않는다. 정적 빌드 미리보기 용도에서는 수용 가능한 대가로 판단했고, 그 기능이 필요해지면 프리뷰 전용 오리진 분리가 정답이다.
- 이 단위는 **격리**만 다룬다. 게이트웨이 인가(#77 G2)와 accessToken 회전(#77 G4)은 여전히 열려 있다.

---

## 2.30 프리뷰 게이트웨이 인가 — 소유권 쿠키 + accessToken 회전 (Issue #77, PR #104, 2026-08-15 병합)

§2.26에서 Issue #77로 분리해 두었던 G2·G4를 해소했다. 게이트웨이(`GET /api/v1/previews/{sessionId}/{accessToken}/**`)의 인가가 URL 안 accessToken 일치 검사 하나뿐이라 "URL을 아는 자 = 접근 허가" 모델이었고(G2), accessToken은 세션 생성 시 한 번 만들어진 뒤 회전·폐기 경로 없이 접근할 때마다 touch로 만료가 연장되어, 유출된 주소가 세션 수명 내내 살아 있었다(G4).

- **G2 — 소유권 쿠키**: iframe은 헤더를 실을 수 없으므로 소유권 증명을 쿠키로 옮겼다. 인증된 요청 `POST /preview-sessions/{sessionId}/access`(신규)가 소유자를 확인하고 `HttpOnly` 쿠키(`qeploy_preview_access`)를 발급한다. 값은 `sessionId:ownerUserId:exp`를 HMAC-SHA256으로 서명한 문자열(`OAuthStateManager`와 같은 방식·같은 키)이라 DB 컬럼을 늘리지 않았고, 검증(`PreviewAccessCookies`)이 서명·만료·대상 세션·소유자 일치를 전부 보므로 A 세션 쿠키로 B 세션을 열 수 없다. `Path=/api/v1/previews/{sessionId}/`로 좁혀 다른 세션·다른 API 경로로 새지 않으며, `Secure`는 게이트웨이 오리진이 https일 때만 붙이고(로컬 http 개발 보호), `SameSite=Lax`는 FE·게이트웨이가 같은 사이트라는 현재 배치를 전제로 한다. 게이트웨이는 쿠키가 없거나 불일치하면 **401** — 세션 없음/토큰 불일치의 404와 구분되는, FE가 재발급으로 복구할 수 있는 신호다.
- **G4 — 토큰 회전**: 같은 발급 호출이 accessToken을 회전시킨다(32hex 랜덤 재생성 + `publicUrl` 갱신). 호출자가 곧바로 새 주소를 화면에 걸기 때문에, 채팅 기록·브라우저 히스토리로 흘러나간 예전 주소의 수명이 "소유자가 다음에 프리뷰를 여는 시점"으로 제한된다. 응답은 `{ sessionId, previewUrl, expiresAt }` — 이전 주소(작업 응답의 `previewUrl` 포함)는 즉시 404가 된다.
- 남의 세션은 404로 통일(존재 여부 은닉 — close/status와 같은 계약), 종료·만료·PROVISIONING 세션은 409. 쿠키 maxAge는 세션 잔여 수명과 TTL 중 짧은 쪽 — 쿠키가 세션보다 오래 살 이유가 없다.
- **호환 스위치**: `qeploy.preview.require-access-cookie`(기본 **true**). FE가 발급 호출을 아직 배포하지 못한 환경만을 위한 임시 스위치로, false면 예전 "URL을 아는 자 = 접근 허가" 모델로 되돌아가므로 기본값이 안전한 쪽이어야 한다.
- **FE 연동**: `Dvely_FE` **PR #30 머지·배포 완료**(`feature/preview-access-grant`, main `1717c69`, 2026-08-15 Deploy to EC2 성공) — 세션 ACTIVE 시 iframe 표시 직전 access 발급 호출(`usePreviewAccessQuery`)로 전환, 회전된 `previewUrl`만 사용, 자동 refetch(포커스·재연결)는 전부 차단(떠 있는 iframe의 주소가 뒤에서 무효화되는 것 방지), 발급 실패 시 세션 재동기화 + 재시도 진입점 유지. 회전 모델과 상충하는 미사용 헬퍼 `getPreviewProxyPath`는 제거했다.
- 문서: `docs/FRONTEND_API_GUIDE.md` §4.10에 "표시 직전 권한 발급(④)" 필수 흐름과 회전 주의를 추가.
- 테스트: **726 tests, 0 failures**(기존 710 + 신규 16). DDL 변경 없음(V1~V31 연속) — 쿠키가 자체 서명 방식이라 컬럼 추가가 없다.

운영 한계:

- 배포 순서: 백엔드만 먼저 배포되면 구 FE는 쿠키 없이 401을 본다. FE 배포 전까지 임시로 `require-access-cookie=false` 경로가 있으나, 끈 동안 G2 갭이 그대로 열린다. (2026-08-15 후속: 운영 서버 Docker 권한 문제는 `ops-restart-pm2-daemon.yml` 워크플로로 해소했고, FE PR #30도 같은 날 머지·배포 완료 — 배포 순서 공백 없이 전 구간이 운영 반영됐다. ROADMAP §1 참고.)
- `SameSite=Lax`는 FE·게이트웨이 동일 사이트 배치 전제 — 프리뷰를 전용 오리진으로 분리하면(§2.29 운영 한계) `None` 전환이 필요하다.

---

## 2.31 게이트웨이 서브리소스 인가·CORS 수정 — 프리뷰 백지 해소 (Issue #108, PR #109 + PR #110, 2026-08-15 병합)

전 구간 배포 후 첫 운영 스모크에서 프리뷰 iframe이 백지로 떴다. §2.29(CSP `sandbox` → 불투명 오리진)와 §2.30(소유권 쿠키)의 상호작용으로, 두 단위 모두 운영 프리뷰가 Docker 권한 문제로 미기동인 기간에 머지되어 실브라우저 조합이 이번에 처음 검증됐다.

- 원인 1: 불투명 오리진 발 서브리소스 요청은 cross-site 판정이라 `SameSite=Lax` 소유권 쿠키가 실리지 않음 → 게이트웨이 401(`vite.svg`). 문서 자체(iframe 탐색)는 FE 페이지가 시작한 same-site 요청이라 쿠키가 실려 200 — 그래서 "문서는 열리는데 안이 백지"가 된다.
- 원인 2: Vite 빌드의 `<script type="module">`은 항상 CORS 모드로 로드되는데 프록시 응답에 `Access-Control-Allow-Origin`이 없어 메인 번들 차단. `SameSite=None`으로도 해결 불가 — module script는 credentials 자체를 보내지 않는다.
- 원인 3(PR #110에서 발견): 전역 CORS(`/**` — FE 오리진 목록 + `credentials=true`)가 `Origin: null` 요청을 **컨트롤러 도달 전에 403**으로 거절. PR #109가 프록시에 단 ACAO는 살아 있었지만 요청이 거기까지 가지 못했다 — 운영 curl 재현으로 확정(`Origin: null` → 403, Origin 없음 → 200+ACAO). 1차 스모크에서는 서브리소스 401과 겹쳐 이 층이 보이지 않았다.
- 수정(PR #109): 쿠키를 **문서 탐색에만** 요구한다(`Sec-Fetch-Dest`가 `document/iframe/frame/embed/object`이거나 **헤더 부재** 시 — curl 등 비브라우저는 안전측으로 쿠키 게이트 유지). 서브리소스는 URL의 회전 accessToken이 자격.
- 수정(PR #110): `/api/v1/previews/**`에 **credentials 없는 전체 허용 CORS**를 `/**`보다 먼저 등록(`SecurityConfig`) — `null` 오리진은 누구나 sandbox iframe으로 만들 수 있어 목록으로 좁혀도 효과가 없다. 프록시가 직접 달던 ACAO는 제거 — CorsFilter의 것과 중복되면 브라우저가 "multiple values"로 거절하므로, **안 다는 것**을 테스트로 고정했다. 다른 API 경로의 FE 오리진·credentials 정책은 불변.
- 운영 재검증(배포 후 curl): 서브리소스(`Origin: null`+`Sec-Fetch-Dest: script`) 200 + ACAO 단일 값 / 문서 탐색(쿠키 없음) 401 / 무헤더 curl 401 — G2 게이트 유지 확인.
- 테스트: **731 tests, 0 failures**(기존 726 + 신규 5: 서브리소스 무쿠키 200 / 탐색 무쿠키 401 유지 / 프록시 무ACAO 계약 / previews 경로 CORS `checkOrigin("null")=="*"` / 타 경로 credentialed 정책 유지). FE 변경 없음, DDL 없음.

운영 한계:

- `Sec-Fetch-Dest`는 브라우저 신호라 위조 가능 — **현재 유효한** 토큰 URL 보유자는 다음 회전 전까지 서브리소스를 읽을 수 있다(의도적 수용). 문서 탐색 게이트 + 매 열람 시 회전이 노출 창을 소유자의 다음 열람 시점까지로 제한하며, 완전한 해소는 프리뷰 전용 오리진 분리(백로그, §2.29부터 예고)다.
- 운영 프리뷰 HTML에 Cloudflare가 `/cdn-cgi/rum` 비컨을 주입해 콘솔에 CORS 에러가 남는다 — 무해한 소음이며, 거슬리면 Cloudflare 대시보드에서 RUM을 끄면 된다.

---

## 2.32 프리뷰 빌드 base 흡수 — 배포용 base로 구운 앱의 백지 해소 (Issue #111, PR #112, 2026-08-15 병합)

§2.31로 CORS·인가 층을 뚫자 그 아래 층이 드러났다. 앱이 GitHub Pages 배포용 base(`/my-todo-app/` — repo명)로 빌드되면 index.html이 `{prefix}my-todo-app/assets/...`를 참조하는데, 컨테이너는 빌드 산출물 **루트**를 서빙하므로 그 경로에 파일이 없다. `serve -s`는 없는 경로에 200 + index.html을 주기 때문에 브라우저가 스타일시트·모듈 자리에서 HTML을 받아 MIME을 거부하고 화면이 백지가 됐다.

- **base 자체는 잘못이 아니다**: 배포 워크플로는 Vite에 `vite build --base=${{ steps.base.outputs.path }}`를 주어 커밋된 값을 덮어쓰고(`DeployWorkflowTemplate:362`), CRA는 빌드 전 `package.json`의 homepage를 강제로 다시 쓴다(:188-195). 즉 커밋된 base는 **배포 결과에 영향이 없고** 순수 `npm run build`인 프리뷰 빌드(`PreviewWorkspaceService:132-134`)에만 반영된다 — 배포와 프리뷰가 갈리는 지점이 정확히 여기다. CODE 스텝 프롬프트에는 base 지시가 없어(`CodeAgentService:65-99`) LLM이 Pages 관행으로 넣은 값이며, preview 브랜치에 커밋되어 고착된다.
- **수정은 빌드가 아니라 프록시에서**: 확장자 있는 자산 요청에 `text/html`이 돌아오면 경로 어긋남의 신호로 보고 선행 세그먼트를 벗겨 재요청한다(최대 2단계, 실패 시 원래 응답 유지). base를 감지·저장하지 않아 모든 툴체인·모든 base 형태에 동작하고, **JS 번들에 인라인된 동적 import 청크 경로까지 본문을 건드리지 않고 살아난다**. 확장자 없는 SPA 라우트는 자산으로 보지 않아 `serve -s`의 index.html fallback이 유지된다(딥링크 새로고침 경로).
- **재빌드 안을 버린 이유**(다중 에이전트 적대 심사): `--emptyOutDir`를 휴리스틱으로 찾은 디렉터리에 겨누면 `vite.config`에 `root`나 `outDir: 'docs'`를 쓰는 프로젝트에서 **사용자 소스가 삭제된 채 `git add -A` → preview 브랜치로 push**되는 경로가 확인됐다. 상대경로 base(`./`) 안은 현재 절대 prefix 치환이 갖는 문서 깊이 독립성을 파괴해 SPA 딥링크에서 자산이 깨진다. JS 본문 치환 안은 대용량 번들 String 왕복이 게이트웨이 힙을 크게 쓰는데 프록시에 크기·동시성 상한이 없다.
- 테스트: **736 tests, 0 failures**(기존 731 + 신규 5). 뮤테이션 실험(호출 제거 시 base 관련 2건 red, 보존 가드 3건 green 유지)과 실제 `serve@14 -s` 실측(`/my-todo-app/assets/app.js` → 200 `text/html` 확인)으로 트리거 전제까지 고정. DDL 없음, FE 변경 없음.

운영 한계:

- 앱이 `basename={import.meta.env.BASE_URL}`(React Router)을 쓰면 자산이 전부 로드돼도 라우터가 빈 화면을 렌더한다 — 프리뷰 URL과 basename이 구조적으로 불일치하기 때문이며, 이 수정의 범위 밖이다(관측된 앱은 번들에 base 문자열이 없어 해당 없음). **Issue #117**로 분리.
- 조사 중 확인된 별개 결함 2건도 분리했다: **Issue #115**(`pkill -f 'npx serve'`가 실제 프로세스를 못 잡아 낡은 산출물이 계속 서빙 + `exec` 종료코드 미확인으로 빌드 실패가 조용함), **Issue #116**(Next/SvelteKit/Gatsby/Astro/Nuxt는 커밋된 config의 base가 이겨 커스텀 도메인에서 자산 404).

## 2.33 프리뷰 콘솔의 CDN beacon CORS 에러 제거 (Issue #113, PR #114, 2026-08-15 병합)

프리뷰가 정상 동작하게 된 뒤 콘솔에 `/cdn-cgi/rum` CORS 에러 하나만 남았다. Cloudflare가 이 zone의 HTML 응답에 자기 RUM beacon을 자동 주입하는데(`curl https://qeploy.com/` 응답에서 확인), 프리뷰 문서는 §2.29의 CSP `sandbox`로 불투명 오리진이라 beacon의 POST가 cross-origin이 되고 엣지가 `Origin: null`에 ACAO를 주지 않아 차단된다. 우리 서버에 닿지도 않는 요청이라(엣지가 404 처리) 기능 영향은 없지만, 프리뷰를 여는 사람에게는 자기 앱의 에러처럼 보인다.

- 수정: 프리뷰 **HTML 응답에만** `Cache-Control: no-store, no-transform`. Cloudflare는 `no-transform`이 있으면 payload를 변형하지 않아 주입 자체가 일어나지 않는다. 주입은 HTML에만 일어나므로 자산 응답은 `no-store`만 유지해 CDN 압축을 잃지 않는다. 프리뷰는 불투명 오리진이라 RUM이 지금도 아무것도 수집하지 못해 잃는 데이터가 없고, 헤더가 프리뷰 응답에만 붙으므로 본 사이트 Web Analytics는 그대로다.
- 테스트: **737 tests, 0 failures**(신규 1건 — 문서에는 `no-transform`, 자산에는 미부착, `no-store`는 양쪽 유지). DDL·FE 변경 없음.

## 2.34 요청 경로 성능 U10 (Issue #345, **PR #348 머지**)

인증 요청마다 붙던 미세 비용을 걷어냈다. 개별로는 작지만 **모든 인증 요청**에 붙고, 커넥션 풀이 마를 때 인증까지 함께 죽는 결합이 있었다.

- **JWT 파싱 1회**(10-1): 인증 필터가 `getUserId`/`getJti`를 따로 불러 요청마다 HMAC 키 생성 + 파서 빌드 + 서명 검증이 **두 번** 돌았다. `TokenPort.parseClaims`로 한 번에 받는다. 서명 키와 `JwtParser`는 시크릿이 바뀌지 않으므로 생성자에서 1회만 만든다. 로그아웃 경로(jti + 만료시각)도 같은 이유로 1회로 줄였다. 클레임 하나만 쓰는 `AuthController`를 위해 단건 getter는 남겼다.
- **폐기 토큰 캐시**(10-2): `revoked_access_tokens` 조회가 인증 요청마다 1회씩 나가고 있었다. `RevokedTokenCache`(Caffeine)를 앞에 뒀다. **캐시는 정답의 출처가 아니라 DB 앞의 단축 경로다** — 항목이 없으면 반드시 DB를 본다. 그래서 재기동으로 캐시가 비거나 크기 상한에 밀려도 폐기된 토큰이 되살아나지 않는다. "폐기됨"은 토큰 만료까지 붙잡고, "폐기 안 됨"만 짧은 TTL(기본 60초, `qeploy.auth.revoked-token-cache.not-revoked-ttl`)로 흘려보낸다. 폐기를 수행한 인스턴스는 반대편 항목을 즉시 지우므로 지연이 없고, 이 TTL은 **다중 인스턴스에서의 로그아웃 전파 지연 상한**이다. `0`을 주면 부정 캐시가 꺼져 이전 동작으로 돌아간다.
- **AES 컨버터 재사용**(10-3): `Cipher.getInstance`를 매 호출, `new SecureRandom()`을 매 암호화 하던 것을 각각 ThreadLocal 재사용·인스턴스 공유로 바꿨다. `UserEntity`의 GitHub 토큰 2개가 컨버터라 `userRepository.findById` 한 번마다 복호화가 2회 돈다. **암호문 형식은 그대로다** — 이전 구현으로 만든 실제 암호문을 상수로 박아 복호화되는 것을 테스트로 고정했다.
- **감사 로그 비동기**(10-4): 동기 `REQUIRES_NEW`는 쓰기 요청 하나가 자기 커넥션과 감사용 커넥션을 동시에 쥐게 했다. 전용 **단일 스레드** 실행기로 넘겨 감사 쓰기가 쓰는 커넥션을 앱 전체 통틀어 최대 1개로 묶었고, INSERT 순서도 호출 순서를 유지한다. 큐 포화 시엔 버리지 않고 호출 스레드에서 동기 실행한다. **`afterCommit`으로 미루지 않았다** — `REQUIRES_NEW`는 "바깥 트랜잭션이 되감겨도 감사 기록은 남는다"(ADR-A2)도 함께 뜻하고, `RepositoryProvisioningService`처럼 이미 일어난 외부 효과를 기록하는 훅이 있어 미루면 되돌릴 수 없는 효과의 유일한 증거가 사라진다. 비동기가 **새로 만드는 것은 가시성 지연**이다 — 쓰기 직후 `GET /audit-logs`를 부르면 아직 안 보일 수 있어 `api.md` §15.2에 명시했다(노출되는 조회 경로는 이 하나뿐이며, FE 가이드에는 감사 로그 조회 절 자체가 없다). 테스트는 `AuditLogExecutor.awaitDrained`로 이 경계를 확정적으로 넘는다(`Thread.sleep`·폴링 없음).
- **요청 상관관계 ID**(10-6): `RequestIdFilter`가 요청마다 ID를 MDC에 심고 응답 `X-Request-Id`로 돌려준다. 필터를 가장 앞에 둬 보안 필터 체인이 남기는 로그(인증 거부 등)까지 같은 ID를 단다. 클라이언트가 준 값은 `[A-Za-z0-9._-]{1,64}`일 때만 받는다 — 개행이 섞인 값을 MDC에 넣으면 로그 줄을 위조할 수 있다(로그 인젝션).
- 테스트: **1440 tests, 0 failures, 12 skipped**(신규 22건). 새 의존성 `com.github.ben-manes.caffeine:caffeine`(Spring Boot BOM 관리, 전이 의존성 없음). DDL·FE 변경 없음.
- 이슈의 10-5(`PreviewWorkspaceService` 빌드 로그 전문 출력)는 #332 구역이라 제외했다.

---

# 3. 해야 할 것

> 아래 항목은 여전히 PRD 대비 미구현이다. 실행 우선순위와 단위(Unit) 분할은 `.notion/ROADMAP.md`를 따른다. 각 소제목에 매핑되는 ROADMAP 단위를 표기했다.

## 3.1 프로젝트 Import

- ZIP 업로드 API
- zip-slip, 파일 크기, 확장자, 악성 파일 검사
- ZIP 압축 해제와 프로젝트 유형 분석
- ZIP import 후 저장소 연결 흐름
- GitHub 레포 가져오기 확인/진행 상태
- import Job과 실패 복구

`BACKLOG_STATUS.md`에서는 현재 제품 흐름(GitHub App + Agent 기반 생성/수정) 기준으로 MVP 범위 밖 Removed 항목으로 정리되어 있다. ROADMAP에는 아직 단위로 편성하지 않았다.

## 3.2 Change와 승인 확장

- Deployment/Domain/Infra Approval의 UI 연동 마감 범위 확인
- 승인/거절 이력의 노출 방식 확장
- Project Settings의 승인 On/Off 정책 확장(General/Release Policy 등과 연동)

## 3.3 영속 Job 확장

- Import, deployment, domain health까지 AgentRun과 동일한 공통 Job 모델로 통합
- 여러 서버 인스턴스 간 상태 공유 시나리오 확대 테스트

## 3.4 AI Workspace 백엔드 연결 — ROADMAP U2 (`danto/agent-chat`)

CHAT Agent 구현과 `ChatAgentService` 신설은 완료했다(§2.14 참고). 남은 항목:

- 프로젝트별 Chat 지침과 응답 상세도 (`ChatAgentService`는 현재 프로젝트 구분 없는 고정 system prompt만 사용한다)

## 3.5 Preview 운영/실행 확장 — ROADMAP U4 (`danto/preview-ops`)

컨테이너 상태/로그 조회 API와 BI-194 격리 정책 기본기(메모리/CPU/pids/capability/네트워크)는 완료했다(§2.16 참고). 컨테이너 호스트 포트의 외부 노출 차단(BI-081/G1)도 완료했다(Issue #76, §2.26 참고). 게이트웨이 인가 강화(G2 소유권 쿠키·G4 accessToken 회전)도 완료했다(Issue #77, §2.30 참고 — FE 연동 `Dvely_FE` PR #30도 머지·배포 완료). 남은 항목:

- dependency/build/image cache 정책
- Environment/Secrets 값의 Docker Preview/Deployment 런타임 실제 주입(§2.15의 `EnvironmentValueResolver`는 아직 HTTP·워크플로 어디에도 연결되지 않음)
- 정적 사이트 외 backend/fullstack/API/DB 프로젝트 실행

## 3.6 Environment / Secrets 런타임 연결 — ROADMAP U3 이후

모델·CRUD·이력·암호화·마스킹은 완료했다(§2.15 참고, ROADMAP U3). 남은 항목은 §3.5의 "Environment/Secrets 값의 런타임 실제 주입"으로 이관했다.

## 3.7 Repository Settings 후속 — ROADMAP U5 이후

Repository Settings 조회와 연결 해제 API는 완료했다(§2.17 참고, ROADMAP U5). 동시 쓰기 lost-update 해소도 완료했다(Issue #45, §2.20 참고). 남은 항목은 없다.

## 3.8 Project Settings 나머지 영역

- General: 설명 등 추가 필드
- Version & Release Policy Settings API
- Deployment Defaults Settings API
- Domain Settings API
- Cost & Budget Settings API
- Danger Zone 전체 정책

## 3.9 배포 실패 복구 후속 — ROADMAP U6 이후

배포 실패 원인 분석과 재시도 API는 완료했다(§2.18 참고, ROADMAP U6). 남은 항목:

- 배포 취소 API
- 동일 프로젝트 동시 배포에 대한 queue 직렬화

## 3.10 인프라 설정 후속 — ROADMAP U7 이후

인프라 설정 저장(4개 provider-중립 enum)과 INFRA_OPERATION standalone 승인 연동은 완료했다(§2.19 참고, ROADMAP U7). 남은 항목:

- 실제 AWS/GCP 프로비저닝 연결(§3.11 참고)
- tier-to-instance 매핑 정의(EPIC 15 Cloud-Ops에서 결정 예정)
- 오토스케일링/로드밸런서/DB 설정(BI-125~127)은 `BACKLOG_STATUS.md` Removed 지침에 따라 범위 밖으로 유지

## 3.11 AWS/GCP 실제 배포

- AWS/GCP deployment adapter
- IaC plan/apply와 상태 저장
- 오토스케일링/로드밸런서/DB 설정(BI-125~127, `BACKLOG_STATUS.md` Removed — IaC 설계 단계에서 재정의 예정)
- cloud 기본 URL 수집
- 배포 로그와 장애 상태

인프라 설정 저장(§2.19)과 비용 추정(§2.21)은 완료했지만, 이 둘을 실제 클라우드 리소스로 만드는 프로비저닝 자체는 아직 없다. `STATUS_CHECK`(§2.22)가 이 사실을 매번 명시적으로 알린다.

## 3.12 비용/운영 확장

- provider 가격 API 연동(현재는 정적 코드 상수 가격표, §2.21 참고) 기반 실시간 비용
- 예산 초과 시 알림/경고 발송(현재는 상태 필드만 계산, 알림 채널 없음)
- 서버 스펙 변경(RESOURCE_SCALING/AUTOSCALING_CHANGE, §2.22에서 감지·거부만 구현됨)
- 미사용 리소스 정리(RESOURCE_CLEANUP, §2.22에서 감지·거부만 구현됨)
- 위 모두 실제 프로비저닝(§3.11)이 선행되어야 의미가 생긴다

## 3.13 도메인 확장

- HTTPS 인증서 자동 갱신 모니터링
- www/apex redirect 정책
- 도메인 변경 API
- AWS/GCP deployment target 연결
- 실제 구매형 도메인 registrar 연동

## 3.14 운영 지표 (EPIC 18)

- Docker preview 성공률, 배포 성공률, build 실패 해결률
- Agent intent 정확도, 도메인 연결 성공률, cloud 비용 예측 오차
- 위 지표 수집을 위한 event/metric 저장

## 3.15 보안 — ROADMAP U-sec (`danto/security`, 민감·사용자 승인 후 착수)

- 실 API 키 등 민감정보의 환경변수(env) 이관
- 추적/로그에 남은 민감정보 정리

---

# 4. 수정해야 할 것

## 4.1 P0: 데이터와 권한 보호

상태: 완료 (2026-06-08)

적용:

- 배포 상태, 이력, 로그, 버전, 후보 조회에서 인증 사용자와 Project 소유권 확인
- DeploymentHistory 단건 조회 후 연결된 Project 소유권 확인
- Agent task에 `ownerUserId`, `projectId`, `conversationId` 저장
- task 상태 조회, 입력 제출, 취소를 소유자에게만 허용
- `CANCELLED` 상태와 `DELETE /api/v1/agent/tasks/{taskId}` 추가
- 기존 프로젝트 Agent 작업 시 `preview` 브랜치를 기준으로 코드 준비
- Docker 결과를 요청 ID 단위 commit으로 `preview`에 non-force push
- 코드 변경이 있는 Deploy Agent는 production 배포를 실행하지 않고 승인 필요 결과 반환
- Deployment/Agent 권한 및 preview push 회귀 테스트 추가

남은 연결:

- Change/Approval 모델과 승인 후 `preview → main` merge는 4.2에서 구현
- task 영속화와 실행 worker 취소는 4.3에서 구현

## 4.2 P0: PRD 핵심 흐름 정합성

### 처리 완료: 승인 기반 Agent 실행

- `Approval` 모델과 조회/승인/거절 API 추가
- `CHANGE`, `DEPLOYMENT`, `DOMAIN_BINDING`, `INFRA_OPERATION` 승인 유형 추가
- 프로젝트별 승인 On/Off 정책과 Chat Settings API 추가
- 필요한 승인이 있으면 task를 `WAITING_APPROVAL`로 유지
- 같은 task의 승인이 모두 완료된 뒤에만 Agent plan 실행
- 거절 시 task를 `CANCELLED`로 전환하고 실제 작업 미실행
- 승인된 `CODE → DEPLOY` plan은 preview commit 후 기존 PR/merge/tag/workflow 배포 흐름 실행

### 처리 완료: Chat과 Agent 연결

- USER 메시지 저장 후 전체 conversation context로 Decision Agent 실행
- 생성된 plan을 conversation/project/owner가 연결된 task로 제출
- 승인 대기, 사용자 질문, 결과, 오류를 Assistant 메시지로 저장
- Agent 직접 요청 응답에 task 상태와 approval ID 목록 추가

남은 연결:

- task/plan 영속화와 queue 기반 재개는 4.3에서 구현
- Change/diff 영속 모델과 프로젝트 단위 PreviewSession은 4.3에서 구현
- 직접 Deployment API의 승인 정책 통합은 Deployment 운영화 단계에서 구현(ROADMAP U6)

## 4.3 P1: Agent/Preview 운영화

### 처리 완료: 영속 AgentRun과 DB queue

- `agent_runs`에 owner/project/conversation/plan/current step/입력/retry/lease 저장
- `agent_run_events`에 생성, 승인 대기, queue, 실행, 입력, 실패, 완료, 취소 이벤트 저장
- DB claim과 worker lease/heartbeat로 다중 인스턴스 중복 실행 방지
- 만료 lease를 `RETRY_WAIT`로 복구해 서버 재시작 후 실행 재개
- task 이벤트 polling과 SSE endpoint 추가
- 영속 입력 저장 후 `WAITING_INPUT → QUEUED` 재개
- 실패 task 수동 retry와 task/Approval 동시 cancel 지원

### 처리 완료: PreviewSession과 gateway

- 사용자 단위 `UserContainerRegistry` 제거
- task마다 project/conversation 문맥이 포함된 Docker container 생성
- `preview_sessions`에 container, port, TTL, access token 저장
- 응답에는 localhost port 대신 token 기반 backend gateway URL 반환
- HTML 정적 asset 경로를 gateway 경로로 보정
- TTL 만료와 사용자 종료 시 container 자동 제거
- 서로 다른 task와 프로젝트의 workspace/container 격리

### 처리 완료: Change/diff와 build 실패 복구

- Code step 성공 시 `project_changes`에 task/preview session/summary/diff 저장
- 프로젝트 Change 목록, 상세, diff API 추가
- 배포 완료 시 Change를 `DEPLOYED`로 전환
- build 실패를 사용자용 설명, 로그 일부, 최선의 수정안으로 저장
- 기본 승인 정책에서는 복구용 CHANGE Approval 생성
- 승인 후 실패한 step부터 수정안을 포함해 자동 재build
- 승인 정책이 꺼진 프로젝트는 제한된 횟수 안에서 자동 retry

### 처리 완료: Preview 운영 API와 컨테이너 격리 (ROADMAP U4)

- 컨테이너 상태/로그 조회 API 추가(§2.16 참고)
- BI-194 자원/권한/네트워크 격리 정책 적용

운영 한계:

- 실행 중인 Docker/GitHub 외부 명령은 즉시 강제 종료하지 않고 step 경계에서 취소를 반영한다.
- DB queue는 at-least-once 실행이므로 외부 adapter의 idempotency 보강이 계속 필요하다.
- gateway는 정적 preview 중심이며 websocket/backend preview는 후속 확장 대상이다(§3.5).
- 외부 공개 URL 차단(BI-081)은 G1(컨테이너 포트 노출, §2.26)에 이어 게이트웨이 인가 강화(G2/G4, Issue #77, §2.30)까지 완료했다.

## 4.4 P1: Deployment 정확성

### 처리 완료: 영속 Deployment Job

- 배포 요청은 `deployment_histories`에 `PENDING` Job을 먼저 저장하고 즉시 `202 Accepted` 반환
- DB worker가 조건부 claim, lease, heartbeat로 외부 GitHub 작업 수행
- 실패 시 backoff retry, 최대 횟수 초과 시 `FAILED`
- worker 재시작 시 만료 lease를 다시 queue에 투입
- Agent DEPLOY step은 task ID가 연결된 Deployment Job만 생성
- Change는 요청 시점이 아니라 workflow 성공 webhook에서 `DEPLOYED`로 전환

### 처리 완료: 버전과 release 메타데이터

- main HEAD가 이미 순차 tag된 경우 해당 commit의 가장 높은 `vN` tag 재사용
- LATEST와 VERSION 모두 `versionLabel`과 commit SHA 저장
- PR 또는 commit에서 title/description, mergedBy/avatar, PR number, mergedAt 저장
- 버전 목록, 상세, 배포 후보 API가 저장된 release 메타데이터 반환

### 처리 완료: workflow 정확 매칭

- workflow dispatch input과 run-name에 deployment correlation ID 포함
- polling은 최신 run이 아니라 correlation ID와 workflow head SHA가 일치하는 run만 선택
- webhook은 workflow run ID 우선, 미저장 시 correlation ID로 이력 조회
- 저장소와 workflow head SHA를 재검증한 뒤 해당 이력만 LIVE/FAILED 전환
- 과거 배포가 늦게 완료되어도 프로젝트 현재 상태는 최신 배포 요청만 갱신

운영 한계:

- GitHub dispatch 직후 프로세스가 종료되는 극단적 구간은 at-least-once 실행 가능성이 있어 correlation 기반 기존 run 재조회로 중복을 완화한다.
- 배포 취소 API와 동일 프로젝트 queue 직렬화는 후속 작업이다(ROADMAP U6).

## 4.5 P1: CloudConnection 의미 수정

### 처리 완료: 형식 검증과 실제 권한 확인 분리

- 등록 시 로컬 형식 검증만 수행하고 `VALIDATED` 상태로 저장
- DB 기반 `cloud_connection_verification_jobs`를 발급하고 조회/재검증 API 추가
- worker claim/lease로 실제 검증을 `VERIFYING` 상태에서 비동기 실행
- AWS Access Key는 STS `GetCallerIdentity` 호출
- AWS Role은 Qeploy 실행 환경 credential로 `AssumeRole` 후 STS 신원 확인
- GCP Service Account Key는 OAuth token 발급 후 Resource Manager `projects.get` 호출
- GCP Service Account Email은 실행 환경 credential로 IAM Credentials impersonation 후 프로젝트 조회
- 실제 호출 성공 시에만 `CONNECTED`, 인증/권한/외부 오류를 별도 상태로 저장
- 기존 `GET health`는 더 이상 상태를 변경하지 않고 저장된 결과만 조회

### 처리 완료: 프로젝트별 Infrastructure 연결 선택

- `project_cloud_connection_settings`에 프로젝트별 선택 connection 저장
- Project Infrastructure 설정 조회/선택/해제 API 추가
- 프로젝트와 connection 소유권을 모두 확인
- 실제 검증 상태가 `CONNECTED`인 connection만 선택 가능
- cloud deployment/operation adapter가 선택 connection만 해석하도록 공용 resolver 추가
- connection 또는 project 삭제 시 설정 자동 정리

운영 조건:

- AWS Role과 GCP Service Account Email 방식은 Qeploy 실행 환경에 source credential이 있어야 한다.
- 현재 GitHub Pages Deployment는 BYOC cloud deployment가 아니므로 선택 connection을 사용하지 않는다.
- 실제 AWS/GCP 배포/운영 adapter 추가 시 `ProjectInfrastructureSettingsService.resolveConnectedConnection`을 진입점으로 사용한다(ROADMAP U7).

## 4.6 P1: Chat 정책 정합성

상태: 완료 (2026-06-12)

적용:

- 대화 휴지통 보관 기간을 PRD 기준 7일로 통일
- 휴지통 응답에 만료 시각과 올림 기준 남은 보관일 제공
- 첫 사용자 메시지로 대화 제목을 자동 생성하고 80자로 제한
- 대화 목록, 상세, 휴지통 응답에 대화 제목과 프로젝트명 표시
- 삭제된 원본 프로젝트 대신 동일 저장소 프로젝트로 복구할 때 대체 프로젝트 정보 표시
- 휴지통 대화 즉시 영구 삭제 API 추가
- 매시간 만료 대화를 자동 영구 삭제하는 scheduler 추가
- 정확히 7일이 지난 대화는 조회/복구 대상에서 제외하고 자동 삭제 대상으로 처리
- 대화 영구 삭제 시 메시지는 함께 삭제하고 AgentRun, Approval, Change, PreviewSession 이력의 대화 참조만 해제
- 7일 정책, 만료 경계, 영구 삭제 소유권, scheduler, 응답 메타데이터 회귀 테스트 추가

코드 재확인(2026-07-17): `ChatTrashPolicy.RETENTION_DAYS = 7`, `ChatTrashCleanupScheduler`(기본 1시간 주기) 모두 실재. 과거 `api.md`/`connection.md`에 남아 있던 "30일" 표기와 "즉시 삭제/스케줄러 없음" 서술은 이번 문서 개정으로 수정했다(§api.md 7장, §connection.md 7.2).

## 4.7 P1: Overview 실제 데이터 연결

상태: 완료 (2026-06-13)

적용:

- Deployment, Change, Approval, Domain 저장 이력을 통합해 최신순 활동 로그 제공
- Overview의 `recentChanges`를 실제 이벤트 구조로 변경하고 최근 3개만 반환
- 프로젝트 생성 이벤트 외 현재 프로젝트 값으로 조립하던 placeholder 활동 제거
- 연결된 DomainBinding을 배포 URL보다 우선하고 custom, managed, purchasable 순으로 선택
- 최신 배포 상태와 가장 최근 LIVE 배포의 URL/버전을 분리해 진행·실패 중에도 현재 서비스 정보 유지
- 최신 commit 응답에 현재 시각 기준 상대시간 추가
- 프로젝트별 선택 CloudConnection의 provider, region, 검증 상태, 마지막 확인 시각 제공
- 배포, 도메인, cloud, AI Workspace, 설정, 제거 운영 조치와 현재 실행 가능 여부 제공
- PRD 제외 범위인 `trafficSummary` 응답 필드와 placeholder 제거
- Overview 이벤트 정렬, 도메인 URL 우선순위, LIVE 버전 fallback, cloud 상태, 운영 조치 회귀 테스트 추가

## 4.8 P1: 브랜드와 도메인 통일

상태: 완료 (2026-06-07)

적용:

- Spring application name, Swagger, README, Agent prompt/질문/commit 작성자를 Qeploy로 통일
- 관리형 서브도메인 예시를 `*.qeploy.com`으로 통일
- AI 설정 prefix를 `qeploy.ai`로 변경
- GitHub Actions workflow 이름/파일명과 생성 commit/PR 문구를 Qeploy로 변경
- Docker container label과 Agent 기본 저장소명을 `qeploy.*`, `qeploy-project-*`로 변경
- API 문서의 GitHub owner 예시를 `qeploy`로 변경

호환 전략:

- AI 환경변수는 `QEPLOY_AI_*`를 우선하고 기존 `ANTHROPIC_API_KEY`/`OPENAI_API_KEY`를 fallback으로 지원
- 기존 `Dvely Deploy to GitHub Pages` webhook event를 계속 처리
- 진행 중인 기존 배포는 `dvely-deploy.yml` workflow run 조회로 fallback
- 서버 재시작 시 기존 `dvely.agent`, `dvely.userId` Docker label도 복구
- 기존 DB 데이터와 Flyway migration은 변경하지 않음

별도 rename 계획(2026-07-17 재확인, 여전히 미착수):

- `com.example.dvely` package, `DvelyApplication`, Gradle root project명은 대규모 기계적 변경으로 별도 수행한다. 코드 패키지는 여전히 `com.example.dvely`이며 브랜드 문서(Qeploy)와 코드 패키지명이 의도적으로 이중 상태다. rename은 계속 의도적으로 연기한다.
- 로컬 DB명, key 파일 경로, 과거 migration comment는 운영 경로와 이력 확인 후 변경
- package/project rename 전 배포 artifact명, 로그/metric key, 외부 연동 참조를 조사하고 단계적 deprecation 기간 정의

## 4.9 P2: Domain 기능 정확성

상태: 완료 (2026-06-14)

적용:

- 실제 registrar 검색이 아닌 `.app`, `.dev` 구매형 후보를 검색 응답에서 제거
- DomainBinding에 `hostingTarget`, 실제 `httpsEnforced`, 인증서 상태와 만료일 추가
- 기존의 항상 true였던 HTTPS 값은 V20에서 미확인 상태로 초기화하고 재검증 시 실제 값 저장
- GitHub Pages API의 custom domain, `https_certificate`, `https_enforced` 상태 조회
- 인증서가 활성화되면 GitHub Pages HTTPS 강제 적용 자동 활성화
- GitHub Pages 도메인 설정과 workflow 재실행을 `DomainHostingAdapter`로 분리
- 요청의 `GITHUB_PAGES`, `AWS`, `GCP` 배포 대상을 명시적으로 저장
- 아직 cloud 배포 adapter가 없는 AWS/GCP 요청은 작업 생성 전에 미지원 오류 반환
- 직접 도메인 연결과 해제 API를 Agent task 제출 방식으로 변경
- 프로젝트 Domain Approval 정책이 켜져 있으면 연결/해제 모두 승인 후 실행
- Domain Agent task에 도메인 유형, 배포 대상, DNS 검증 방식, 삭제 operation 보존
- Domain 목록/상세와 Overview에 배포 대상, HTTPS, 인증서 상태 제공
- 검색, 승인 제출, adapter 선택, 인증서/HTTPS 갱신, migration 회귀 테스트 추가

운영 한계:

- AWS/GCP 실제 domain adapter는 해당 cloud 배포 adapter가 추가된 뒤 연결한다.
- registrar 구매와 www/apex redirect 정책은 아직 지원하지 않는다.

## 4.10 P2: Webhook 완성

상태: 완료 (2026-06-14, PR #31에서 delivery 재시도/저장소 동기화 최종 정리)

적용:

- `X-GitHub-Delivery` GUID를 PK로 저장해 동일 delivery 중복 처리 방지
- 서명 검증 후 `webhook_deliveries` queue에 저장하고 즉시 `202 Accepted` 반환
- DB claim, lease, 만료 lease 복구로 다중 인스턴스 중복 실행 방지
- 처리 실패를 오류 메시지와 시도 횟수로 기록하고 backoff 후 최대 5회 재시도
- 지원하지 않는 이벤트를 `IGNORED`, 성공 이벤트를 `COMPLETED`, 소진 실패를 `FAILED`로 기록
- 기본 브랜치 push로 repository health와 최신 commit SHA, 메시지, 작성자, 시각 동기화
- `vN` tag push로 최신 repository version 동기화하고 늦은 낮은 버전 이벤트의 downgrade 방지
- merged pull request로 기본 브랜치의 merge commit snapshot 동기화
- installation 삭제 시 사용자 installation ID와 App token 제거
- installation 중단 시 App token 제거, 삭제/중단 프로젝트 repository health를 `ACCESS_DENIED`로 갱신
- installation 생성, 복구, 권한 승인 시 installation ID 유지 및 repository health 재확인 상태로 전환
- 기존 `workflow_run` 정확 매칭 처리를 delivery worker로 통합하고 실패 예외를 retry 대상으로 전환
- GitHub API 장애 시 commit 조회는 저장된 webhook snapshot으로 fallback
- Overview에 배포 버전과 별도로 최신 GitHub `repositoryVersion` 제공
- 서명, 중복 제출, retry, push, tag, merge, installation, workflow 매칭, migration 회귀 테스트 추가

운영 한계:

- 완료 delivery 보관 기간과 정리 scheduler는 운영 보존 정책 확정 후 추가한다.
- `installation_repositories`의 저장소별 추가/제거 동기화는 현재 installation 전체 상태 범위에 포함하지 않는다.

## 4.11 P2: API와 설정 품질

상태: 완료 (2026-06-15, PR #31/#32에서 응답 계약과 프로젝트 생성 흐름 정합화)

적용:

- 일반 JSON 성공 응답을 `status`, `code`, `message`, `data` 공통 envelope로 자동 변환
- validation, 인증/인가, not found, method not allowed, conflict 오류를 공통 오류 계약으로 통일
- Project/Chat 모듈별 예외 wrapper 제거와 Agent/PreviewSession 빈 오류 응답 정리
- redirect, webhook, preview byte proxy, SSE, `204 No Content`는 프로토콜 응답을 그대로 유지
- CORS origin과 origin pattern을 `qeploy.cors` profile 환경설정으로 이동
- dev/local은 localhost origin을 기본 제공하고 prod는 환경변수로 명시하도록 분리
- Project 생성 시 blank/template과 fast/quality 값을 검증·정규화
- 생성된 Project ID에 연결된 CODE Agent task를 제출하고 task/승인 정보를 `202 Accepted`로 반환
- template은 지정 유형을 초기 생성 지시에 반영하고 quality/fast에 따라 생성 품질과 provider를 선택
- 기존 GitHub 저장소 가져오기는 repository 연결 흐름을 유지하고 ZIP import는 3.1 후속 범위로 분리
- 공통 응답 MVC, CORS, 생성 Agent plan, Auth 외부 port와 token rotation 테스트 추가
- Agent/CloudConnection/PreviewSession 소유권 및 webhook 서명 실패 회귀 테스트 확대

## 4.12 P2: Agent 도메인 모듈화와 실행 안정성 (PR #33, 2026-06-16 병합)

상태: 완료

적용(`agent/unhak` → `main` 병합, PR #31의 Approval/Change/영속 Agent 변경과 충돌 정리 포함):

- Agent 제출 흐름을 `AgentFacade`로 분리하고 conversation/approval 결과를 함께 유지
- Docker 포트 바인딩 누락과 컨테이너 제거 예외 흐름 보강(`DockerContainerService`)
- `TaskStore` 입력 제출에서 blank/중복 입력 방어
- PreviewSession/Change API 기준으로 오래된 `SessionDiffService`, `UserContainerRegistry` 등 레거시 경로 제거
- facade, Docker, TaskStore, Controller 대상 회귀 테스트 추가
- README에 Approval/Change/Preview 모듈 반영

## 4.13 P1: Environment/Secrets, Preview 운영, Repository Settings (ROADMAP U3·U4·U5)

상태: 완료 (2026-07-18, PR #44/#47/#46 머지)

적용: §2.15, §2.16, §2.17 참고.

남은 연결(§3.5~§3.6 참고):

- Environment/Secrets 값의 Docker Preview/Deployment 실제 주입

외부 공개 URL 차단(BI-081)은 G1(컨테이너 포트 노출, §2.26)과 게이트웨이 인가 강화(G2/G4, Issue #77, §2.30)까지 완료했다.

## 4.14 P1: 배포 실패 복구 + 인프라 설정/Standalone 승인 (ROADMAP U6·U7)

상태: 완료 (2026-07-18, PR #50/#51 머지)

적용: §2.18(배포 실패 복구), §2.19(인프라 설정+standalone 승인) 참고.

남은 연결:

- 배포 취소 API, 동일 프로젝트 queue 직렬화(§3.9)
- 실제 AWS/GCP 프로비저닝과 인프라 설정 연결(§3.10, §3.11)

## 4.15 P1: Project 동시 쓰기 lost-update 해소 (Issue #45)

상태: 완료 (2026-07-18, PR #52 머지)

적용: §2.20 참고.

남은 연결: 없음. `repository` 연결/해제, webhook head-sync, 배포 상태 반영 등 Project를 쓰는 모든 경로에 낙관적 잠금이 공통 적용되었다.

## 4.16 P1: Cost & Budget, Cloud Ops Agent (Issue #53·#54)

상태: 완료 (2026-07-18, PR #58/#59 머지)

적용: §2.21(Cost & Budget), §2.22(Cloud Ops Agent) 참고.

남은 연결:

- 실제 AWS/GCP 프로비저닝(§3.11) — 비용/운영 기능 모두 "desired state를 계산/조작"하는 수준이며 실제 클라우드 리소스는 없다
- RESOURCE_SCALING/AUTOSCALING_CHANGE/RESOURCE_CLEANUP 실행(§3.12) — 현재는 감지·거부만
- Cost/CloudOps 리뷰에서 파생된 후속 이슈 #55(오케스트레이션 하드닝)는 아직 착수 전. #56(결과 승인 2단계)은 §4.17에서 완료
- Audit Log(EPIC 17 나머지)는 완료(2026-07-25, Issue #74/PR #75, §2.25/§4.20 참고)

## 4.17 P1: 결과 승인 2단계 게이트 (Issue #56)

상태: 완료 (2026-07-18, PR #61 머지)

적용: §2.23 참고. PRD §15.2도 사용자 확정에 따라 함께 개정(정본 예외).

남은 연결:

- Issue #62: 결과 승인 리뷰의 Low 등급 잔여 항목(B3 락 순서 통일은 §2.24에서 함께 해소, 나머지는 축소된 범위로 남음)
- Issue #57: E2E 결함 H1·M3는 §2.6에서 완료

## 4.18 P1: Agent 오케스트레이션 동시성/복구 하드닝 (Issue #55)

상태: 완료 (2026-07-18, PR #63 머지)

적용: §2.24 참고.

남은 연결:

- Issue #62: 결과 승인 리뷰 Low 등급 잔여(B3는 §2.24에서 해소, 나머지 축소 범위 미착수)
- Issue #64: 배포 재시도(retry) TOCTOU 경합(아직 착수 전, 세부 사항은 Issue 본문 참고)
- 🔴 `U-sec`: 실 OpenAI API 키 등 민감정보의 env 이관(사용자 승인 전까지 보류)
- Audit Log(EPIC 17 나머지)는 완료(2026-07-25, Issue #74/PR #75, §2.25/§4.20 참고)

## 4.19 P2: task 응답 retryable·pendingApprovalId 정합 (Issue #57)

상태: 완료 (2026-07-19, PR #65 머지)

적용: §2.6 참고. QA `full-api-conformance-report.md`의 H1/M3 결함을 동일 근본 원인으로 함께 해소.

남은 연결: 없음.

## 4.20 P1: 감사 로그(Audit Log) 도메인 (Issue #74, BACKLOG EPIC 17 BI-188~192)

상태: 완료 (2026-07-25, PR #75 머지, V30)

적용: §2.25 참고. 리뷰 `.agent-team/10-review/ad-audit-review.md`(Thomas, APPROVE — Blocking/High 0건, Medium 1건·Low 3건은 같은 PR 후속 커밋에서 정리 완료).

남은 연결:

- BI-195(권한 최소화)는 부분 완료 — GitHub App 설치 권한 재검토, G5(컨테이너 `/tmp/.git-credentials` 평문 개선)는 보안 성격의 별도 후속으로 분리(§2.25, `ROADMAP.md` "이후 백로그" 참고). 아직 이슈 미신설
- 계정 수준 감사(GitHub App installation 이벤트, 로그인 이력)와 `/me/audit-logs`는 이번 단위 범위 밖
- 환경변수 변경의 audit 편입은 제외 유지(`environment_variable_histories` 자체 이력으로 충분하다고 판단)
- EPIC 18 성공률 지표(BI-196~201, §3.14)는 감사 로그를 원천으로 활용할 수 있으나 별도 단위

---

## 4.21 P1: 외부 AI 코딩 에이전트 개인계정 연동 — BYOK (Issue #242)

상태: 머지 준비 완료 — PR #245, CI 성공, MERGEABLE. 브랜치 `danto/coding-agent-byok`, 커밋 10개, V45.

무엇: 사용자가 자기 공식 API 키를 등록하면(BYOK) 그 키로 Claude Code / Codex CLI 를 격리 컨테이너에서 헤드리스 실행한다. 설계 `docs/byok-coding-agent-design.md`, 요구사항 `srs.md`, API `api.md` §16.

왜 BYOK 인가: 구독(Pro·Max·Plus) 자격증명을 제3자 제품에 임베딩하는 것은 Anthropic 이 공식 금지(2026-02-20 명문화, 04-04 시행)하고 OpenAI 도 미지원이다. 구독 OAuth 토큰은 헤드리스에서 갱신되지 않아 10~15분 후 401 이 난다. BYOK 는 두 벤더 모두 명시적으로 허용·권장하며 사용량이 사용자 계정으로 직접 청구된다.

구현 요약:

- `aiaccount` 도메인 신설(`ai_provider_credentials`, V45). 키는 기존 `AesEncryptor` 로 at-rest 암호화, 조회는 전부 userId 스코프.
- `CodingAgentPort` + `agent.infrastructure.codingagent` 의 CLI 어댑터 2종(Claude Code / Codex), 전용 이미지 `docker/coding-agent/Dockerfile`.
- `AiProvider` 에 `CLAUDE_CODE`·`CODEX` 추가. `credentialVendor()` 가 실행 모드→벤더 매핑을 한 곳에 모아 "사용자당 벤더당 키 하나"를 유지한다.
- 엔드포인트 3개(`/api/v1/ai-credentials`). 평문 키는 어떤 응답에도 없다.

남은 연결:

- ~~**CODE 스텝 배선 미완**~~ **(2026-09-11 배선 완료, Issue #325)**: 원래 문제는 이랬다 — `CodeAgentService` 의 루프는 이미 떠 있는 프리뷰 컨테이너 안에서 툴을 돌리는데, 코딩 에이전트는 자기 컨테이너에 호스트 체크아웃을 마운트한다. 워크스페이스 모델이 달라 다리를 놓는 것이 별도 단위다. 그때까지 CODE 스텝은 해당 제공자를 거절한다.
- **SRS FR-4(등록 시 키 유효성 실검증) 보류**: 실검증은 사용자 키를 기존 HTTP 클라이언트에 흘려야 하는데, 그 클라이언트들은 배포 키(`AiProperties`)에 묶여 있고 병렬 작업의 핫파일이다. 설계에서 "기존 HTTP 클라이언트 BYOK" 를 후속으로 분리해 둔 것과 같은 이유로 함께 미룬다.
- ~~CLI 비대화 인자 실측 미완~~ → **실측 완료(2026-09-05)**. 이미지를 빌드해 실제로 돌려 확인했다(Claude Code 2.1.260 · codex-cli 0.153.2). `claude -p` · `codex exec` 는 맞았으나 **Codex 인증 방식이 틀려서 코드를 고쳤다** — `codex exec` 는 `OPENAI_API_KEY` 를 읽지 않고(401 Missing bearer) `codex login --with-api-key`(stdin) 선행이 필요하다. 러너에 로그인 단계 + stdin 지원을 추가하고, 로그인 실패 시 에이전트를 돌리지 않고 즉시 반환하도록 했다. `codex exec` 가 git 저장소 밖을 거부하는 것도 확인(워크스페이스가 clone 이라 자연 통과, `--skip-git-repo-check` 는 기본값에 넣지 않음).
- **Codex 경로 end-to-end 검증 완료(2026-09-05)**: 사용자가 넣어준 유효 OpenAI 키로 `codex login`(stdin) → `codex exec --model gpt-5.6-luna` → exit 0, 에이전트 응답 "OK". 모델은 별도 설정(`qeploy.coding-agent.codex.model`)으로 빼고 기본값을 최저 등급 `gpt-5.6-luna`로 잡았다(같은 프롬프트 실측: luna 9,692 vs CLI 기본 sol 11,203 토큰, 답 동일).

**배선(2026-09-11)** — `CodingAgentWorkspaceBridge` 가 두 워크스페이스 모델을 잇는다. 프로젝트를 컨테이너 밖 호스트 디렉터리로 꺼내고, 에이전트가 거기서 고치고, 되돌려 넣는다.

- **`node_modules`·`.git` 은 어느 방향으로도 나르지 않는다.** 수만 개 파일이고 반대편에서 다시 만들 수 있다 — 나르면 복사가 실행 전체를 지배한다. 되돌려 넣은 뒤 컨테이너에서 `npm install` 을 다시 돌린다(에이전트가 자기 컨테이너에 깐 것은 그 컨테이너와 함께 사라졌고, `package.json` 에 새 의존성이 붙었을 수 있다).
- **되돌려 넣기 전에 컨테이너 쪽을 비운다.** Docker 의 copy 는 덮어쓸 뿐 지우지 않아, 에이전트가 지운 파일이 살아남아 "지운 줄 아는 코드" 와 함께 빌드된다. `node_modules` 만 남긴다 — 유일하게 안 나른 것이라 유일하게 지우면 안 되는 것이기도 하다.
- **실패하면 아무것도 되돌려 넣지 않는다.** 타임아웃이 특히 그렇다 — 절반만 적용된 체크아웃을 넣으면 프리뷰가 그걸 빌드해 결과인 것처럼 보여준다. 컨테이너를 그대로 두면 실패가 깨끗해 재시도가 된다.
- **egress 허용 목록에 `registry.npmjs.org` 를 더했다.** 의존성을 못 까는 에이전트는 없는 패키지를 import 해 놓고 끝났다고 말한다. 잘 알려진 호스트에 POST 할 수 있게 되는 것이 대가다.
- **제공자 목록은 사용자 키로 갈린다.** `GET /agent/ai-providers` 가 서버 키가 아니라 **호출자가 등록한** 크리덴셜을 보고 코딩 에이전트를 넣는다. 모델·thinking 은 CLI 소관이라 빈 목록으로 오고, 지정하면 400 이다.

> **`DockerClient` 는 스프링 빈이 아니다.** 브리지가 그걸 주입받게 썼다가 컨텍스트 로드가 통째로 깨져 테스트 82개가 죽었다(`NoSuchBeanDefinitionException`). `DockerContainerService` 가 자체 생성해 들고 있으므로 디렉터리 복사를 거기 붙이고 그것만 주입한다.

- **Claude Code 경로는 인증·전송까지만 검증**: 계정 잔액 부족("Credit balance is too low")으로 모델 응답까지는 미확인. 어댑터가 exit 1 을 failed 로 정확히 매핑하는 것은 확인. 유효 키 확보 시 마지막 한 단계가 남는다. `ROADMAP.md` 의 `U-sec` 과 인접. **탈출구인 `--allow-dangerously-skip-permissions` 는 root 에서 거부되므로**(실측), 필요한 것으로 판명되면 비-root 사용자 추가와 bind mount 소유권 처리가 선행되어야 한다. 휴면 인프라라 지금은 사용자에게 닿지 않으며 CODE 스텝 배선 전에 반드시 검증할 것.
- **에이전트가 파일을 쓰려면 Codex 자체 샌드박스를 우회해야 한다(실측)**: bubblewrap 이 user namespace 를 요구하는데 우리 컨테이너는 cap-drop ALL + no-new-privileges 라 뜨지 못하고 셸 툴 호출이 전부 exit 1 로 죽는다. 조용한 실패다 — exit 0 으로 성공을 보고하면서 파일을 하나도 만들지 않았다. `--dangerously-bypass-approvals-and-sandbox` 를 codex 기본 argv 에 넣어 해결(플래그 문서가 이 경우를 가리킨다: 외부에서 이미 샌드박싱된 환경 전용). bubblewrap 은 시험 후 제거 — 우리 격리 아래서는 넣어도 동작하지 않고 어느 경로에서도 쓰이지 않는다.
- **마이그레이션 번호 두 번 밀림(V43→V44→V45)**: develop 이 V43(#241)·V44(#244)를 연속 선점. git 이 잡아주지 않고 CI 의 머지 커밋에서 Flyway 만 죽는 종류라, 규칙을 "착수 시 예약"에서 "머지 직전 확정"으로 바꿈.
- **Java 경로 실측으로 잡은 결함(2026-09-05)**: 게이트형 통합 테스트(`CodexCliAdapterIntegrationTest`, `-Ddocker.it=true`+`QEPLOY_IT_OPENAI_API_KEY`)를 러너→어댑터에 그대로 통과시키자 `AsynchronousCloseException`. docker-java **OkHttp 전송이 exec stdin 하이재킹을 미지원**(수작업 `docker exec -i` 검증은 이 경로를 안 탔음). 수정: stdin 대신 archive 업로드 API 로 `/run/qeploy/stdin`(0600) 스테이징 + 상수 래퍼 `"$@" < file; rm -f file` (argv 는 위치 인자, 무보간). 통합 테스트 통과(로그인→실행→파일 생성→컨테이너 잔존 0). 이 테스트가 "exit 0 인데 아무것도 안 함" 류 조용한 회귀를 고정한다.
- 변형 B(클라이언트측 키 보관 + 확장/WebSocket)는 범위 밖.

---

## 4.22 P1: 에이전트용 개인 액세스 토큰 PAT (Issue #304)

상태: **머지 완료** (2026-09-10, develop `d8237b7`). V60 — 착수 때 V56 이었는데 develop 이 V56~V59 를 가져가는 사이 세 번 밀렸다.

무엇: 브라우저 JWT 가 1시간이라 헤드리스 클라이언트(MCP 서버·CLI·CI)가 쓸 장수명 자격이 없었다. PAT 로 채운다. MCP·CLI 단위(PRD 부록 A-2, `srs.md` §B)의 유일한 선행 요건이다.

- `apitoken` 도메인(V60). **해시만 저장**하고 평문은 발급 응답에서 1회만 노출한다 — §4.21 의 BYOK 키와 달리 벤더에 전달할 일이 없어 비교만 하면 된다.
- 기존 `JwtAuthenticationFilter` 가 `qp_` 접두사로 분기. 기존 JWT 경로는 테스트로 불변을 고정했다.
- 스코프는 HTTP 메서드로 강제(READ 는 GET 만, 변경 메서드는 403).
- 엔드포인트 3개(`/api/v1/api-tokens`). `api.md` §18.

**실 서버 검증 완료**: 로컬 기동 후 실제 HTTP 로 10가지 경우 전수 확인 — 발급·목록·폐기, 발급 토큰의 실제 동작, READ 토큰의 쓰기 403, 만료·폐기·미상 토큰의 401 동일화, 목록 응답에 평문 부재, 만료 상한 400. 검증용 사용자·토큰은 정리했다.

> 이 과정에서 잡은 것은 코드 결함이 아니라 **내 검증 스크립트의 오류**였다(응답 봉투를 벗겨진 형태로 파싱). 코드는 처음부터 맞았다.

---

## 4.26 P1: 성능·비용 전면 개선 — 10개 단위 (2026-09-11, Issue #335~#345)

백엔드 전체를 다섯 영역(스키마·인덱스 / 쿼리패턴·JPA / 스케줄러·스레드풀 / 외부비용 / 요청경로)으로 나눠 코드 실측 감사한 뒤, 10개 단위로 나눠 3차에 걸쳐 처리했다. 계획 원문은 `.agent-team/00-plan/perf-cost-plan.md`(gitignore).

### 대표 수치 (전부 실측, 추정 아님)

| 지표 | 전 | 후 | 단위 |
|---|---|---|---|
| 유휴 DB 쿼리(사용자 0·작업 0) | 1,904/분 | **65/분** (10단위 전부 머지 후 실측, 90초 창) | U5 (#350) 주도 |
| 프로젝트 개요의 변경 목록 200행 | 97,522,183 B / 488ms | **20,819 B / 71ms** | U6 (#353) |
| 웹훅당 `projects` 조회 | `ALL` 19,109행 | **`ref` 1행** | U3 (#347) |
| 실제로 열리는 동시 SSE 스트림 | 2 / 5 | **5 / 5** | U4 (#352) |
| SSE 스트림 1개당 SELECT | 177/분 | **10/분** | U4 (#352) |
| 프리뷰 자산 20건 로드 시 세션 UPDATE | 20회 | **0회**(스로틀 안) | U7 (#355) |
| CODE 10라운드 전송 중 캐시 접두 | — | **82.4%** | U8 (#354) |
| 120턴 대화 요청 본문 | 41,571 B | **7,131 B** | U8 (#354) |
| 외부 무응답 시 스레드 점유 | 무한 | **5~60초** | U1 (#349) |
| 스케줄러 스레드 | 1개(잡 29개 직렬화) | 6개 | U0 (#346) |

### 단위별

| 단위 | 이슈 | PR | 한 줄 |
|---|---|---|---|
| U0 설정 | #335 | #346 | 운영 SQL 로그 차단 · 스케줄러 스레드 1→6 · graceful shutdown · 응답 압축 |
| U1 HTTP | #336 | #349 | 공용 `RestClient` + 경로별 타임아웃 · GitHub App 키/토큰 캐시 |
| U2 트랜잭션 | #337 | #351 | 트랜잭션 안에서 외부 I/O 를 기다리던 8지점 분리 |
| U3 스키마 | #338 | #347 | 인덱스 13테이블 · 보존 정책 5건 · 죽은 구조 제거(V61·V62) |
| U4 SSE | #339 | #352 | 이벤트 구동 + 가상 스레드 |
| U5 워커 | #340 | #350 | 적응형 백오프 + recover/claim 트랜잭션 병합 + **행별 격리 버그 수정** |
| U6 목록 | #341 | #353 | 프로젝션 · 커서 페이지네이션 |
| U7 프리뷰 | #342 | #355 | 세션 touch 스로틀 · 해시 자산 캐시 · 스트리밍 |
| U8 LLM | #343 | #354 | **토큰 계측 신설**(V63) · prompt caching · 대화 윈도우 |
| U9 프로비저닝 | #344 | — | **unhak 인계**(STS·SDK 클라이언트 캐시, ECR lifecycle) |
| U10 요청경로 | #345 | #348 | §2.34 참조 |

### 이 작업에서 드러난 사실 (다음 사람을 위해)

1. **유휴 DB 비용의 99%는 SELECT 가 아니라 트랜잭션 의례였다.** 폴링 1회가 `SET autocommit=0` → SELECT → `COMMIT` → `SET autocommit=1` 로 왕복 4배가 된다. 워커 4종 × (recover+claim) = 폴링당 8 트랜잭션이 초당 반복됐다. 그래서 폴링 횟수를 줄이는 것이 SELECT 를 줄이는 것보다 효과가 크다.

2. **Spring Data 의 `IgnoreCase` 가 인덱스를 죽이고 있었다.** `upper(col) = upper(?)` 를 만들어 컬럼에 함수를 씌우는데, 이 저장소 컬레이션은 `utf8mb4_unicode_ci` 라 **`IgnoreCase` 없이도 대소문자를 이미 무시한다.** 즉 효과 없이 인덱스만 못 타게 하고 있었다. 인덱스만 추가하면 안 고쳐진다 — 둘 다 필요하다.

3. **`*SchemaTest` 도 `ddl-auto: validate` 도 인덱스를 검증하지 않는다.** 인덱스 마이그레이션이 틀려도 테스트는 통과한다. 검증은 `EXPLAIN` 으로 해야 한다.

4. **전송 계층이 AWS SDK 의 전이 의존으로 암묵 선택되고 있었다.** `RestClient` 에 `requestFactory` 를 지정하지 않으면 Spring 이 클래스패스에서 고르는데 1순위가 Apache HttpClient5(`apache5-client`)였고, 그 기본 풀은 라우트당 5커넥션이다. 이제 JDK 로 명시 고정한다.

5. **`ThreadPoolExecutor` 는 큐가 가득 차야 코어를 넘긴다.** `agentEventExecutor` 가 core 2 / queue 100 이라 실질 동시 SSE 스트림이 2개였고, 3번째부터 최대 5분 큐 대기 후 FE 가 폴링으로 조용히 강등됐다.

6. **Connector/J 는 기본값에서 "바뀐 행" 이 아니라 "조건에 맞은 행" 수를 돌려준다**(`useAffectedRows=false`). 보존 스윕의 `UPDATE ... WHERE x IS NOT NULL` 에서 그 조건은 장식이 아니라 **반복 종료 조건**이다 — 없으면 이미 비운 행이 계속 매칭돼 무한 루프가 된다.

7. **`SIGNAL` 은 준비문 안에서 지원되지 않는다**(ERROR 1295). 마이그레이션에서 조건부로 실패시키며 메시지를 남기려면 존재하지 않는 테이블 이름에 사유를 담는 우회가 필요하다(V62 의 안전 게이트).

### 되돌리는 법

전부 설정으로 되돌아간다: `spring.task.scheduling.pool.size`(U0) · `qeploy.worker.poll.max-delay-ms`(U5, 1000 이면 이전 동작) · `qeploy.auth.revoked-token-cache.not-revoked-ttl=0`(U10) · `qeploy.ai.failure-analysis.provider/model`(U8) · `qeploy.ai.code-agent.max-task-tokens=0`(U8 예산 상한 해제).

---

## 4.25 P1: 보안 감사 — 토큰 탈취·프리뷰 코드 실행 (2026-09-11)

두 위협을 코드로 감사하고 확정된 것만 고쳤다.

### 확정 결함 1 — 프리뷰 컨테이너가 Qeploy API 에 닿았다

컨테이너 간 통신은 막혀 있었지만(`enable_icc=false`) **호스트로 나가는 길은 그 옵션과 무관하게 열려 있다.** 코드 주석도 그렇게 적고 있었다. 실측으로 확인했다 — 운영과 같은 격리 설정(`cap-drop ALL`·`no-new-privileges`·`qeploy-preview` 네트워크)의 컨테이너에서 호스트 리스너에 도달했다.

그 길 끝에 무엇이 있었나: Spring 이 `server.address` 없이 `0.0.0.0` 에 붙어 있었다. MySQL 은 이미 루프백에 묶여 있었고(설치 스크립트가 강제) 앞단엔 nginx 가 `127.0.0.1:8080` 으로 프록시한다 — 앱만 열려 있었던 것이다.

**수정**: `server.address: ${QEPLOY_BIND_ADDRESS:127.0.0.1}`. 검증은 바인딩 자체를 쟀다 — 수정 후 `java 127.0.0.1:8099`, 환경변수로 되돌리면 `java *:8099`. Linux 에서 컨테이너는 브리지 게이트웨이 주소로 도달하므로 루프백 소켓은 받지 않는다.

> macOS 의 Docker Desktop 은 호스트 루프백까지 프록시로 전달해서, 그 환경에서는 컨테이너가 여전히 닿는 것처럼 보인다. 운영(Linux)의 동작과 다르므로 그 결과로 판단하면 안 된다.

### 확정 결함 2 — 쓰기 토큰이 읽기 토큰만큼 오래 살았다

READ·WRITE 가 똑같이 기본 90일·최대 365일이었다. 유출됐을 때 할 수 있는 일이 다른데 수명이 같았다 — READ 는 소유자가 이미 볼 수 있는 것을 드러내지만, WRITE 는 배포하고 환경변수를 바꾸고 도메인을 붙인다. 그것도 헤드리스 클라이언트에서, 아무도 화면을 보고 있지 않은 채로.

**수정**: WRITE 는 기본 30일·최대 90일. READ 는 그대로. 거절 메시지에 스코프를 넣었다 — 365일을 요청하는 것은 그 토큰이 배포할 수 있다는 걸 알기 전까지는 합리적이고, "1~90" 만으로는 왜 답이 달라졌는지 알 수 없다.

### 구멍이 아니었던 것 (전수 확인)

- **소유권 검증은 제대로 되어 있다.** 프로젝트 스코프 리소스는 전부 `findByIdAndOwnerUserIdAndDeletedFalse` 계열로 거른다. `deploymentId`·`serverId`·`domainId` 처럼 프로젝트 스코프가 없는 경로도 마찬가지다.
- **토큰이 로그에 남지 않는다.** PAT 는 헤더 전용이고 값을 찍는 곳이 없다. 프리뷰 게이트웨이가 남기는 경로에도 accessToken 구간이 들어가지 않는다.
- **docker socket·바인드 마운트가 없다.** 자원 상한(메모리·swap·CPU·PID)과 exec 타임아웃 10분이 걸려 있고, 게시 포트는 루프백 전용이다.

**그래서 회귀 가드를 뒀다** — `ResourceOwnershipGuardTest`. 호출자가 준 ID 로 리소스를 꺼내면서 소유자를 확인하지 않는 메서드를 소스에서 찾아 실패시킨다. 확인이 private 헬퍼로 빠져 있는 경우까지 한 단계 따라간다(실제 코드가 그렇게 생겼다 — `retryDeployment` → `resolveProject`). 지키는 것은 현재 상태가 아니라 **다음에 추가될 엔드포인트**다. 결함을 일부러 주입해 잡히는 것까지 확인했다.

### 남은 것 — Issue #332 (unhak 인계, 2026-09-11)

프리뷰 컨테이너가 **root(uid 0)로 돌고**, egress 가 제한되지 않는다.

착수했다가 되돌렸다. 처음 세운 방법(root 로 워크스페이스를 만들어 넘기고 이후 exec 를 `--user node` 로)이 **실측으로 반증됐다** — `cap-drop ALL` 이 `DAC_OVERRIDE` 를 떼서 이 컨테이너의 root 는 파일 권한을 우회하지 못한다(`CapEff: c1`). 워크스페이스를 넘기는 순간 이후 root 명령이 전부 막히므로 **주인이 하나여야 한다.** 방향이 예상과 반대였다 — 문제는 node 가 root 파일을 못 쓰는 것이 아니라(그건 우회된다) root 가 node 트리에 못 쓰는 것이다.

목표 상태는 동작한다(적대적 postinstall 이 uid 1000 으로 돌고 `/etc`·`/usr/local/bin` 쓰기와 `apk add` 는 거부, git·npm 전 과정 정상). 다만 `createAndStartContainer`·`exec` 를 프리뷰와 빌드 컨테이너가 함께 써서, 기본 사용자를 뒤집으면 AWS 가 필요해 여기서 돌려볼 수 없는 배포 경로까지 영향을 받는다.

**unhak 에게 넘겼다.** 위험한 부분이 전부 프리뷰 런타임이고 JAVA_FULLSTACK 경로는 이쪽에서 재보지 못한 유일한 곳이다. 이슈 본문을 인계 문서로 다시 썼다 — 확정 제약, 목표 상태 실측, 4단계 순서(빌드 컨테이너 분리부터), 함정 3건(컨테이너 재사용 시 root 소유 빌드 로그, 빌드가 git 자격증명을 못 물려받게 되는 것, JAVA_FULLSTACK 미검증).

---

## 4.24 P1: 발행 경로를 러너에서 확정하고, 빈 사이트를 배포하지 않는다 (Issue #330)

상태: 완료 (2026-09-11).

**조용한 실패가 문제였다.** 빌드가 산출물을 안 만들어도 404 복사는 `|| true` 로 넘어가고, custom domain 스텝의 `mkdir -p` 가 **빈 디렉터리를 만들어**, 발행 액션이 그걸 올렸다. 실패한 스텝이 하나도 없는 채로 사이트 전체가 404 가 된다 — 사용자는 어디를 볼지 알 수 없다.

- **발행 경로를 러너에서 확정한다.** `Resolve publish dir` 스텝이 `$GITHUB_OUTPUT` 에 쓰고 하류가 그걸 본다. 빌드가 어디에 냈는지는 설정과 프레임워크 판본에 달려 있어 워크플로를 만드는 시점에는 확실히 알 수 없다.
- **산출물 검증이 `mkdir -p` 보다 앞에 있다.** 순서가 반대면 검증이 보는 것은 이미 만들어진 빈 디렉터리다.
- **Nuxt 는 실물을 가리킨다.** `nuxi generate` 는 `.output/public` 에 내고 `dist` 는 호환용 심볼릭 링크다(**Nuxt 4.5.2 실측** — 절대 경로 링크). 링크는 판본·설정에 따라 없을 수 있고 발행 액션이 따라간다는 보장도 없어, 두 불확실성을 함께 없앴다. `.output/public` 이 없으면 예전 값으로 떨어져 Nuxt 2 계열도 그대로 동작한다.
- **명시적으로 받은 발행 경로는 추측으로 덮지 않는다.**

검증: 실제 `nuxi generate` 산출물 배치에 대고 생성된 스텝을 돌렸고, 링크 없음·Nuxt 2 배치·산출물 없음·빈 디렉터리·정상 다섯 경우를 확인했다. 재현은 `scripts/verify-deploy-publish-dir.sh`.

> Nuxt 4.5.2 로 확인했다. Nuxt 5 는 아직 nightly 이고 산출물 배치가 또 달라질 수 있다 — `.output/public` 을 먼저 보는 지금 구조는 그 변화에 한 겹 덜 취약하다.

---

## 4.23 P1: 에이전트 연동 — MCP 서버 + CLI (PRD 부록 A-2)

상태: **머지 완료** (2026-09-10, develop `d8237b7`). PAT 와 한 PR 로 묶었다 — MCP 는 PAT 없이는 인증 자체가 성립하지 않아 따로 머지할 실익이 없다.

무엇: 사용자의 Claude Code·Codex 가 Qeploy 를 도구로 호출한다. §4.21(BYOK)과 호출 방향이 반대라 Qeploy 는 추론하지 않고 AI 자격증명을 보지도 중계하지도 않는다 — 컴플라이언스 이슈가 없고 이 경로의 AI 비용은 0 이다.

- `agent-tools/` 워크스페이스: `@qeploy/client`(REST 클라이언트, 봉투 해제·에러 번역), `@qeploy/mcp`(stdio MCP 서버), `@qeploy/cli`(`qeploy` 명령).
- **읽기 전용 도구 11개.** 되돌리기 어려운 조작(프로젝트·서버 삭제, 승인, 비용예산)은 앞으로도 노출하지 않는다. 도구 이름 집합을 테스트로 못박아 추가가 의도적 편집이 되게 했다.
- 인증은 §4.22 의 PAT. 서버가 READ 스코프에서 변경 메서드를 403 으로 막으므로 읽기 보장이 클라이언트 규약이 아니라 서버 강제다.
- API 실패는 프로토콜 예외가 아니라 **에러 결과**로 반환한다. 예외면 에이전트가 읽지 못하지만 결과면 모델이 읽고 대응한다(만료 토큰이면 재발급을 안내).

**실 stdio 검증 완료**: MCP 클라이언트로 실제 서버 프로세스를 띄워 initialize → tools/list(11개) → tools/call 왕복까지 확인. 없는 프로젝트는 에러 결과, 토큰 미설정이면 종료코드 1 이고 안내가 stdout(프로토콜 채널)을 오염시키지 않는다.

**쓰기 도구 6개 추가(2026-09-08)**: deploy·retry·create_env·update_env·bind_domain·verification_guide. 방어가 세 층이다 — ① `QEPLOY_ENABLE_WRITES=true` 없이는 목록에 없다(기본값이 "시작할 수 없음") ② READ 스코프 PAT 는 서버가 403 으로 막는다(클라이언트 우회·플래그 무관, 실측 확인) ③ 기존 승인 게이트가 그대로다. MCP 표준 `destructiveHint` 를 붙여 클라이언트가 실행 전 사용자에게 묻게 했다 — 우리 산문을 에이전트가 읽어주길 바라는 대신 프로토콜 수단을 쓴다. 되돌리기 어려운 조작은 플래그와 무관하게 미노출이고 테스트가 그 부재를 못박는다.

실 stdio 검증(쓰기 포함): 기본 11개/활성 17개, READ 토큰의 배포 시도를 서버가 스코프 사유와 함께 거부, WRITE 토큰은 인가를 통과해 404 까지 도달(실제 배포는 트리거하지 않음). 검증 스크립트는 `agent-tools/e2e.mjs`.

**CLI(2026-09-08)** — MCP 를 못 쓰는 에이전트, CI, 사람용. 같은 클라이언트를 감싸므로 인증·스코프·오류 문장이 MCP 와 같다. 읽기 8개 + 쓰기 3개(`deploy`·`retry`·`env:set`).

- **종료 코드가 실질적 인터페이스다.** 0 성공 · 1 실패 · 2 사용법 · 3 인증. 인증을 따로 뺀 것은 토큰이 만료된 파이프라인과 빌드가 깨진 파이프라인이 서로 다른 대응을 요구하기 때문이다.
- **비대화형에서는 확인을 묻지 않고 거절한다.** CI 로그의 프롬프트는 답할 사람이 없어 러너 타임아웃까지 매달린다.
- **stdin 이 닫혀도 매달리지 않는다.** 종료된 스트림에서 `readline.question()` 이 resolve 도 reject 도 하지 않는 것을 실측하고, close 이벤트를 함께 기다려 "아니오" 로 떨어뜨렸다.
- **`env:set` 은 API 에 없는 upsert 를 만든다.** 중복 (프로젝트, scope, key) 생성은 409 라, 목록을 먼저 보고 생성·수정을 고른다. scope 는 필수다 — COMMON 스코프가 없으므로 기본값을 두면 의도하지 않은 환경에 쓰게 된다.
- **토큰을 옵션으로 받지 않는다.** 명령행 인자는 셸 히스토리와 `ps` 에 남는다.
- 표는 응답 모양이 어긋나면 원본 JSON 으로 물러난다. 이름이 바뀐 필드를 대시로 채운 표는 변화를 감춘다.
- `bind_domain` 은 CLI 에 넣지 않았다. 비동기이고 중간에 사용자가 DNS 레코드를 넣어야 끝나므로 한 번 실행하고 끝나는 명령의 모양이 아니다.

> **실 서버 검증이 mock 이 통과시킨 결함을 잡았다.** 배포 확인 프롬프트가 프로젝트 이름을 보여주도록 개요 응답의 `name` 을 읽었는데, `ProjectOverviewResponse` 에는 `name` 도 `projectId` 도 없다. 테스트 mock 이 없는 필드를 지어내 통과시키고 있었다. 이름은 목록 응답에만 있어 그쪽으로 고쳤고, mock 을 실제 응답 모양에 맞췄다.

재현: `agent-tools/e2e-cli.sh`(CLI), `agent-tools/e2e.mjs`(MCP). JS 테스트 60개.

**`qeploy login`(2026-09-10)** — 발급 창구를 CLI 안에 만들어 FE 의존을 끊었다. 도구를 다 만들어 놓고 열쇠만 없는 상태였다.

- 브라우저 JWT 를 한 번 받아 PAT 로 바꾸고 `~/.config/qeploy/config.json`(0600)에 저장한다. **CLI 와 MCP 가 같은 파일을 읽는다** — 에이전트는 `npx @qeploy/mcp` 로 실행되어 환경변수를 받을 자리가 없다.
- 환경변수가 파일을 이긴다(CI 가 설정한 토큰을 로그인 파일이 조용히 덮어쓰면 안 된다).
- 붙여넣은 것도 발급한 것도 화면에 찍지 않는다. `qp_` 로 시작하는 값을 붙여넣으면 거절한다 — 만료될 때까지 "되는 것처럼" 보이다 이유 없이 실패하기 때문.
- `logout` 은 로컬 파일만 지우고 그 사실을 말한다. 서버 폐기에는 WRITE 스코프가 필요한데 기본이 READ 다.

> **브라우저 왕복이 아닌 이유(실측)**: `redirect_uri` 를 authorize 에도 토큰 교환에도 보내지 않아 GitHub 이 등록된 콜백 한 곳으로만 돌아온다. 기존 웹 콜백에 얹는 방법은 FE 가 `state` 를 검증해 브라우저에 남은 이전 state 하나로 튕긴다. #327 로 분리했다. `GITHUB_OAUTH_REDIRECT_URI` 는 선언만 되고 아무도 읽지 않는 죽은 설정이라는 것도 이때 드러났다.

실 서버 검증: 실제 HS256 JWT 로 로그인 → PAT 발급·0600 저장 → **환경변수 하나 없이** CLI 와 MCP(도구 11개, 실 API 왕복) 양쪽 동작 → logout 후 종료코드 2. JS 테스트 77개.

남은 것: FE 의 PAT 발급 화면(`Dvely_FE` #76)은 여전히 웹 사용자를 위해 필요하다. 다만 **개발자는 이제 FE 없이도 쓸 수 있다.** npm 배포는 오픈소스 공개를 결정할 때. 공개 저장소 분리와 npm 배포는 오픈소스 공개 결정 시점의 일이다.

---

# 5. 권장 구현 순서

`.notion/ROADMAP.md`가 단위(Unit) 단위 실행 순서의 SSOT다. 아래는 지금까지 진행해 온 Phase 이력이며, U1~U7·Issue #45·Cost & Budget·Cloud Ops Agent·Issue #56·Issue #55·Issue #57·Issue #74(Audit Log)·Issue #76(Preview 외부 노출 차단, BI-081/G1)·Issue #77(게이트웨이 인가, G2·G4)이 모두 완료된 이후 신규 작업은 ROADMAP의 "이후 백로그"(`BI-163~165` Project Settings 나머지)와 U-sec 단위를 따른다.


## 4.22 퍼블리싱 템플릿 — 카탈로그 + 씨딩 (Issue #318, PR-1~4)

**되는 것**

- 템플릿 저장소 `Dvely/qeploy-templates` 가 GitHub Pages 로 셋을 발행한다 — `catalog.json`(카탈로그 정본) · `t/<id>/`(데모) · `src/<id>.tar.gz`(씨앗). main 머지가 곧 발행이다
- 데모는 iframe 임베드 가능(2026-09-10 실측: 정상 문서에 `X-Frame-Options`·`CSP frame-ancestors` 없음)
- `GET /api/v1/templates`, `GET /api/v1/templates/{id}` — 인증 불필요. 서버는 카탈로그를 읽어 나를 뿐 **소스를 들지 않는다**
- 카탈로그는 10분 주기로 갱신하고, 갱신 실패 시 직전 목록으로 계속 응답한다(stale-while-error). 한 번도 읽지 못한 경우에만 `503 TEMPLATE_CATALOG_UNAVAILABLE`
- 프로젝트 생성 시 `templateType` 을 **정규화 후 카탈로그와 대조**한다. 없는 ID 는 400
- 참조 템플릿 3종: `landing-minimal` · `portfolio-grid` · `shop-single` (전부 vanilla·자기완결·외부 자산 없음)
- **씨딩**: 첫 CODE 스텝에서 씨앗 tarball 을 `/workspace/app` 에 푼다. 작업 디렉터리가 비었을 때만 — 두 번째 요청이나 저장소를 clone 해온 프로젝트는 건너뛴다(덮으면 사용자 작업물이 사라진다)
- 씨딩되면 CODE 지시문 앞에 템플릿 맥락이 붙는다: "이미 깔려 있다 · 스캐폴더를 돌리지 마라 · 이 부분이 내용이다(contentHints)". 시스템 프롬프트가 "프로젝트 없으면 스캐폴드" 로 시작하므로 그 판단을 추측에 맡기지 않는다
- 씨딩 실패는 조용히 넘어가지 않는다 — 실패하면 태스크를 실패로 닫는다. 그냥 진행하면 고른 것과 다른 결과물이 "성공" 으로 나온다
- 카탈로그의 `sourceUrl` 은 셸에 들어가기 전 형식 검증을 거친다(https + `.tar.gz`, 안전 문자만)

**아직 안 되는 것**

- FE 템플릿 갤러리 UI (별도 담당)
- 썸네일 자동 생성
- 실 사용자 e2e — 템플릿 선택 → 생성 → 프리뷰까지는 아직 실측 전이다

**배경**

이전에는 `startMode`/`templateType` 이 검증·저장되기만 하고 읽는 코드가 없었다. 사용자가 템플릿을 골라도 조용히 무시되고 백지 생성됐다. 설계는 `docs/template-architecture-design.md`.

## Phase 1. 안전한 핵심 흐름 (완료)

1. Deployment/Agent 소유권 검증
2. Agent `main --force` 제거
3. Chat → Agent → Assistant message 연결
4. 영속 Job/AgentRun/PreviewSession
5. Change와 Approval

## Phase 2. PRD 기본 사용자 여정 (부분 완료)

1. ZIP/GitHub import — 미착수, §3.1 참고
2. preview 브랜치 요청 단위 commit/diff — 완료
3. 실제 Overview 이력 통합 — 완료
4. Project Settings 기본 정책 — Chat/Infrastructure/Environment/Repository/Infrastructure Configuration/Cost & Budget 완료, 나머지는 §3.8
5. Chat 7일 휴지통/제목/영구 삭제 — 완료

## Phase 3. 운영과 배포 확장 (완료, ROADMAP U6·U7 + Cost·CloudOps·결과 승인·오케스트레이션 하드닝)

1. CloudConnection 실제 health — 완료
2. Preview 운영 API/격리 — 완료(§2.16)
3. 배포 실패 원인 분석 + 재시도 — 완료(§2.18)
4. 인프라 설정 저장 + standalone Approval — 완료(§2.19)
5. 비용 추정 + 예산 — 완료(§2.21)
6. Cloud Ops Agent(상태/로그/장애분석/재시작) — 완료(§2.22)
7. 결과 승인 2단계(preview→main 반영 게이트) — 완료(§2.23)
8. Agent 오케스트레이션 동시성/복구 하드닝(락 계층·좀비 복구·스윕) — 완료(§2.24)
9. AWS/GCP 실제 프로비저닝, 리소스 스케일링/정리 실행 — 미착수(§3.11, §3.12)
10. Domain/HTTPS adapter 확장 — §3.13
11. Audit Log(EPIC 17 나머지) — 완료(§2.25)

---

# 6. 현재 결론

현재 백엔드는 GitHub 인증, 프로젝트/저장소(연결+해제), Chat 데이터, Docker Code/Chat/CloudOps Agent, GitHub Pages 배포, 도메인, Cloud credential 저장/실검증, Environment/Secrets, Preview 운영·격리, 배포 실패 복구, 인프라 설정 저장+standalone 승인, 비용 추정/예산, 결과 승인 2단계 게이트까지 폭넓은 기반을 갖췄다. Chat 메시지 저장은 Decision Agent 실행으로 바로 이어지고(CHAT 응답과 자연어 인프라 운영 질의 모두 포함), Approval/Change/PreviewSession/AgentRun이 모두 DB에 영속화되어 있으며, CloudConnection은 실제 STS/IAM 검증을 수행하고 프로젝트에서 선택할 수 있다. 승인은 이제 Agent task 기반뿐 아니라 API에서 직접 생성되는 standalone 승인(INFRA_OPERATION)도 지원하며, Cloud Ops Agent의 RESTART도 같은 승인 타입을 재사용한다. 승인은 이제 실행 전 계획 승인(1단계)과 실행 후 결과 승인(2단계, RESULT)으로 나뉘어, "결과를 확인한 뒤 git에 반영할지"와 "그것을 실제로 공개(배포)할지"가 분리된 질문이 되었다. Project를 쓰는 모든 경로(직접 API, webhook head-sync, Agent, 배포 워커)는 낙관적 잠금으로 lost-update로부터 보호된다. Agent 승인 결정 자체도 task 단위 락 계층으로 직렬화되어 동시 승인 write-skew 고착과 RUNNING 좀비 상태가 구조적으로 발생하지 않는다(§2.24).

ROADMAP U1~U7·Issue #45·Cost & Budget·Cloud Ops Agent·Issue #56·Issue #55·Issue #57·Issue #74(Audit Log)가 모두 완료되어, PRD의 제품 핵심 연결은 다음과 같이 정리된다.

```text
Chat
→ 영속 Agent Job (완료)
→ preview 브랜치 Change (완료)
→ Docker Preview (완료, 운영 API/격리 포함)
→ 결과 확인(preview + diff) → 결과 승인(RESULT, git 반영 게이트) (완료)
→ preview → main 병합 (완료, 결과 승인 시점)
→ Approval (완료, Agent 기반 + standalone 모두, 계획 승인 1단계 + 결과 승인 2단계)
→ GitHub Pages 배포(별도 배포 승인/요청 단계) (완료, 실패 복구 포함) 또는 AWS/GCP Deployment (미착수, desired state 저장까지만 완료)
→ Domain/HTTPS (GitHub Pages 기준 완료)
→ Overview/운영 Agent (Overview 완료, Cloud Ops Agent로 자연어 운영 질의는 완료 — 실제 프로비저닝 대상 서버는 없음)
```

남은 핵심 간극은 (1) Environment/Secrets 값의 Preview/Deployment 런타임 실주입, (2) 저장된 인프라 설정/비용/운영 요청을 실제 AWS/GCP 리소스로 만드는 프로비저닝(EPIC 15 Cloud-Ops 실행 계층 — 지금은 desired state 계산과 정직한 거부까지만 구현됨)이다. Preview 게이트웨이 인가 강화(G2 소유권 쿠키·G4 accessToken 회전)는 완료했다(Issue #77, §2.30 — FE 연동 `Dvely_FE` PR #30도 머지·배포 완료). Audit Log(EPIC 17 나머지)는 완료했다(§2.25). 다음 작업은 `ROADMAP.md`의 백로그 순서(`BI-163~165` Project Settings 나머지)를 따른다.
