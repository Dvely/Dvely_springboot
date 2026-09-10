# Qeploy MCP 서버 · CLI 설계 (에이전트 연동)

> 기준일: 2026-09-07 (2026-09-08 갱신) · 코드 실측 기반
> 구현 상태: 1~5단계 완료 · PR #306 · FE 6단계는 `Dvely_FE` #76
> 상위 PRD: `.notion/prd.md` 부록 A-2 · 요구사항: `.notion/srs.md` §B

---

## 목표

사용자의 코딩 에이전트(Claude Code · Codex)가 **Qeploy를 도구로 호출**해 배포·프리뷰·도메인·환경변수를 다루게 한다. 배포용 npm CLI도 같은 클라이언트 위에 함께 낸다.

## 왜 이 방향인가 — 관계의 역전

지금까지 검토한 모든 안은 "Qeploy가 AI를 부른다"였고, 그래서 전부 제공사 약관에 걸렸다. 이 설계는 방향이 반대다.

```
[기존] Qeploy 서버 → AI 호출 → 응답 소비
       = Qeploy 가 구독/키로 "구동되는 제3자 서비스". 구독은 금지, API 키는 BYOK 로만 허용.

[이 설계] 사용자의 Claude Code(사용자 구독) → Qeploy API 호출
       = Qeploy 는 에이전트가 쓰는 도구. Claude Code 가 GitHub·AWS API 를 부르는 것과 같은 관계.
```

**컴플라이언스 문제가 소멸한다.** Qeploy는 추론을 하지 않고, 구독 자격증명을 보지도 중계하지도 않는다. 서면 승인도 회색지대도 없다.

부수 효과로 **이 경로에서는 Qeploy의 AI 비용이 0이다.** 추론은 전부 사용자 쪽에서 일어나므로 BYOK조차 불필요하다.

### 이것은 대체가 아니라 추가다

이 경로가 닿는 대상은 **Claude Code를 이미 쓰는 개발자**다. PRD의 주 대상인 비개발자·입문 개발자는 여전히 웹 UI에서 시작하므로, 그쪽에는 BYOK(부록 A-1)가 그대로 필요하다. 두 경로는 사용자층이 다르다.

## 표면 선택

| 표면 | 동작 범위 | 판단 |
|---|---|---|---|
| **MCP 서버** | Claude Code + Codex 둘 다 | **축으로 채택** |
| **npm CLI** | 모든 에이전트 + CI + 사람 | **함께 채택** |
| Skill | Claude Code 전용 | MCP 검증 후 검토 |

Codex가 MCP를 지원하는 것은 실측으로 확인했다 — `codex --help`에 `mcp`(외부 MCP 서버 관리)와 `mcp-server`(Codex 자신을 MCP 서버로 기동)가 있다. 즉 **MCP 서버 하나로 두 CLI를 모두 커버**한다.

CLI를 함께 내는 이유는 셋이다. MCP를 지원하지 않는 환경을 커버하고, CI에서 쓸 수 있고, 사람이 직접 쓸 수 있다. 둘 다 같은 REST API를 감싸는 얇은 클라이언트이므로 **공용 클라이언트 하나를 만들면 CLI는 거의 공짜로 나온다.**

## 패키지 구조

```
qeploy-agent-tools/            (별도 저장소, MIT)
├─ packages/
│  ├─ client/    @qeploy/client      REST 클라이언트 + 타입 (공용)
│  ├─ mcp/       @qeploy/mcp         MCP 서버 (stdio)
│  └─ cli/       @qeploy/cli         npm CLI
```

사용:

```bash
# Claude Code
claude mcp add qeploy -- npx -y @qeploy/mcp
# Codex
codex mcp add qeploy -- npx -y @qeploy/mcp
# CLI / CI
npx @qeploy/cli deploy --project 12
```

## 노출할 도구

