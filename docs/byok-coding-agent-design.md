# 외부 AI 코딩 에이전트 개인계정 연동 설계 (BYOK)

> 기준일: 2026-09-05 · 기준 브랜치: `develop` @ `4f31212` · 코드 실측 기반
> 구현 상태: 설계 확정 · 미착수(변형 A 범위) · 브랜치 `danto/coding-agent-byok`
> 요구사항 정본: `.notion/srs.md` · 상위 PRD: `.notion/prd.md` 부록 A-1

---

## 목표

Qeploy 사용자가 **자기 공식 API 키**를 등록하면(BYOK), Qeploy 에이전트가 그 키로 **Claude Code / Codex CLI를 격리 컨테이너에서 헤드리스 실행**해 코딩·배포 보조(빌드 로그 분석, Dockerfile 최적화 등)를 수행한다. 토큰 사용량은 사용자 계정에 직접 청구된다. 공식 API 엔드포인트만 사용하므로 AWS 데이터센터 IP 차단 문제도 발생하지 않는다.

## 비목표 (하지 않는다)

- 구독(Pro·Max·Plus) 자격증명 라우팅 / claude.ai·chatgpt.com 로그인 임베딩
- 브라우저 세션 쿠키·액세스 토큰 가로채기
- 비공식·리버스 엔지니어링 엔드포인트 호출
- Cloudflare 봇 차단·데이터센터 IP 차단 우회
- 운영자 소유 키·구독의 다수 사용자 풀링/재판매/중개

## 컴플라이언스 근거 (2026-09-05 공식 문서 실측)

| 방식 | Claude Code / Agent SDK | OpenAI Codex | 판정 |
|---|---|---|---|
| 구독 자격증명 제3자 임베딩 | **공식 금지**(2026-02-20 명문화, 04-04 시행) | 비공식·취약 | 채택 안 함 |
| 구독 OAuth 토큰 헤드리스 | ~10~15분 후 401 무갱신 + 임베딩 금지 | 미지원 | 채택 안 함 |
| **BYOK(사용자 본인 API 키)** | **명시적 허용·권장**(`ANTHROPIC_API_KEY`) | 허용(회색지대, 보안 주의) | **채택** |
| 운영자 키·구독 풀링 | 금지("결제·재판매·중개 금지") | 동일 | 채택 안 함 |

Anthropic 원문: "제3자 개발자가 자기 앱에 claude.ai 로그인을 제공하거나, 사용자를 대신해 Free·Pro·Max 자격증명으로 요청을 라우팅하는 것을 허용하지 않는다. … 각 최종 사용자는 자신의 API 키로 인증해야 하며, 그 사용량은 사용자에게 직접 청구된다."

**착수 전 확인:** OpenAI BYOK는 약관상 허용이나 회색지대라 키 저장·전송 보안을 강하게 잡는다. 구독 임베딩을 굳이 원하면 Anthropic 세일즈 서면 승인이 선행되어야 하며 그 전엔 착수하지 않는다.

## 아키텍처 (변형 A — 서버측 실행)

- 작업(Task)마다 격리 Docker 컨테이너를 띄우고 그 안에서 공식 CLI를 헤드리스 구동:
  - Claude: `ANTHROPIC_API_KEY=<사용자 키> claude -p "<task>"` 또는 Claude Agent SDK.
  - OpenAI: `OPENAI_API_KEY=<사용자 키>` + Codex CLI 비대화 실행.
- EC2가 사용자 키(암호화 저장)를 복호화해 컨테이너 env로만 주입한다. 디스크 미기록, egress는 공식 API 도메인(`api.anthropic.com`, `api.openai.com`)으로 제한.
- 기존 `agent/infrastructure/docker/DockerContainerService`의 격리 정책(자원 상한·capability drop·no-new-priv·네트워크 격리, U4)을 재사용한다.
- 브라우저·확장·WebSocket은 필요 없다(변형 A). 원안의 브라우저 브리지는 주거용 IP·세션 확보용이었고 BYOK+공식 API에선 그 목적이 사라진다.

## 코드 접점 & 소유 경계 (충돌 회피)

9/4~9/5 unhak이 `AgentOrchestrator`·`ChatCommandService`·`AiProperties`·`agent/infrastructure/llm/**`에 진입했다. 신규 로직은 **새 패키지로 격리**하고 공유 파일은 **append-only**로만 만진다.

