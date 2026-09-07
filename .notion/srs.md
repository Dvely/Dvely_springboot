# SRS — Qeploy 단위별 상세 요구사항

단위별로 절을 나눈다. A = BYOK 코딩 에이전트, B = 에이전트 연동(MCP·CLI).

---

# A. 외부 AI 코딩 에이전트 개인계정 연동 (BYOK)

> 기준일: 2026-09-05 · 상태: 확정(변형 A 범위) · 미착수
> 상위 PRD: `prd.md` 부록 A-1 · 상세 설계: `docs/byok-coding-agent-design.md`
> 구현 상태는 `state.md`, API 계약은 `api.md`가 이어받는다.

---

## A1. 범위
Qeploy 사용자가 자신의 공식 AI API 키를 등록하고, 그 키로 Claude Code / Codex CLI를 격리 컨테이너에서 실행해 코딩·배포 보조를 받는 기능. 1차 범위는 변형 A(서버측 실행)다.

## A2. 이해관계자·전제
- 각 사용자는 자신의 Anthropic / OpenAI **API 키**를 보유한다(구독이 아니라 키).
- Qeploy는 사용자 키를 대신 결제·재판매·중개하지 않는다.
- 실행 환경은 기존 EC2 + Docker 격리(U4 정책)를 재사용한다.

## A3. 기능 요구사항 (FR)

| ID | 요구사항 | 수용 기준 |
|---|---|---|
| FR-1 | 키 등록 | provider별 API 키 등록 시 AES/GCM 암호화 저장. 평문은 DB·로그·응답 어디에도 남지 않는다. |
| FR-2 | 키 조회(마스킹) | 조회 시 `sk-...****` 마스킹만 반환. 복호화 값은 API로 노출되지 않는다. |
| FR-3 | 키 삭제·교체 | 삭제/교체 가능, 진행 중 작업에 영향 없이 다음 작업부터 반영. |
| FR-4 | 키 유효성 검증 | 등록 시 공식 API 최소 호출로 유효성 확인, 실패를 명확한 에러코드로 반환. |
| FR-5 | 격리 실행 | 작업마다 컨테이너를 띄우고 사용자 키를 env로만 주입해 Claude Code / Codex CLI 헤드리스 실행. |
| FR-6 | 프롬프트 왕복 | 배포 스크립트 중 AI 개입 지점에서 프롬프트를 보내고 응답을 받아 파이프라인 재개. |
| FR-7 | 동기 제어 | 응답 수신까지 작업을 안전 대기, 타임아웃 시 실패로 종결(무한 대기 금지). |
| FR-8 | 재시도 | 재시도 가능한 제공자 실패는 백오프 재시도로 흡수(기존 `LlmProviderErrors` 정책과 정합). |
| FR-9 | provider 선택 | 요청이 `CLAUDE_CODE`/`CODEX` 지정 시 CLI 경로, 기존 값이면 기존 HTTP 경로로 분기. |

## A4. 비기능 요구사항 (NFR)

| ID | 요구사항 |
|---|---|
| NFR-1 (준수) | 공식 API 엔드포인트만 사용. 구독 자격증명 라우팅·세션 가로채기·비공식 엔드포인트·차단 우회 금지. |
| NFR-2 (보안) | 키는 at-rest AES/GCM 암호화(기존 `AesEncryptor` 재사용), 전송·로그 미노출, 엔티티 `@ToString` 금지. |
| NFR-3 (격리) | user_id 스코프 저장, per-user 실행 격리, 운영자 키 풀링 금지. |
| NFR-4 (네트워크) | 실행 컨테이너 egress를 공식 API 도메인으로 제한. |
| NFR-5 (가용성) | 타임아웃·재시도로 행(hung) provider로부터 파이프라인 보호. |
| NFR-6 (관측성) | 실행 시작·성공·실패·타임아웃을 감사 로그(기존 audit 도메인)로 남기되 키·프롬프트 원문은 마스킹. |

## A5. 데이터
- `ai_provider_credentials` (V43): `user_id`, `provider`, `encrypted_api_key`, `label`, timestamps. `UNIQUE(user_id, provider)`.

## A6. 인터페이스(초안, `api.md`로 승격)
- `POST /ai-credentials` — 키 등록(provider, apiKey, label). 응답 마스킹.
- `GET /ai-credentials` — 등록 목록(마스킹).
- `DELETE /ai-credentials/{provider}` — 삭제.
- 메시지 요청 provider에 `CLAUDE_CODE`/`CODEX` 허용(기존 `aiProvider` 파라미터 확장, append).

## A7. 추적성
- PRD 부록 A-1 ↔ FR-1~9 ↔ 설계 `docs/byok-coding-agent-design.md` §아키텍처~데이터 ↔ 구현 PR-1~5.

## A8. 미결·검증
- OpenAI BYOK 회색지대 → 보안 강화 + 필요 시 서면 확인.
- 변형 B(클라이언트측 키 보관 + 확장/WebSocket)는 현재 범위 밖(설계 문서 "향후" 참고).