기존 105개 엔드포인트 중 **에이전트가 의미 있게 쓸 것만** 고른다. 채팅·승인·프로젝트 삭제처럼 사람이 판단해야 하는 것은 제외한다.

| 도구 | 매핑 | 비고 |
|---|---|---|
| `qeploy_list_projects` | `GET /api/v1/projects` | |
| `qeploy_get_project` | `GET /api/v1/projects/{id}/overview` | 상태 한눈에 |
| `qeploy_deploy` | `POST /api/v1/projects/{id}/deployments` | **쓰기 — 확인 필요** |
| `qeploy_deployment_status` | `GET /api/v1/deployments/{id}` | |
| `qeploy_deployment_logs` | `GET /api/v1/deployments/{id}/logs` | 실패 원인 파악 |
| `qeploy_deployment_failure_analysis` | `GET /api/v1/deployments/{id}/failure-analysis` | 이미 있는 분석 결과 조회 |
| `qeploy_retry_deployment` | `POST /api/v1/deployments/{id}/retry` | **쓰기** |
| `qeploy_list_deployments` | `GET /api/v1/projects/{id}/deployments` | |
| `qeploy_preview_session` | `GET`/`POST /api/v1/projects/{id}/preview-session` | |
| `qeploy_preview_logs` | `GET /api/v1/preview-sessions/{id}/logs` | |
| `qeploy_list_env` | `GET /api/v1/projects/{id}/environment-variables` | secret 은 값 없이 |
| `qeploy_set_env` | `POST`/`PATCH` 환경변수 | **쓰기** |
| `qeploy_list_domains` | `GET /api/v1/projects/{id}/domains` | |
| `qeploy_bind_domain` | `POST /api/v1/projects/{id}/domains` | **쓰기** |
| `qeploy_domain_verification` | `GET /api/v1/domains/{id}/verification-guide` | DNS 안내 |
| `qeploy_list_servers` | `GET /api/v1/projects/{id}/servers` | |
| `qeploy_server_logs` | `GET /api/v1/servers/{id}/logs` | |

**제외**: 프로젝트/서버 삭제, 저장소 연결 해제, 승인 처리, 비용예산 변경. 되돌리기 어렵거나 과금이 걸린 것은 웹 UI에서 사람이 한다.

### 쓰기 도구 안전장치 — 구현본

방어가 세 층이다. 하나가 뚫려도 나머지가 남게 했다.

1. **기본 비활성.** `QEPLOY_ENABLE_WRITES=true` 없이는 쓰기 도구가 목록에 아예 없다. 과하게 적극적인 에이전트의 실패는 틀린 답이 아니라 진짜 배포라, 기본값은 시작할 수 없는 쪽이어야 한다.
2. **서버 강제.** `READ` 스코프 PAT 는 변경 메서드에서 403 이다. 클라이언트를 우회해도, 플래그를 켜도 막힌다. 실측으로 확인했다.
3. **승인 게이트.** 배포·인프라 변경의 기존 승인 흐름은 이 경로에서도 그대로다. 그래서 `qeploy_deploy` 응답이 200 이어도 "배포됨"이 아니라 "승인 대기"일 수 있고, 도구 설명이 그 점을 명시해 에이전트가 단정하지 않게 한다.

여기에 MCP 표준 `destructiveHint` 를 붙여 클라이언트가 실행 전에 사용자에게 물을 수 있게 했다. 우리 산문을 에이전트가 읽어주길 바라는 대신 프로토콜 자체의 수단을 쓴다.

되돌리기 어려운 조작은 플래그와 무관하게 노출하지 않는다 — 프로젝트·서버 삭제, 저장소 연결 해제, 승인 결정, 비용예산 변경. 테스트가 그 이름들의 부재를 못박는다.

### (원안) 쓰기 도구 안전장치

- MCP 도구 설명에 되돌릴 수 없음을 명시해 에이전트가 확인을 구하게 한다
- `--yes` 없이는 CLI가 확인을 요구한다
- 서버측 승인 게이트(`ApprovalType`)는 그대로 살아 있다 — 배포·인프라 변경은 기존대로 승인을 거친다

