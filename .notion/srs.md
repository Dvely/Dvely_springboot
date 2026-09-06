# SRS: 외부 AI 코딩 에이전트 개인계정 연동 (BYOK)

> 기준일: 2026-09-05 · 상태: 확정(변형 A 범위) · 미착수
> 상위 PRD: `prd.md` 부록 A-1 · 상세 설계: `docs/byok-coding-agent-design.md`
> 구현 상태는 `state.md`, API 계약은 `api.md`가 이어받는다.

---

## 1. 범위
Qeploy 사용자가 자신의 공식 AI API 키를 등록하고, 그 키로 Claude Code / Codex CLI를 격리 컨테이너에서 실행해 코딩·배포 보조를 받는 기능. 1차 범위는 변형 A(서버측 실행)다.

## 2. 이해관계자·전제
- 각 사용자는 자신의 Anthropic / OpenAI **API 키**를 보유한다(구독이 아니라 키).
- Qeploy는 사용자 키를 대신 결제·재판매·중개하지 않는다.
- 실행 환경은 기존 EC2 + Docker 격리(U4 정책)를 재사용한다.

## 3. 기능 요구사항 (FR)

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

## 4. 비기능 요구사항 (NFR)

| ID | 요구사항 |
|---|---|
| NFR-1 (준수) | 공식 API 엔드포인트만 사용. 구독 자격증명 라우팅·세션 가로채기·비공식 엔드포인트·차단 우회 금지. |
| NFR-2 (보안) | 키는 at-rest AES/GCM 암호화(기존 `AesEncryptor` 재사용), 전송·로그 미노출, 엔티티 `@ToString` 금지. |
| NFR-3 (격리) | user_id 스코프 저장, per-user 실행 격리, 운영자 키 풀링 금지. |
| NFR-4 (네트워크) | 실행 컨테이너 egress를 공식 API 도메인으로 제한. |
| NFR-5 (가용성) | 타임아웃·재시도로 행(hung) provider로부터 파이프라인 보호. |
| NFR-6 (관측성) | 실행 시작·성공·실패·타임아웃을 감사 로그(기존 audit 도메인)로 남기되 키·프롬프트 원문은 마스킹. |

## 5. 데이터
- `ai_provider_credentials` (V43): `user_id`, `provider`, `encrypted_api_key`, `label`, timestamps. `UNIQUE(user_id, provider)`.

## 6. 인터페이스(초안, `api.md`로 승격)
- `POST /ai-credentials` — 키 등록(provider, apiKey, label). 응답 마스킹.
- `GET /ai-credentials` — 등록 목록(마스킹).
- `DELETE /ai-credentials/{provider}` — 삭제.
- 메시지 요청 provider에 `CLAUDE_CODE`/`CODEX` 허용(기존 `aiProvider` 파라미터 확장, append).

## 7. 추적성
- PRD 부록 A-1 ↔ FR-1~9 ↔ 설계 `docs/byok-coding-agent-design.md` §아키텍처~데이터 ↔ 구현 PR-1~5.

## 8. 미결·검증
- OpenAI BYOK 회색지대 → 보안 강화 + 필요 시 서면 확인.
- 변형 B(클라이언트측 키 보관 + 확장/WebSocket)는 현재 범위 밖(설계 문서 "향후" 참고).