| 대상 | 소유·처리 | 충돌 위험 |
|---|---|---|
| `agent/application/port/out/CodingAgentPort.java` (신규) | 신규 전용 | 없음 |
| `agent/infrastructure/codingagent/ClaudeCodeCliAdapter.java`, `CodexCliAdapter.java` (신규) | 신규 전용 | 없음 |
| `aiaccount/**` (신규 도메인: 사용자 API 키 저장/조회/삭제) | 신규 전용 | 없음 |
| `AiProvider` enum | 값 `CLAUDE_CODE`, `CODEX` 끝에 append | 낮음 |
| `LlmRouter` | 코딩 CLI provider면 별도 분기 추가(기존 case 미변경) | 낮음 |
| `AiProperties` | `codingAgent` 중첩 프로퍼티 추가만 | 낮음 |
| `ErrorCode` enum | 항목 끝에만 추가 | 낮음 |
| `application-*.yml` | 새 블록만 추가 | 낮음 |
| Flyway | 최신 V42 다음 **V43 예약**(이슈 명시) → unhak V44부터 | 조율 필요 |
| `docs/FRONTEND_API_GUIDE.md` | 새 섹션만 추가 | 낮음 |

핵심: 변형 A는 기존 `LlmRouter`/HTTP 클라이언트(unhak 핫파일)를 거의 건드리지 않는다. CLI 어댑터가 별도 포트로 완전 분리되기 때문. (기존 HTTP 클라이언트까지 per-user 키 BYOK를 원하면 `LlmToolPort`·`AiProperties`를 손대야 해 충돌 위험이 커지므로 별도 후속 단위로 분리한다.)

## 데이터 모델 (V43)

사용자·제공자별 API 키를 기존 `AesEncryptor`(AES/GCM, `auth.infrastructure.persistence.converter`)로 암호화 저장. 신규 crypto 코드 없음(U3 방침).

`V43__add_ai_provider_credentials.sql` (구현본):
```sql
CREATE TABLE ai_provider_credentials (
    ai_provider_credential_id BIGINT       NOT NULL AUTO_INCREMENT,
    user_id                   BIGINT       NOT NULL,
    provider                  VARCHAR(20)  NOT NULL,          -- ANTHROPIC | OPENAI | GLM (벤더 단위)
    encrypted_api_key         MEDIUMTEXT   NOT NULL,          -- AesEncryptor 컨버터로 저장
    label                     VARCHAR(64)  NULL,
    created_at                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (ai_provider_credential_id),
    UNIQUE KEY uk_ai_provider_credentials_user_provider (user_id, provider),
    CONSTRAINT fk_ai_provider_credentials_user
        FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE
);
```

**`provider`는 실행 모드가 아니라 벤더 단위다(구현 시 확정).** Claude Code 는 `ANTHROPIC` 키를, Codex 는 `OPENAI` 키를 쓰므로 실행 모드(`CLAUDE_CODE`/`CODEX`)별로 행을 나누면 사용자가 같은 키를 두 번 넣어야 한다. 실행 모드는 이 표를 조회할 때 벤더로 환산한다.

엔티티는 `EnvironmentVariableEntity` 패턴을 그대로 따른다: `@Convert(converter = AesEncryptor.class)`, `@ToString` 금지. 마스킹은 선행 6자만 남긴다(`sk-ant****`) — 접두사는 벤더별로 거의 상수라 식별용이고, 꼬리는 실제 엔트로피라 노출하지 않는다.

저장소 포트의 모든 조회는 `userId` 스코프다. id 단독 조회·전체 조회를 두지 않아 남의 키에 도달하거나 여러 사용자 키를 한데 모으는 경로가 구조적으로 생기지 않는다.

## 병렬 안전 PR 계획

브랜치 `danto/coding-agent-byok`(develop 분기, 수명 1일, 매일 rebase). 소형 PR 순차.

1. **PR-1** 크리덴셜 도메인(`aiaccount/**`) + `CodingAgentPort` 정의 + **V43**. (신규 파일 위주)
2. **PR-2** `ClaudeCodeCliAdapter` — 격리 컨테이너 헤드리스 실행 + 타임아웃·재시도.
3. **PR-3** `CodexCliAdapter` — 동일 패턴.
4. **PR-4** `AiProvider` enum append + `LlmRouter` 분기 + 오케스트레이터 배선.
5. **PR-5** 키 등록/조회(마스킹)/삭제 엔드포인트 + `FRONTEND_API_GUIDE.md` 섹션 + `api.md`·`state.md` 갱신.

DoD: 각 PR `./gradlew test` 통과 + FE 정본 repo(`/Users/otter/Dvely_FE_test`) 관련 플로우 확인.

## 향후 (현재 범위 밖)

- **변형 B — 클라이언트측 실행:** 사용자 키를 브라우저에만 보관하고 확장(MV3)이 공식 API를 호출한 뒤 결과를 WebSocket으로 컨테이너에 토스. 키를 서버에 두지 않는 게 유일한 이점, 복잡도↑. 키 서버저장을 꺼리는 사용자 요구가 생기면 별도 단위로 검토.
- **기존 HTTP 클라이언트 BYOK:** 위 표 참고. 공유 핫파일을 만지므로 별도 후속.

## 근거 문서
Claude Code Legal & Compliance, Claude Agent SDK Overview, OpenAI Codex device-auth 문서(2026-09-05 확인).