### CLI 안전장치 — 구현본

- **비대화형에서는 묻지 않고 거절한다.** CI 로그의 프롬프트는 답할 사람이 없어 러너 타임아웃까지
  매달린다. 빠르고 읽히는 실패가 낫다.
- **stdin 이 닫혀도 매달리지 않는다.** 종료된 스트림에서 `readline.question()` 은 resolve 도
  reject 도 하지 않는다(실측). close 이벤트를 함께 기다려 "아니오" 로 떨어뜨린다.
- **확인 프롬프트가 프로젝트 이름을 보여준다.** "프로젝트 12 를 배포할까요?" 는 대조할 것이 없다.
  이름이 있어야 잘못된 대상을 알아챌 수 있다. 이름은 목록 응답에만 있다 — 개요 응답에는 없다.
- **`secret` 전환은 되돌릴 수 없어 따로 묻는다.** 서버가 true→false 를 400 으로 막는다.
- **종료 코드로 인증 실패를 분리한다.** 토큰이 만료된 파이프라인과 빌드가 깨진 파이프라인은
  대응이 다르다. 0 성공 · 1 실패 · 2 사용법 · 3 인증.
- **토큰을 옵션으로 받지 않는다.** 명령행 인자는 셸 히스토리와 `ps` 에 남는다.

노출 범위는 MCP 보다 좁다. `bind_domain` 은 CLI 에 넣지 않았다 — 비동기이고 중간에 사용자가 DNS
레코드를 추가해야 끝나므로, 한 번 실행하고 끝나는 명령의 모양이 아니다. 도메인은 웹 UI 와 MCP 에서
안내를 따라가며 한다.

## 선행 작업 — 에이전트용 토큰 (유일한 실질 과제)

**지금 인증이 CLI에 맞지 않는다.** 실측: GitHub OAuth로 브라우저 로그인 후 JWT를 받는 구조이고, 액세스 토큰 수명이 `JWT_EXPIRATION_MS` 기본 **1시간**이다. 헤드리스 CLI·MCP 서버가 쓸 장수명 자격이 없다.

**해법: 개인 액세스 토큰(PAT).** 웹 UI에서 발급하고 사용자가 붙여넣는다.

- 새 도메인 `apitoken`. 저장 방식은 방금 만든 `aiaccount`와 동일하다 — 해시 저장, 발급 시 1회만 평문 노출, 목록은 접두사만
- **원문을 저장하지 않는다.** 크리덴셜과 달리 우리가 검증만 하면 되므로 해시로 충분하다(BYOK 키는 벤더에 전달해야 해서 암호화 저장이었다)
- `JwtAuthenticationFilter`가 `Bearer qp_...` 접두사를 보고 PAT 경로로 분기. 기존 JWT 경로는 그대로
- 스코프는 초기에 `read` / `write` 둘만. 만료는 사용자가 선택(90일 기본), 웹에서 폐기 가능
- 마이그레이션 1개(`api_tokens`) — **번호는 머지 직전에 확정한다**(AGENTS.md 참고)

## 단계

