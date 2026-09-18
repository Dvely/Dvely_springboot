# Qeploy 작업 지침 (Issue/PR/Commit) — 컨벤션 SSOT

> 기준일: 2026-07-17 · 기준 커밋: 34fe578 · 코드 실측 기반

이 파일은 프로젝트 작업 시 일관된 Issue/PR/Commit 수행 규칙을 정의하는 **단일 기준 문서(SSOT)**다.
사용자가 " .notion 읽어"라고 요청하면 반드시 아래 파일을 먼저 읽고, 이 지침을 따른다.
`commit.md`/`github.md`/`issue.md`/`pr.md`는 이 문서의 type 목록·형식과 항상 일치해야 하며, 서로 다르면 이 문서를 기준으로 한다.

## 항상 먼저 읽을 파일
- prd.md
- srs.md
- state.md
- api.md
- connection.md
- ROADMAP.md
- github.md
- issue.md
- commit.md
- pr.md

`prd.md`는 최종 제품 요구사항의 기준 문서다(기존 요구사항 수정 금지, 개정은 부록 A append-only).
`srs.md`는 단위별 상세 요구사항(FR/NFR) 문서다.
`state.md`는 현재 구현, 신규 구현, 수정 필요 항목의 기준 문서다.
`ROADMAP.md`는 단위(Unit) 작업 순서와 세션 인계의 기준 문서다.
기능을 변경하면 실제 코드 기준으로 `api.md`, `connection.md`, `state.md`도 함께 갱신한다.

## 기본 원칙
1) 기능 시작 전에 Issue를 먼저 만든다.
2) Issue의 Task list는 PR 단위로 쪼개어 작성한다.
3) 기본은 "이슈 1개 = 커밋 1개 = PR 1개" 규칙을 따른다. `ROADMAP.md`의 단위(Unit)도 이 규칙을 그대로 따른다: 단위 1개 = 이슈 1개 + 브랜치 1개(`danto/<도메인>`) + PR 1개.
4) 커밋 메시지에는 반드시 `[ #IssueNumber ]`를 포함한다(공백형, §Issue/PR/Commit 타이틀 정규화 참고).
5) PR은 Issue 완료 시점에 생성한다.
6) PR 머지 후 Issue에서 Task를 수동으로 체크한다.
7) 모든 Task 완료 후 Issue를 수동으로 Close한다.
8) 로컬 환경 파일/시크릿은 커밋에 포함하지 않는다.
9) Issue/PR 작성안을 줄 때는 본문뿐 아니라 타이틀도 함께 제공한다.

## 작성안 응답 순서
사용자가 Issue/Commit/PR 작성안을 요청하면 항상 아래 순서와 라벨로 답한다.

1. Issue Title
2. Issue Body
3. Commit Message
4. PR Title
5. PR Body

아직 실제 Issue 번호가 없으면 `#IssueNumber` placeholder를 사용한다.
실제 번호가 있으면 모든 `#IssueNumber`를 실제 번호로 교체한다.

## Type 선택 기준 (확정 목록)

`feat`, `fix`, `refactor`, `config`, `docs`, `test`, `chore`, `style`, `perf` — 9종을 사용한다. `commit.md`/`github.md`의 목록과 동일하다.

- `feat`: 새로운 공개 기능/API/모듈을 추가한다.
- `fix`: 기존 동작이 요구사항과 다르거나 테스트 중 발견된 문제를 바로잡는다.
- `refactor`: 외부 동작 변화 없이 구조만 정리한다.
- `config`: yml, 환경 설정, 외부 서비스 설정 바인딩처럼 설정 관리 방식을 바꾼다.
- `docs`: 문서만 수정한다.
- `test`: 테스트만 추가/수정한다.
- `chore`: 빌드/패키지/운영 보조 작업이다.
- `style`: 포맷팅 등 코드 의미 변화 없는 스타일 변경이다.
- `perf`: 성능 개선이다.

요구사항이 "기존 흐름을 수정한다", "테스트 중 발견된 문제를 보정한다", "강제 동작을 제거한다"에 가까우면 `fix`를 우선 사용한다.
기능 추가처럼 보여도 기존 요구사항을 충족하기 위한 보정이면 `fix`를 사용한다.

## 예외 규칙 (통합 PR)
- 여러 Issue가 하나의 PR로 묶일 경우, PR 본문에 모든 이슈를 Refs로 연결한다.
- 커밋도 하나로 통합하는 경우, 커밋 메시지에 이슈 번호를 모두 포함한다.
  예: `feat: 통합 작업 [ #123 ][ #124 ]`
- 통합 PR 타이틀에는 포함된 이슈 범위와 이슈 번호를 함께 표기한다.
  예: `[Project+Chat] Issue A+B+C 통합 - auth/user 정합성 및 chat MVP, 후속문제해결 [ #5, #6, #8 ]`
