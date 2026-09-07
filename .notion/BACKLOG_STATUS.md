# Qeploy Backend Backlog Board

> 기준일: 2026-07-25 · 기준 커밋: af221a7 · 코드 실측 기반

검토일: 2026-07-03 (최초 작성), 2026-07-18 갱신(U3~U7·Issue #45·Cost & Budget·Cloud Ops Agent·Issue #56 완료분 반영), 2026-07-25 갱신(Issue #74 Audit Log — BI-188~192 Done, BI-195 부분 완료 반영), 2026-07-25 갱신(Issue #76/PR #78 Preview 외부 노출 차단 — BI-081 Done 반영)
기준: PRD 백로그 201개 항목을 현재 코드, DB migration, API controller, service, test와 대조
검증: `./gradlew test` 통과 (2026-07-03 시점 최초 검증, Issue #56은 PR #61 머지 시점 514/514 보고 기준, Issue #74는 PR #75 머지 시점 641/641 보고 기준, Issue #76은 PR #78 머지 시점 644/644 보고 기준 — 리뷰어 `.agent-team/10-review/ad-audit-review.md`·`.agent-team/10-review/ae-preview-exposure-review.md`가 각각 직접 실행해 확인)

Issue #56(결과 승인 2단계)은 PRD 201개 백로그 원장에 대응 BI 번호가 없는 리뷰 파생 항목이라, 이 보드에는 Todo→Done 이동 대상이 없다(진행 상태는 `ROADMAP.md` 참고).

## Superthread 입력 기준

이 파일은 Superthread에 task를 하나씩 넣기 쉽도록 `Status -> Epic -> Task` 순서로 펼친 보드입니다.

| Superthread 필드 | 입력값 예시 |
| --- | --- |
| Title | `[BI-152] 환경변수 모델 설계` |
| Status | `Todo`, `Doing`, `Backlog`, `Done` |
| Priority | `P0`, `P1`, `P2` |
| Labels | `BE`, `Infra`, `EPIC 13` |

## Board Summary

| Status | Count | 의미 |
| --- | ---: | --- |
| Todo | 3 | MVP 기준 다음에 바로 넣을 작업 |
| Doing | 11 | 일부 구현됐지만 완성 기준이 남은 작업 |
| Backlog | 9 | MVP 이후 확장 작업 |
| Done | 159 | 현재 구현 완료로 판단되는 작업 |
| Removed | 19 | 제품 흐름과 맞지 않거나 다른 task로 흡수할 작업 |

이 보드의 남은 Todo/Doing/Backlog 항목은 `.notion/ROADMAP.md`의 "이후 백로그"(Audit Log → Issue #55~#57)로 재편성되었다. 두 문서가 다르면 `ROADMAP.md`를 우선한다.

2026-07-18 1차 갱신: ROADMAP U3(Environment/Secrets)·U4(Preview 운영/격리)·U5(Repository Settings)가 완료되어 14개 항목을 Todo → Done으로 이동했다: BI-025, BI-082, BI-083, BI-152~BI-160(9개), BI-162, BI-194.

2026-07-18 2차 갱신: ROADMAP U6(배포 실패 복구)·U7(인프라 설정+standalone 승인)이 완료되어 아래 9개 항목을 Todo/Doing → Done으로 이동했다: BI-112, BI-113, BI-186, BI-121~BI-124(4개), BI-129, BI-097.

2026-07-18 3차 갱신: Cost & Budget·Cloud Ops Agent가 완료되어 아래 12개 항목을 Doing/Backlog → Done으로 이동했다: BI-055, BI-144~BI-147(4개), BI-167, BI-170~BI-173(4개), BI-176, BI-177. (Issue #45는 BI 미부여 항목이라 이 보드에는 별도 표시 없음, `ROADMAP.md` 참고)

2026-07-25 4차 갱신: Audit Log(Issue #74/PR #75, BI-188~192)가 완료되어 5개 항목을 Backlog → Done으로 이동했다. BI-195(권한 최소화)는 감사 접근/데이터 최소화·권한 사용 가시화 분만 완료되어 **부분 완료**로 표기하고 Backlog에 남겼다(후속: GitHub App 권한 재검토·G5, 이슈 미신설).

2026-07-25 5차 갱신: Preview 컨테이너 외부 노출 차단(Issue #76/PR #78, BI-081)이 완료되어 1개 항목을 Todo → Done으로 이동했다. 범위는 G1(컨테이너 호스트 포트가 `0.0.0.0`에 퍼블리시되어 게이트웨이·accessToken·Spring Security를 우회하던 결함)만이며, G2(게이트웨이 소유권/JWT 미검증)·G4(accessToken 회전·폐기 없음)는 Issue #77로 분리되어 아직 Todo/Doing 어디에도 등재되지 않았다(무헤더 accessToken이 iframe 임베딩을 위한 의도된 설계라 FE 조율 후 착수, `ROADMAP.md` AG 항목 참고).

## Todo

### EPIC 14. Project Settings API

- [ ] **BI-163** · `P1` · `BE` · Version Policy Settings API
- [ ] **BI-164** · `P1` · `BE` · Deployment Defaults API
- [ ] **BI-165** · `P1` · `BE` · Domain Settings API

## Doing

### EPIC 04. GitHub Version Flow

- [ ] **BI-037** · `P1` · `BE` · 커밋 메시지 자동 생성 로직

### EPIC 05. Agent Orchestration

- [ ] **BI-051** · `P0` · `BE` · 프로젝트 컨텍스트 수집 로직

### EPIC 07. Docker Preview / Infra Runtime

- [ ] **BI-079** · `P0` · `Infra` · 실행 명령 자동 추론
- [ ] **BI-085** · `P1` · `Infra` · 의존성 캐시 구현
- [ ] **BI-086** · `P1` · `Infra` · 빌드 캐시 구현
- [ ] **BI-087** · `P1` · `Infra` · 이미지 캐시 구현
- [ ] **BI-090** · `P2` · `Infra` · 백엔드 프로젝트 preview 지원
- [ ] **BI-091** · `P2` · `Infra` · 풀스택 프로젝트 preview 지원
- [ ] **BI-092** · `P2` · `Infra` · DB 필요 프로젝트 preview 지원

### EPIC 11. Domain / DNS / HTTPS

- [ ] **BI-141** · `P2` · `Infra` · www 리디렉션 설정
- [ ] **BI-142** · `P1` · `BE` · 도메인 변경 처리

## Backlog

### EPIC 10. AWS / GCP Infra

- [ ] **BI-130** · `P1` · `Infra` · AWS/GCP 배포 실행 구조
- [ ] **BI-131** · `P2` · `Infra` · IaC 실행 구조 설계

### EPIC 17. Security / Audit

- [ ] **BI-195** · `P1` · `BE` · 권한 최소화 정책 적용 — **부분 완료** (2026-07-25, Issue #74/PR #75). 완료분: 감사 로그 접근 최소화(소유자 404, 관리자/전체 조회 표면 없음)·데이터 최소화(레닥션·화이트리스트)·권한 사용 가시화(카탈로그 16종). 후속분(미착수, 이슈 미신설): GitHub App 설치 권한 재검토·축소, G5 컨테이너 `/tmp/.git-credentials` 평문 개선 — 아래 EPIC 17 Done의 BI-188~192 참고

### EPIC 18. Success Metrics

- [ ] **BI-196** · `P1` · `BE` · Docker preview 성공률 기록
- [ ] **BI-197** · `P1` · `BE` · 배포 성공률 기록
- [ ] **BI-198** · `P1` · `BE` · 빌드 실패 해결률 기록
- [ ] **BI-199** · `P1` · `BE` · 요청 처리 정확도 기록
- [ ] **BI-200** · `P1` · `BE` · 도메인 연결 성공률 기록
- [ ] **BI-201** · `P2` · `BE` · 클라우드 예상 요금 신뢰도 기록

## Done

### EPIC 01. 서버 기본 구조 / 인증

- [x] **BI-001** · `P0` · `BE` · 백엔드 프로젝트 초기 세팅
- [x] **BI-002** · `P0` · `BE` · DB 스키마 초기 설계
- [x] **BI-003** · `P0` · `BE` · GitHub OAuth 인증 구현
- [x] **BI-004** · `P0` · `BE` · 사용자 계정 생성 / 조회 구현
- [x] **BI-005** · `P0` · `BE` · GitHub Access Token 저장
- [x] **BI-006** · `P0` · `BE` · Token 암호화 저장
- [x] **BI-007** · `P0` · `BE` · 인증 미들웨어 구현
- [x] **BI-008** · `P0` · `BE` · 로그아웃 처리
- [x] **BI-009** · `P1` · `BE` · 권한 만료 처리
- [x] **BI-010** · `P0` · `BE` · 공통 에러 응답 포맷 정의

### EPIC 02. Project / Repository

- [x] **BI-011** · `P0` · `BE` · 프로젝트 모델 설계
- [x] **BI-012** · `P0` · `BE` · Repository 연결 모델 설계
- [x] **BI-013** · `P0` · `BE` · 프로젝트 생성 API
- [x] **BI-014** · `P0` · `BE` · 프로젝트 목록 조회 API
- [x] **BI-015** · `P0` · `BE` · 프로젝트 상세 조회 API
- [x] **BI-016** · `P0` · `BE` · 프로젝트 수정 API
- [x] **BI-017** · `P0` · `BE` · 프로젝트 상태 관리 로직
- [x] **BI-018** · `P0` · `BE` · 프로젝트 최근 수정일 갱신 로직
- [x] **BI-019** · `P1` · `BE` · 프로젝트 제거 API
- [x] **BI-020** · `P1` · `BE` · 프로젝트 영구 삭제 API
- [x] **BI-021** · `P0` · `BE` · GitHub 레포 목록 조회
- [x] **BI-022** · `P0` · `BE` · 기존 레포 연결 API
- [x] **BI-023** · `P0` · `BE` · GitHub 레포 불러오기 API
- [x] **BI-024** · `P1` · `BE` · 새 GitHub 레포 생성 API
- [x] **BI-025** · `P1` · `BE` · 레포 연결 해제 API — 완료 (2026-07-18, ROADMAP U5, PR #46. `DELETE /projects/{id}/repository`, 비파괴·GitHub 호출 0)
- [x] **BI-026** · `P0` · `BE` · 연결 레포 접근 제한 정책 구현
- [x] **BI-027** · `P1` · `BE` · GitHub 레포 삭제 API

### EPIC 04. GitHub Version Flow

- [x] **BI-034** · `P0` · `BE` · preview 브랜치 생성 로직
- [x] **BI-035** · `P0` · `BE` · 파일 변경사항 커밋 로직
- [x] **BI-036** · `P0` · `BE` · 요청 단위 커밋 생성
- [x] **BI-038** · `P0` · `BE` · PR 생성 로직
- [x] **BI-039** · `P0` · `BE` · PR merge 로직
- [x] **BI-041** · `P0` · `BE` · merge 후 버전 증가 로직
- [x] **BI-042** · `P1` · `BE` · 버전 이력 조회 API
- [x] **BI-043** · `P0` · `BE` · 버전별 커밋 해시 저장
- [x] **BI-044** · `P0` · `BE` · 버전별 작업 요약 저장
- [x] **BI-045** · `P1` · `BE` · 특정 버전 배포 로직
- [x] **BI-047** · `P1` · `BE` · 최근 반영 이력 API
- [x] **BI-048** · `P1` · `BE` · 최근 커밋 이력 API

### EPIC 05. Agent Orchestration

- [x] **BI-049** · `P0` · `BE` · 의사결정 Agent 서버 구조 구현
- [x] **BI-050** · `P0` · `BE` · 요청 의도 분류 로직
- [x] **BI-052** · `P0` · `BE` · 코드 Agent 실행 구조
- [x] **BI-053** · `P0` · `BE` · 배포 Agent 실행 구조
- [x] **BI-054** · `P1` · `BE` · 도메인 Agent 실행 구조
- [x] **BI-056** · `P0` · `BE` · Agent 작업 계획 생성
- [x] **BI-057** · `P0` · `BE` · 승인창용 요약 생성
- [x] **BI-058** · `P0` · `BE` · Agent 실행 상태 저장
- [x] **BI-059** · `P1` · `BE` · Agent 실행 로그 저장
- [x] **BI-060** · `P0` · `BE` · Agent 실패 처리
- [x] **BI-061** · `P1` · `BE` · Agent 작업 재시도 로직
- [x] **BI-055** · `P1` · `BE` · 인프라 운영 Agent 실행 구조 — 완료 (2026-07-18, Issue #54 / PR #59. `AgentType.INFRA_OPERATE` + `InfraOpsAgentService`, chat 경유·신규 HTTP 엔드포인트 없음)

### EPIC 06. Chat Session

- [x] **BI-062** · `P0` · `BE` · 대화 세션 모델 설계
- [x] **BI-063** · `P0` · `BE` · 대화 세션 생성 API
- [x] **BI-064** · `P0` · `BE` · 대화 세션 목록 API
- [x] **BI-065** · `P0` · `BE` · 대화 메시지 저장
- [x] **BI-066** · `P0` · `BE` · 대화 세션 상세 조회
- [x] **BI-067** · `P1` · `BE` · 대화 제목 자동 생성
- [x] **BI-068** · `P1` · `BE` · 대화 세션 삭제 처리
- [x] **BI-069** · `P1` · `BE` · 휴지통 목록 API
- [x] **BI-070** · `P1` · `BE` · 대화 복구 API
- [x] **BI-071** · `P2` · `BE` · 대화 즉시 삭제 API
- [x] **BI-072** · `P2` · `BE` · 7일 후 자동 완전 삭제 스케줄러
- [x] **BI-073** · `P2` · `BE` · 연결 불가 대화 복구 제한

### EPIC 07. Docker Preview / Infra Runtime

- [x] **BI-074** · `P0` · `Infra` · Docker preview 아키텍처 설계
- [x] **BI-075** · `P0` · `Infra` · 프로젝트별 컨테이너 생성
- [x] **BI-076** · `P0` · `Infra` · 프로젝트 파일 컨테이너 마운트 / 복사
- [x] **BI-077** · `P0` · `Infra` · 의존성 설치 자동화
- [x] **BI-078** · `P0` · `Infra` · 빌드 명령 자동 실행
- [x] **BI-080** · `P0` · `Infra` · Preview 내부 프록시 생성
- [x] **BI-081** · `P1` · `Infra` · 외부 공개 URL 차단 — G1만 완료 (2026-07-25, Issue #76 / PR #78. `DockerContainerService` 호스트 포트 바인딩을 `bindIpAndPort("127.0.0.1", 0)`으로 전환해 컨테이너 포트의 `0.0.0.0` 외부 퍼블리시를 차단, 게이트웨이 프록시 경로는 무영향). G2(게이트웨이 소유권/JWT 미검증)·G4(accessToken 회전·폐기 없음)는 Issue #77로 분리, 미착수
- [x] **BI-082** · `P0` · `BE` · 컨테이너 상태 조회 API — 완료 (2026-07-18, ROADMAP U4, PR #47. `GET /preview-sessions/{id}/status`)
- [x] **BI-083** · `P0` · `Infra` · 컨테이너 로그 수집 — 완료 (2026-07-18, ROADMAP U4, PR #47. `GET /preview-sessions/{id}/logs`)
- [x] **BI-084** · `P1` · `Infra` · 미사용 컨테이너 자동 종료
- [x] **BI-088** · `P0` · `Infra` · 정적 프로젝트 preview 지원
- [x] **BI-089** · `P0` · `Infra` · React 프로젝트 preview 지원

### EPIC 08. Approval System

- [x] **BI-093** · `P0` · `BE` · Approval 모델 설계
- [x] **BI-094** · `P0` · `BE` · 개발/수정 Approval 생성
- [x] **BI-095** · `P0` · `BE` · 배포 Approval 생성
- [x] **BI-096** · `P1` · `BE` · 도메인 Approval 생성
- [x] **BI-098** · `P0` · `BE` · Approval 승인 API
- [x] **BI-099** · `P1` · `BE` · Approval 거절 API
- [x] **BI-100** · `P0` · `BE` · Chat Settings 승인 정책 반영
- [x] **BI-101** · `P0` · `BE` · 승인 후 작업 실행 큐 연결
- [x] **BI-102** · `P1` · `BE` · 승인 결과 이력 저장
- [x] **BI-097** · `P1` · `BE` · 인프라 Approval 생성 — 완료 (2026-07-18, ROADMAP U7, Issue #49 / PR #51. `INFRA_OPERATION` standalone 승인, `StandaloneApprovalHandler`+`InfrastructureChangeApprovalHandler`)

### EPIC 09. Deployment

- [x] **BI-103** · `P0` · `BE` · 배포 모델 설계
- [x] **BI-104** · `P0` · `BE` · GitHub Pages 배포 로직
- [x] **BI-105** · `P0` · `BE` · GitHub Pages 설정 연동
- [x] **BI-106** · `P1` · `BE` · GitHub Actions workflow 실행
- [x] **BI-107** · `P1` · `BE` · 신규 workflow 파일 생성
- [x] **BI-108** · `P0` · `BE` · 최신 승인 버전 배포
- [x] **BI-109** · `P0` · `BE` · 배포 상태 관리
- [x] **BI-110** · `P0` · `BE` · 배포 URL 저장
- [x] **BI-111** · `P0` · `BE` · 배포 실패 감지
- [x] **BI-112** · `P1` · `BE` · 배포 실패 원인 분석 — 완료 (2026-07-18, ROADMAP U6, Issue #48 / PR #50. `POST/GET /deployments/{id}/failure-analysis`, LLM+룰 fallback)
- [x] **BI-113** · `P1` · `BE` · 배포 재시도 API — 완료 (2026-07-18, ROADMAP U6, Issue #48 / PR #50. `POST /deployments/{id}/retry`)
- [x] **BI-114** · `P1` · `BE` · 특정 버전 재배포
- [x] **BI-115** · `P1` · `BE` · 배포 결과 검증

### EPIC 10. AWS / GCP Infra

- [x] **BI-116** · `P1` · `Infra` · AWS 계정 연결 처리
- [x] **BI-117** · `P1` · `Infra` · GCP 계정 연결 처리
- [x] **BI-118** · `P1` · `BE` · 클라우드 제공자 설정 저장
- [x] **BI-119** · `P1` · `BE` · 연결 클라우드 계정 저장
- [x] **BI-120** · `P1` · `BE` · 리전 설정 저장
- [x] **BI-121** · `P1` · `BE` · 배포 아키텍처 설정 저장 — 완료 (2026-07-18, ROADMAP U7, Issue #49 / PR #51. V25 `project_infrastructure_settings`, `DeploymentArchitecture` enum)
- [x] **BI-122** · `P1` · `BE` · 컴퓨팅 리소스 설정 저장 — `ComputeTier`(MICRO/SMALL/MEDIUM/LARGE, provider-중립)
- [x] **BI-123** · `P1` · `BE` · 스토리지 설정 저장 — `StorageType`(NONE/OBJECT_STORAGE)
- [x] **BI-124** · `P1` · `BE` · 네트워크 설정 저장 — `NetworkAccess`(PUBLIC/PRIVATE)
- [x] **BI-128** · `P1` · `Infra` · 현재 인프라 상태 조회
- [x] **BI-129** · `P1` · `BE` · 인프라 설정 변경 이력 저장 — `GET .../configuration/history`, `project_infrastructure_setting_changes`(APPLIED/PENDING_APPROVAL/REJECTED)

### EPIC 11. Domain / DNS / HTTPS

- [x] **BI-132** · `P0` · `BE` · 도메인 모델 설계
- [x] **BI-133** · `P1` · `BE` · qeploy.com 서브도메인 중복 확인
- [x] **BI-134** · `P1` · `BE` · qeploy.com 서브도메인 연결
- [x] **BI-135** · `P1` · `BE` · 커스텀 도메인 등록
- [x] **BI-136** · `P1` · `Infra` · DNS 설정값 생성
- [x] **BI-137** · `P1` · `Infra` · DNS 설정 확인
- [x] **BI-138** · `P1` · `Infra` · HTTPS 적용 상태 확인
- [x] **BI-139** · `P1` · `Infra` · HTTPS 처리 자동화
- [x] **BI-140** · `P2` · `Infra` · HTTPS 강제 적용 설정
- [x] **BI-143** · `P1` · `BE` · 도메인 연결 실패 처리

### EPIC 12. Cost / Budget

- [x] **BI-144** · `P1` · `BE` · 예상 월 비용 계산 로직 — 완료 (2026-07-18, Issue #53 / PR #58. V27, `InfrastructureCostEstimator` 정적 가격표 기반 온더플라이 계산)
- [x] **BI-145** · `P1` · `BE` · 리소스별 비용 계산 — `resourceCosts` 배열(항목별 monthlyCost)
- [x] **BI-146** · `P1` · `BE` · 예산 설정 저장 — `project_budget_settings`, `PUT/DELETE /projects/{id}/settings/cost-budget`
- [x] **BI-147** · `P1` · `BE` · 예산 초과 감지 — `budgetStatus`(NO_BUDGET/NOT_EVALUABLE/OVER_BUDGET/WITHIN_BUDGET)

### EPIC 13. Environment / Secrets

- [x] **BI-152** · `P0` · `BE` · 환경변수 모델 설계 — 완료 (2026-07-18, ROADMAP U3, Issue #41 / PR #44. V22 `environment_variables`)
- [x] **BI-153** · `P0` · `BE` · 환경변수 목록 API — `GET /projects/{id}/environment-variables`
- [x] **BI-154** · `P0` · `BE` · 환경변수 추가 API — `POST /projects/{id}/environment-variables`
- [x] **BI-155** · `P0` · `BE` · 환경변수 수정 API — `PATCH /projects/{id}/environment-variables/{variableId}`
- [x] **BI-156** · `P0` · `BE` · 환경변수 삭제 API — `DELETE /projects/{id}/environment-variables/{variableId}`
- [x] **BI-157** · `P0` · `BE` · Preview / Production 환경 분리 — `EnvironmentScope.PREVIEW/PRODUCTION`(COMMON 없음, 설계상 의도적 보류)
- [x] **BI-158** · `P0` · `BE` · Secret 값 암호화 저장 — secret 여부와 무관하게 값 전체 AES-256-GCM
- [x] **BI-159** · `P0` · `BE` · Secret 값 마스킹 응답 — `secret=true`는 모든 응답에서 `value=null`
- [x] **BI-160** · `P1` · `BE` · 환경값 변경 이력 저장 — `environment_variable_histories`(값 자체는 미저장, append-only)

### EPIC 14. Project Settings API

- [x] **BI-161** · `P0` · `BE` · General Settings API
- [x] **BI-162** · `P0` · `BE` · Repository Settings API — 완료 (2026-07-18, ROADMAP U5, Issue #43 / PR #46. `GET /projects/{id}/settings/repository`)
- [x] **BI-166** · `P1` · `BE` · Cloud Infrastructure Settings API
- [x] **BI-167** · `P1` · `BE` · Cost & Budget Settings API — 완료 (2026-07-18, Issue #53 / PR #58. `GET /projects/{id}/settings/cost-budget`)
- [x] **BI-168** · `P0` · `BE` · Chat Settings API
- [x] **BI-169** · `P0` · `BE` · Danger Zone API

### EPIC 15. Cloud Ops Agent

- [x] **BI-170** · `P1` · `Infra` · 서버 상태 확인 기능 — 완료 (2026-07-18, Issue #54 / PR #59. `STATUS_CHECK`, 배포/Preview/클라우드연결/인프라설정 4행 고정 템플릿)
- [x] **BI-171** · `P1` · `Infra` · 서버 로그 조회 기능 — `LOG_VIEW`, ACTIVE preview 컨테이너 stdout/stderr tail 50줄/2000자
- [x] **BI-172** · `P1` · `BE` · 장애 원인 분석 기능 — `FAILURE_ANALYSIS`, U6 `DeploymentFailureAnalysisService` 재사용
- [x] **BI-173** · `P1` · `Infra` · 서버 재시작 기능 — `RESTART`, ACTIVE preview 세션 한정, INFRA_OPERATION 승인 대상
- [x] **BI-176** · `P1` · `BE` · 서비스 영향 작업 감지 — `InfraOperation.serviceImpact`/`impactMarkers()`
- [x] **BI-177** · `P1` · `BE` · 비용 증가 작업 감지 — `InfraOperation.costImpact`/`impactMarkers()`

### EPIC 16. 오류 처리 / 자동 수정

- [x] **BI-180** · `P0` · `BE` · 빌드 실패 감지
- [x] **BI-181** · `P0` · `Infra` · 실패 로그 수집
- [x] **BI-182** · `P0` · `BE` · 실패 원인 분석 Agent 연결
- [x] **BI-183** · `P0` · `BE` · 최선 수정안 1개 생성
- [x] **BI-184** · `P0` · `BE` · 수정안 승인 후 코드 수정
- [x] **BI-185** · `P0` · `Infra` · 수정 후 재빌드 실행
- [x] **BI-186** · `P1` · `BE` · 배포 실패 원인 분석 — 완료 (2026-07-18, ROADMAP U6, Issue #48 / PR #50. BI-112과 동일 API, EPIC 16 관점 중복 항목)
- [x] **BI-187** · `P1` · `BE` · 오류 해결 이력 저장

### EPIC 17. Security / Audit

- [x] **BI-193** · `P0` · `BE` · 위험 작업 이중 확인 처리
- [x] **BI-194** · `P0` · `Infra` · Preview 컨테이너 격리 정책 — 완료 (2026-07-18, ROADMAP U4, Issue #42 / PR #47. 메모리 1GiB/CPU 1코어/pids 256/capDrop ALL+3/no-new-privileges/전용 네트워크 icc=false)
- [x] **BI-188** · `P1` · `BE` · 감사 로그 모델 설계 — 완료 (2026-07-25, Issue #74 / PR #75. V30 `audit_logs`, FK 0개, 인덱스 3개, `audit` 도메인 4계층 신설)
- [x] **BI-189** · `P1` · `BE` · GitHub 작업 로그 저장 — 완료 (2026-07-25, Issue #74 / PR #75. 레포 생성/연결/해제/삭제, preview push, RESULT merge 6종 액션)
- [x] **BI-190** · `P1` · `BE` · 배포 작업 로그 저장 — 완료 (2026-07-25, Issue #74 / PR #75. 요청/재시도/성공/실패 4종 액션)
- [x] **BI-191** · `P1` · `BE` · 도메인 작업 로그 저장 — 완료 (2026-07-25, Issue #74 / PR #75. 연결/해제 2종 액션 — 삭제 시 행이 사라지는 `domain_bindings`의 유일한 잔존 기록)
- [x] **BI-192** · `P1` · `BE` · 인프라 작업 로그 저장 — 완료 (2026-07-25, Issue #74 / PR #75. preview 재시작, 인프라 설정 변경 요청/적용/거절 4종 액션)

## Removed

아래 항목은 Superthread task로 만들지 않는 것을 추천합니다.

| ID | 작업 | 삭제 이유 |
| --- | --- | --- |
| BI-028~033 | ZIP 업로드 / 파일 처리 | 현재 제품 흐름이 GitHub App + Agent 기반 생성/수정이라 MVP에서 불필요 |
| BI-040 | 버전 모델 설계 | `deployment_histories` 기반 버전 이력으로 흡수됨 |
| BI-046 | 롤백 처리 로직 | 특정 버전 재배포와 같은 흐름으로 처리하는 편이 단순함 |
| BI-125~127 | 오토스케일링/로드밸런서/DB 설정 저장 | IaC 설계 단계에서 다시 정의하는 편이 안전함 |
| BI-148~151 | 고급 비용 예측/최적화 | Cost/Budget MVP 이후 확장으로 충분함 |
| BI-174~175 | 리소스 스펙/오토스케일링 변경 | Cloud Ops 초기 범위를 넘는 운영 자동화 |
| BI-178~179 | 비용 최적화/불필요 리소스 정리 | 실제 Cloud deploy/IaC 이후에 새로 정의하는 편이 맞음 |

## Recommended Next Sprint

U1~U7, Issue #45, Cost & Budget, Cloud Ops Agent, Issue #56(결과 승인 2단계), Issue #74(Audit Log, BI-188~192), Issue #76(Preview 외부 노출 차단, BI-081/G1)이 모두 완료(2026-07-25)되어 이전 스프린트 목표는 모두 처리되었다. 다음 우선순위는 `ROADMAP.md` "이후 백로그" 순서를 따른다.

1. ~~`BI-188~192·195` Audit Log(EPIC 17 나머지)~~ — BI-188~192 완료, 1순위 소진(2026-07-25, Issue #74/PR #75). BI-195는 부분 완료(위 EPIC 17 Backlog 참고)
2. ~~`BI-081` 외부 공개 URL 차단~~ — G1 완료, 2순위 소진(2026-07-25, Issue #76/PR #78). 잔여 G2/G4는 Issue #77(FE 조율 후 착수, 이 보드에는 아직 미등재)로 분리
3. `BI-163~165` 나머지 Project Settings(Version Policy/Deployment Defaults/Domain)를 채운다. — 다음 단위 1순위
4. Issue #55(Agent 오케스트레이션 하드닝)·#57(E2E 결함 H1/M3)·#62(결과 승인 리뷰 Low 등급 잔여)를 처리한다 — Cost/CloudOps/결과 승인 리뷰에서 파생. (`ROADMAP.md` 기준 #55·#57·#62 B3는 이미 완료 처리된 것으로 보임 — 이 보드 항목은 이번 개정에서 재검증하지 않음, 확인 필요)
5. 실제 AWS/GCP 프로비저닝(`BI-130~131` 등)은 IaC 설계가 선행되어야 하므로 후순위로 유지한다.