| 단계 | 내용 | 규모 | 상태 |
|---|---|---|---|
| 1 | `apitoken` 도메인 + PAT 발급/폐기 API + 필터 분기 | 중 | **완료**(Issue #304, V60) |
| 2 | `@qeploy/client` — REST 클라이언트·타입 | 소 | **완료** |
| 3 | `@qeploy/mcp` — 읽기 도구만 먼저 | 중 | **완료**(도구 11개, 실 stdio 검증) |
| 4 | 쓰기 도구(`deploy`·`set_env`·`bind_domain`) + 확인 규약 | 소 | **완료**(도구 6개, 기본 비활성, destructiveHint) |
| 5 | `@qeploy/cli` | 소 | **완료**(명령 13개, 실 서버 검증) |
| 5b | `qeploy login` — PAT 발급을 FE 없이 | 소 | **완료**(붙여넣기 방식, 브라우저 왕복은 #327) |
| 6 | 웹 UI에 PAT 발급 화면 + 연동 안내 | 소 (FE) | `Dvely_FE` #76 |

**3단계까지가 최소 가치 지점이다.** 읽기 전용 MCP만 있어도 에이전트가 배포 상태·로그·실패 원인을 직접 읽고 사용자에게 설명할 수 있다. 그 지점에서 실제 유용한지 판단하고 쓰기로 넘어간다.

## 발급 창구 — FE 없이 뚫었다

설계 6단계(웹 UI 발급 화면)는 FE 작업이라 이쪽에서 처리할 수 없다. 그런데 **발급 창구가 없으면 MCP·CLI 를 아무도 쓸 수 없다** — 도구를 다 만들어 놓고 열쇠만 없는 상태다.

`qeploy login` 이 그 창구를 CLI 안에 만든다. 브라우저 JWT 를 한 번 받아 PAT 로 바꾸고 `~/.config/qeploy/config.json`(0600)에 저장한다.

- **CLI 와 MCP 가 같은 파일을 읽는다.** 에이전트는 `npx @qeploy/mcp` 로 실행되어 환경변수를 받을 자리가 없고, 에이전트 설정 파일에 자격증명을 적게 하면 비밀이 있을 필요 없는 곳에 하나 더 생긴다.
- **환경변수가 파일을 이긴다.** CI 가 `QEPLOY_TOKEN` 을 설정했는데 개발자의 로그인 파일이 조용히 덮어쓰면 안 된다.
- **붙여넣은 것은 화면에 표시하지 않고, 발급한 PAT 도 출력하지 않는다.** 이미 저장했으므로 스크롤백에 90일짜리 자격증명을 남길 이유가 없다.
- **`logout` 은 로컬 파일만 지우고 그 사실을 말한다.** 서버 폐기에는 WRITE 스코프가 필요한데 기본이 READ 다. 죽은 줄 아는 자격증명이 살아 있는 것은 로그아웃이 없는 것보다 나쁘다.
- **`qp_` 로 시작하는 값을 붙여넣으면 거절한다.** 이미 발급된 PAT 를 여기 넣으면 만료될 때까지는 "되는 것처럼" 보이다가 이유를 알 수 없게 실패한다.

### 왜 브라우저 왕복이 아닌가

실측: `redirect_uri` 를 authorize 에도 토큰 교환에도 **보내지 않는다.** GitHub 은 등록된 콜백 한 곳으로만 돌아오므로 루프백을 쓰려면 CLI 전용 OAuth 앱을 새로 등록해야 한다. 기존 웹 콜백에 얹는 방법은 FE 가 `state` 를 검증해서(`savedState !== state`) 브라우저에 남은 이전 state 하나로 튕긴다. 사용자의 브라우저 상태에 따라 되기도 하는 로그인은 만들지 않았다. #327 로 분리했다.

## 리스크

- **API 계약이 외부에 고정된다.** 지금까지는 FE만 쓰던 엔드포인트가 공개 계약이 되므로, 파괴적 변경에 버저닝이 필요해진다. 노출 도구를 좁게 시작하는 이유다.
- **PAT는 새 자격증명이다.** 유출 시 그 사용자의 프로젝트를 조작할 수 있다. 스코프·만료·폐기를 1단계에 함께 넣는다.
- 에이전트가 쓰기 도구를 남발할 위험은 승인 게이트와 도구 설명으로 낮추되, 완전히 막지는 못한다.

## 비목표

- Qeploy가 사용자 구독으로 추론하지 않는다(부록 A-1 비목표와 동일)
- MCP 서버는 사용자 AI 자격증명을 다루지 않는다 — Qeploy PAT만 안다
