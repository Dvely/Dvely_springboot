# AGENTS.md — Qeploy 백엔드 작업 지침

Codex(AGENTS.md)와 Claude Code(CLAUDE.md)가 **같은 현황을 이어받도록** 하는 진입점이다.
`CLAUDE.md`는 이 파일을 import 하므로, 내용은 **여기에만** 쓴다. 두 파일을 각각 고치면 갈라진다.

---

## 이 저장소

Qeploy 백엔드. 사용자가 자연어로 웹 프로젝트를 만들고, Docker 프리뷰로 확인하고, GitHub·AWS로 배포하고, 도메인까지 연결하는 AI Agent 플랫폼이다.

- Java 25 · Spring Boot 4.0.5 · MySQL 8.0 · Flyway · Gradle
- 아키텍처: 도메인별 `domain / application / infrastructure / presentation` 4계층

**브랜드와 패키지명이 다르다.** 사용자 대상 브랜드는 **Qeploy**이고 코드 패키지는 여전히 `com.example.dvely`다. rename은 규모 때문에 의도적으로 미뤄져 있다. 새 코드도 패키지는 `com.example.dvely`로 두고, 로그·응답·문서 등 **노출 문자열만 Qeploy**로 쓴다.

## 먼저 읽을 문서 (`.notion/`, 저장소에 포함됨)

| 파일 | 역할 |
|---|---|
| `prd.md` | 제품 요구사항 정본. **§0~§29 기존 텍스트 수정 금지**, 개정은 말미 "부록 A"에 append-only |
| `srs.md` | 단위별 상세 요구사항(FR/NFR) |
| `state.md` | 구현 현황 — "지금 무엇이 되고 무엇이 안 되는가"의 기준 |
| `ROADMAP.md` | **작업 순서·세션 인계의 SSOT. 새 세션은 여기 "1. 현재 상태"부터 읽는다** |
| `api.md` | API 계약 |
| `connection.md` | 모듈 연결 |
| `agent.md` · `commit.md` · `issue.md` · `pr.md` · `github.md` | Issue/Commit/PR 컨벤션 SSOT |
| `BACKLOG_STATUS.md` | PRD 백로그 원장 |

기능을 바꾸면 **실제 코드 기준으로** `api.md` · `connection.md` · `state.md`도 함께 갱신한다.

## 빌드·테스트

```bash
./gradlew test          # 전체. 실제 MySQL 필요(H2 대체 불가 — SchemaTest가 information_schema를 직접 조회)
./gradlew build         # CI가 도는 것과 같은 명령
```

- 로컬 DB 설정은 `src/main/resources/application-local.yml`(gitignore, 실제 키 포함)
- Docker를 요구하는 통합 테스트는 기본 skip이고 `-Ddocker.it=true`로만 켠다
- BYOK 코딩 에이전트 실측 테스트는 추가로 `QEPLOY_IT_OPENAI_API_KEY` 환경변수를 요구한다(없으면 skip)

## 컨벤션 (자세한 건 `.notion/agent.md`)

- **브랜치**: `<이름>/<도메인>` — 예: `danto/coding-agent-byok`, `unhak/backend-eip`
- **base는 `develop`.** `develop` push가 dev 서버 배포, `main` push가 운영 배포 트리거다
- **커밋**: `{type}({scope}): {요약} [ #이슈번호 ]` — 이슈 번호는 `[ #11 ]`처럼 **대괄호 안 공백** 형식
- **PR 제목**: `[{Module}] {요약} [ #이슈번호 ]`
- type 9종: `feat` `fix` `refactor` `config` `docs` `test` `chore` `style` `perf`
- 기능 시작 전에 Issue를 먼저 만든다. 이슈 배치: **FE 작업은 `Dvely_FE`, 서버 작업은 `Dvely_springboot`**

## ⚠️ 함정 — 여기서 실제로 사고가 났다

### 1. Flyway 마이그레이션 번호는 머지 직전에 확정한다

**착수 시점에 번호를 예약하지 마라.** 두 사람이 병렬로 마이그레이션을 추가하는 동안 예약은 무의미하다. 실제로 한 브랜치가 V43 → V44 → V45 → **V47**로 세 번 밀렸다.

이 충돌은 **git이 잡아주지 않는다.** 파일명이 다르니 rebase는 조용히 통과하고, **CI만** Flyway 중복 버전으로 죽는다. CI는 PR 브랜치 단독이 아니라 **develop과 합친 머지 커밋**을 빌드하기 때문에, 로컬 전체 통과 + CI만 대량 실패라는 형태로 나타난다.

```bash
# rebase 후, 그리고 머지 버튼 누르기 직전에 반드시:
ls src/main/resources/db/migration | sort -V | tail -1
```

### 2. 두 사람이 병렬로 작업한다 — 소유 구역을 지킨다

`git log`로 최근 핫파일을 먼저 확인하고, 남의 구역은 **append-only**로만 만진다(enum은 끝에 추가, yml은 새 블록만). 신규 로직은 가능하면 **새 패키지로 격리**한다. 큰 공유 파일을 재사용하려다 충돌을 만드는 것보다, 전용 클래스를 새로 두는 편이 낫다.

### 3. 시크릿

- `application-local.yml`, `*.pem`, `.env*`는 gitignore. 커밋 전 diff에 키 패턴이 없는지 확인한다
- 로그·예외 메시지·응답 DTO에 비밀을 넣지 않는다. 비밀을 담는 엔티티/도메인에는 **`toString`을 두지 않는다**(Lombok `@ToString`·`@Data` 금지)
- docker-java는 명령 객체의 모든 필드를 DEBUG 로그로 reflection 덤프한다. 그래서 `application.yaml`이 `com.github.dockerjava.core.command`를 WARN으로 고정하고 있다 — **풀지 말 것**

### 4. 외부 AI 연동은 BYOK만

구독(Claude Pro/Max, ChatGPT Plus) 자격증명을 제품에 임베딩하는 것은 Anthropic이 공식 금지하고 OpenAI도 미지원이다. 사용자 본인 **공식 API 키**만 받는다. 세션 가로채기·비공식 엔드포인트·차단 우회는 영구 비목표다. 배경은 `docs/byok-coding-agent-design.md`.

## 세션 인계

1. 시작할 때 `.notion/ROADMAP.md`의 "1. 현재 상태"를 읽는다
2. 단위가 바뀌거나 우선순위가 바뀌면 그 절을 **즉시** 갱신한다
3. 끝낼 때 마지막 진행 지점(어떤 단위, 어디까지, 다음에 무엇)을 남긴다

## 참고 문서 (`docs/`)

- `FRONTEND_API_GUIDE.md` — FE용 엔드포인트 카탈로그·플로우
- `byok-coding-agent-design.md` — BYOK 코딩 에이전트(실측 기록 포함)
- `multi-stack-deploy-design.md` · `backend-domain-binding-design.md` — 배포·도메인 설계
- `aws-byoc-permissions.md` — 사용자 AWS 계정에 필요한 IAM
