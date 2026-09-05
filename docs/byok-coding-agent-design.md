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
  - **Claude Code**: `ANTHROPIC_API_KEY` env + `claude -p "<task>"`.
  - **Codex**: env 가 아니라 **로그인 단계**가 필요하다. `codex login --with-api-key`(키를 **stdin** 으로) 실행 후 `codex exec "<task>"`.

> **인증 방식 실측 (2026-09-05, Claude Code 2.1.260 · codex-cli 0.153.2)**
> 두 CLI 의 키 수용 방식이 다르다. 이미지를 빌드해 실제로 돌려 확인한 결과:
> - `claude -p` 는 `ANTHROPIC_API_KEY` 를 읽어 API 에 도달한다(계정 잔액 부족 응답까지 확인).
> - `codex exec` 는 **`OPENAI_API_KEY` 를 읽지 않는다** — `401 Missing bearer or basic authentication in header`. `codex login --with-api-key`(stdin)로 먼저 로그인해야 하며, 그 뒤 `codex login status` 가 "Logged in using an API key" 를 보고하고 `codex exec` 가 정상 인증된다.
> - stdin 은 보안상으로도 낫다. env 와 달리 `/proc/<pid>/environ` 에 남지 않는다.
> - `codex exec` 는 git 저장소 밖을 거부한다(`--skip-git-repo-check` 미지정 시). 편집을 되돌릴 수 있게 하려는 안전장치이고 Qeploy 워크스페이스는 clone 이라 자연히 통과하므로, 기본값에 그 플래그를 넣지 않았다.

> **end-to-end 검증 완료 (2026-09-05)**
> 유효한 OpenAI 키로 전 구간이 통과했다. `codex login --with-api-key`(stdin) → `codex exec --model gpt-5.6-luna "..."` → **exit 0, 에이전트 응답 "OK"**.
> **모델은 별도 설정(`qeploy.coding-agent.codex.model`)으로 뺐고 기본값은 `gpt-5.6-luna`다.** CLI 기본값은 `gpt-5.6-sol` 인데, 같은 프롬프트로 luna 가 9,692 토큰, sol 이 11,203 토큰을 썼고 답은 같았다 — 기본으로 큰 등급을 태울 이유가 없다. `terra` 도 동작 확인(11,203). 비우면 CLI 기본값을 쓴다.
> Claude Code 쪽은 인증·전송 경로까지 확인했으나 계정 잔액 부족으로 모델 응답까지는 보지 못했다.
> **에이전트가 파일을 쓰려면 Codex 자체 샌드박스를 우회해야 한다 (2026-09-05 실측)**
> bubblewrap 경고를 쫓다가 훨씬 큰 문제를 찾았다. Codex 자체 샌드박스는 bubblewrap 을 쓰고 그건 user namespace 를 요구하는데, 우리 컨테이너는 모든 capability 를 떨구고 `no-new-privileges` 를 걸어 bubblewrap 이 뜨지 못한다. 그 상태에서는 에이전트의 셸 툴 호출이 전부 `exit 1` 로 죽는다 — **`-s workspace-write` 를 줘도 "성공"(exit 0)을 보고하면서 파일을 하나도 만들지 못했다.** 코딩 에이전트가 코드를 못 고치면 무의미하므로 이건 치명적이다.
> 해법은 `--dangerously-bypass-approvals-and-sandbox` 다. 이름과 달리 지름길이 아니라 이 배치에 맞는 선택이고, 플래그 문서가 바로 이 경우를 가리킨다("외부에서 이미 샌드박싱된 환경 전용"). 일회용 컨테이너가 그 외부 샌드박스다: 퍼블리시 포트 없음, cap-drop ALL, 메모리·pids 상한, 워크스페이스만 마운트. 이 플래그로 같은 프롬프트가 **실제로 파일을 썼다**(`agent-wrote-this.txt`, 내용 `verified`, exit 0, bubblewrap 경고 0건).
> `bubblewrap` 은 이미지에 넣어 뒀지만 기본 경로가 Codex 샌드박스를 우회하므로 실제로는 쓰이지 않는다. 격리를 느슨하게 풀어 Codex 자체 샌드박스를 쓰는 배포를 위해 남겨 둔 것이다.
>
> **Claude Code 쪽 미해결 위험**: 같은 종류의 문제가 있을 수 있는데 확인하지 못했다. 잔액 부족이라 파일 편집 단계까지 못 갔고, 탈출구인 `--allow-dangerously-skip-permissions` 는 **root 에서 거부된다**("cannot be used with root/sudo privileges"). 현재 이미지는 root 로 돌아가므로, 이 플래그가 필요한 것으로 판명되면 비-root 사용자 추가가 선행되어야 한다(bind mount 소유권 문제를 함께 풀어야 함). 유효한 Anthropic 키가 생기면 가장 먼저 확인할 항목.