---

# B. 에이전트 연동 — MCP 서버 · CLI

> 기준일: 2026-09-07 · 상태: 설계 · 미착수
> 상위 PRD: `prd.md` 부록 A-2 · 설계: `docs/qeploy-mcp-cli-design.md`

## B1. 범위

사용자의 코딩 에이전트(Claude Code · Codex)가 Qeploy를 도구로 호출하도록 MCP 서버와 npm CLI를 오픈소스로 제공한다. 별도 저장소, MIT.

**A 단위와 방향이 반대다.** A는 Qeploy가 사용자 키로 AI를 부르고, B는 사용자 AI가 Qeploy를 부른다. B에서 Qeploy는 추론하지 않고 AI 자격증명을 취급하지 않는다.

## B2. 전제

- 사용자가 Claude Code 또는 Codex를 이미 설치·인증해 두었다(Qeploy는 관여하지 않는다)
- Codex가 MCP를 지원함을 실측 확인했다(`codex mcp`, `codex mcp-server`)
- 현행 인증(GitHub OAuth → JWT, 액세스 토큰 1시간)은 헤드리스 클라이언트에 부적합하다 → PAT 선행

## B3. 기능 요구사항 (FR)

| ID | 요구사항 | 수용 기준 |
|---|---|---|
| B-FR-1 | PAT 발급 | 웹 UI에서 발급. **평문은 발급 시 1회만 노출**되고 서버는 해시만 보관한다 |
| B-FR-2 | PAT 목록·폐기 | 접두사·생성시각·만료·마지막 사용만 표시. 평문 재노출 불가 |
| B-FR-3 | PAT 인증 | `Authorization: Bearer qp_...` 를 기존 JWT 필터가 분기해 처리. 기존 JWT 경로 불변 |
| B-FR-4 | PAT 스코프 | `read` / `write` 2종. write 없는 토큰은 쓰기 도구에서 403 |
| B-FR-5 | PAT 만료 | 사용자가 선택(기본 90일). 만료·폐기 토큰은 401 |
| B-FR-6 | 읽기 도구 | 프로젝트·배포·프리뷰·도메인·서버·환경변수 조회를 MCP 도구로 노출 |
| B-FR-7 | 쓰기 도구 | 배포 트리거·재시도, 환경변수 설정, 도메인 연결. 도구 설명에 비가역성 명시 |
| B-FR-8 | 제외 조작 | 프로젝트·서버 삭제, 저장소 연결 해제, 승인 처리, 비용예산 변경은 **노출하지 않는다** |
| B-FR-9 | CLI | MCP와 같은 클라이언트를 쓰는 npm CLI. `--yes` 없으면 쓰기 전 확인 |
| B-FR-10 | secret 보호 | 환경변수 조회 도구는 `secret=true` 값을 반환하지 않는다(기존 계약 준수) |

## B4. 비기능 요구사항 (NFR)

| ID | 요구사항 |
|---|---|
| B-NFR-1 (보안) | PAT는 해시 저장. 평문 미보관 — A 단위 크리덴셜과 달리 벤더 전달이 없어 검증만 하면 된다 |
| B-NFR-2 (보안) | MCP 서버·CLI는 사용자 AI 자격증명을 읽지도 저장하지도 않는다 |
| B-NFR-3 (안전) | 서버측 승인 게이트가 이 경로에도 그대로 적용된다 |
| B-NFR-4 (계약) | 노출 엔드포인트는 공개 계약이 된다. 파괴적 변경 시 버저닝이 필요하므로 도구 집합을 좁게 시작한다 |
| B-NFR-5 (호환) | MCP 서버 하나가 Claude Code와 Codex 양쪽에서 동작해야 한다 |

## B5. 데이터

- `api_tokens`: `user_id`, `token_hash`, `prefix`, `scope`, `label`, `expires_at`, `last_used_at`, timestamps
- 마이그레이션 번호는 **머지 직전에 확정**한다(`AGENTS.md` 함정 1)

## B6. 인터페이스(초안, `api.md`로 승격)

- `POST /api/v1/api-tokens` — 발급(평문 1회 반환)
- `GET /api/v1/api-tokens` — 목록(접두사만)
- `DELETE /api/v1/api-tokens/{id}` — 폐기

MCP 도구 목록은 설계 문서의 표를 정본으로 한다.

## B7. 단계

1. `apitoken` 도메인 + 필터 분기 → 2. `@qeploy/client` → 3. `@qeploy/mcp` 읽기 전용 → 4. 쓰기 도구 → 5. `@qeploy/cli` → 6. FE 발급 화면

**3단계가 최소 가치 지점이다.** 읽기 전용만으로도 에이전트가 배포 상태·로그·실패 원인을 읽어 사용자에게 설명할 수 있다.

## B8. 미결

- 노출 엔드포인트가 공개 계약이 되는 데 따른 버저닝 정책
- PAT 유출 시 영향 범위와 알림 정책
- Skill(Claude Code 전용) 추가 여부는 MCP 검증 후 판단