- 실측 사례: PR #31은 브랜드 통일, chat 7일 정책, cloud-connection 실검증, deployment, agent-preview, domainbinding, project overview, webhook delivery 재시도, api 응답 계약까지 다수 커밋을 하나의 이슈(`#31`) 아래 순차 커밋으로 쌓은 뒤 PR 하나로 머지했다. 앞으로 신규 작업은 `ROADMAP.md`의 단위별로 이슈/브랜치/PR을 분리하는 것을 기본으로 한다.

## Issue/PR/Commit 타이틀 정규화

### Issue Title
항상 아래 형식을 사용한다.

```
Issue {Letter}: [{Module}] {작업 요약}
```

예:
- `Issue C: [Project+Chat] API 테스트 중 발견된 저장소 플로우 및 대화 복구 보정`
- `Issue D: [DomainBinding] 도메인 연결 및 Cloudflare/GitHub Pages 연동 추가`

### PR Title
항상 아래 형식을 사용한다.

```
[{Module}] {작업 요약} [ #IssueNumber ]
```

예:
- `[Config] Cloudflare 설정을 yml 관리 방식으로 전환 [ #IssueNumber ]`
- `[Project] 프로젝트 생성 시 GitHub 저장소 연결 강제 동작 수정 [ #IssueNumber ]`

실측 형식: PR 본문은 이모지 한글 템플릿(🔥 PR 개요 / 🔍 주요 변경 사항 / 📌 관련 이슈 / ✅ 테스트 결과)을 사용한다. 템플릿 전문은 `pr.md` 참고.

### Commit Message
항상 아래 형식을 사용한다.

```
{type}({scope}): {작업 요약} [ #IssueNumber ]
```

예:
- `fix(project): 프로젝트 생성 시 저장소 연결 강제 동작 수정 [ #IssueNumber ]`
- `config(domainbinding): Cloudflare 설정을 yml 관리 방식으로 전환 [ #IssueNumber ]`

주의(코드 실측 기반으로 확정, 과거 "공백 없이" 규칙을 대체):
- 이슈 번호 표기는 `[ #11 ]`처럼 대괄호 안에 공백을 넣는 형식을 사용한다. `git log` 실측 결과 공백형이 26건, 무공백형(`[#11]`)이 4건으로 공백형이 이미 사실상 표준이다.
- 여러 모듈을 묶는 경우 Issue/PR의 모듈 표기는 `[ModuleA+ModuleB]`처럼 `+`로 연결한다.
- commit scope는 소문자 단수형을 우선 사용한다. 예: `project`, `chat`, `domainbinding`, `config`.
- 여러 모듈을 한 커밋으로 묶는 경우 `project-chat`처럼 하이픈으로 연결한다.

## 브랜치 규칙

- `main`: 운영/배포 브랜치
- `danto/<도메인>`: 신규 단위(Unit) 작업 브랜치. 앞으로의 기본 브랜치 규칙이다. 예: `danto/agent-chat`, `danto/environment`, `danto/preview-ops`, `danto/repo-settings`, `danto/deploy-recovery`, `danto/infra-settings`, `danto/security`.
- 과거 브랜치는 `agent/unhak`, `fix` 등 다양한 이름을 혼용했다(PR #31~#33 실측). 과거 브랜치명은 유지하되, 신규 브랜치는 `danto/<도메인>` 규칙을 따른다.
- 참고용으로 남겨둔 과거 계획(`develop_이름/*`, `feature_이름/*`, `release_이름/*`, `hotfix_이름/*`)은 실제로 쓰인 이력이 없다. 신규 작업에는 사용하지 않는다.

## 브랜드 이중 상태 (반드시 인지)

- 문서/사용자 대상 브랜드는 **Qeploy**다(README, Swagger, Agent prompt, 생성 commit/PR 문구, 서브도메인 예시 `*.qeploy.com` 등).
- 코드 패키지는 여전히 `com.example.dvely`이며 `DvelyApplication`, Gradle root project명도 변경되지 않았다(`state.md` §4.8 참고).
- 이 rename은 대규모 기계적 변경이 필요해 **의도적으로 연기**되어 있다. 새 코드를 작성할 때 패키지 경로를 `com.example.dvely`로 유지하고, 브랜드 노출 문자열(로그 메시지, 사용자 응답, 문서)만 Qeploy로 작성한다.
- rename 착수 전 배포 artifact명, 로그/metric key, 외부 연동 참조 조사와 단계적 deprecation 기간 정의가 선행되어야 한다.

## 작업 흐름 체크리스트
1. Issue 생성 (issue.md 복사해서 사용)
2. Task list 작성
3. 구현
4. 테스트 수행 (예: `./gradlew test`)
5. 단일 커밋 생성 (commit.md 복사해서 사용)
6. PR 생성 (pr.md 복사해서 사용)
7. 머지 후 Issue에서 Task 체크
8. Issue 수동 Close
9. 세션 종료 시 `ROADMAP.md`의 "현재 상태/다음 단위"를 갱신한다(단위 작업인 경우).

## 절대 포함하지 말 것
- 로컬 환경 설정 파일
- 시크릿/토큰/비밀번호
- 개인 개발용 설정