> **Java 코드 경로 실측 — stdin 하이재킹 결함 발견·수정 (2026-09-05)**
> 수작업 검증은 `docker exec -i`(CLI)로 했기 때문에 정상이었지만, 제 러너의 stdin 경로는 전부 mock 테스트라 실 데몬에서 돌아본 적이 없었다. 게이트형 통합 테스트(`CodexCliAdapterIntegrationTest`, `-Ddocker.it=true` + `QEPLOY_IT_OPENAI_API_KEY`)를 만들어 러너→어댑터를 그대로 통과시키자 **5초 만에 `AsynchronousCloseException`** 이 났다. 원인은 docker-java의 **OkHttp 전송이 exec stdin 하이재킹을 지원하지 않는 것** — 요청 본문(stdin)을 보낸 뒤 연결 반쪽을 닫아, 응답 프레임을 읽던 쪽이 죽는다. 전송 계층 교체는 `DockerContainerService`까지 파급되므로 하지 않았다.
> **수정:** stdin 을 하이재킹하지 않는다. 키를 **archive 업로드 API**(`copyArchiveToContainerCmd`, 일반 HTTP PUT)로 `/run/qeploy/stdin`(0600)에 스테이징하고, 상수 래퍼 `"$@" < /run/qeploy/stdin; s=$?; rm -f ...; exit $s` 를 `sh -c` 로 실행한다. 실제 argv 는 `"$@"` 위치 인자로만 전달되어 절대 보간되지 않으므로 주입 표면은 그대로 0 이다. 키는 여전히 argv·env·inspect 에 남지 않고, 컨테이너 안 파일로 잠깐 존재했다가 래퍼가 지운다.
> **결과:** 통합 테스트 통과 — 로그인 → `codex exec` → 워크스페이스에 파일 실제 생성 → 컨테이너 잔존 0. 이 테스트가 이제 "exit 0 인데 아무것도 안 함" 류의 조용한 회귀를 막는다. 게이트 없이 돌리면 skip 되어 CI 는 초록을 유지한다.
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
| Flyway | **V45** 사용. V43·V44 를 develop 이 연속으로 선점했다(아래 참고) | 조율 필요 |
| `docs/FRONTEND_API_GUIDE.md` | 새 섹션만 추가 | 낮음 |

핵심: 변형 A는 기존 `LlmRouter`/HTTP 클라이언트(unhak 핫파일)를 거의 건드리지 않는다. CLI 어댑터가 별도 포트로 완전 분리되기 때문. (기존 HTTP 클라이언트까지 per-user 키 BYOK를 원하면 `LlmToolPort`·`AiProperties`를 손대야 해 충돌 위험이 커지므로 별도 후속 단위로 분리한다.)

## 데이터 모델 (V45)

사용자·제공자별 API 키를 기존 `AesEncryptor`(AES/GCM, `auth.infrastructure.persistence.converter`)로 암호화 저장. 신규 crypto 코드 없음(U3 방침).

> **번호 조율 기록 (두 번 밀렸다):** 착수 시점 최신이 V42 라 V43 을 예약했는데, 같은 날 develop 이 V43(PR #241)을 가져가 V44 로 옮겼고, 이틀도 안 돼 V44(PR #244)까지 가져가 V45 로 다시 옮겼다.
>
> 이 충돌은 **git 이 잡아주지 않는다.** 파일명이 다르니 rebase 는 조용히 통과하고, Flyway 만 중복 버전으로 죽는다. 즉 브랜치가 초록불인 채로 머지되어 배포에서 터질 수 있는 종류다.
>
> 그래서 규칙: **번호는 예약하지 말고 머지 직전에 확정한다.** rebase 후 반드시 `ls src/main/resources/db/migration | sort -V | tail -1` 로 실제 최신을 다시 확인하고, 필요하면 그 자리에서 옮긴다. 병렬 작업자가 마이그레이션을 자주 추가하는 동안은 착수 시점 예약이 무의미하다.

`V45__add_ai_provider_credentials.sql` (구현본):
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

1. **PR-1** 크리덴셜 도메인(`aiaccount/**`) + `CodingAgentPort` 정의 + **V45**. (신규 파일 위주)
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
