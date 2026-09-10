# Qeploy 에이전트 도구

사용자의 코딩 에이전트(Claude Code · Codex)가 Qeploy 를 **도구로 호출**하기 위한 패키지들이다.

방향이 중요하다. Qeploy 가 AI 를 부르는 게 아니라 **사용자의 AI 가 Qeploy 를 부른다.** 그래서
추론은 전부 사용자 쪽에서 일어나고, Qeploy 는 AI 자격증명을 보지도 중계하지도 않는다. 설계 배경은
`../docs/qeploy-mcp-cli-design.md`.

| 패키지 | 내용 |
|---|---|
| `packages/client` | Qeploy REST 클라이언트(공용) |
| `packages/mcp` | MCP 서버(stdio). Claude Code · Codex 양쪽에서 동작 |
| `packages/cli` | `qeploy` 커맨드라인. MCP 를 못 쓰는 환경·CI·사람용 |

## 지금은 이 저장소 안에 있다

API 와 함께 두고 반복하기 위해서다. 자립적인 디렉터리라 공개 저장소로 떼어내는 것은 나중에 언제든
가능하고, 그 시점은 오픈소스 공개를 결정할 때다.

## 사용

```bash
# 토큰 발급: 웹 UI 또는
#   POST /api/v1/api-tokens  {"scope":"READ","label":"내 노트북"}
export QEPLOY_API_URL=https://qeploy.com
export QEPLOY_TOKEN=qp_...

# Claude Code
claude mcp add qeploy -- npx -y @qeploy/mcp
# Codex
codex mcp add qeploy -- npx -y @qeploy/mcp
```

## 쓰기 도구는 기본으로 꺼져 있다

```bash
export QEPLOY_ENABLE_WRITES=true   # 배포·환경변수·도메인 연결 도구가 나타난다
```

과하게 적극적인 에이전트의 실패는 틀린 답이 아니라 **진짜 배포**라서, 기본값은 시작할 수 없는 쪽이다.
플래그를 켜도 두 층이 남는다. `READ` 스코프 토큰은 서버가 변경 요청을 403 으로 막고, 배포·인프라
변경의 승인 게이트는 이 경로에서도 그대로다.

되돌리기 어려운 조작(프로젝트·서버 삭제, 저장소 연결 해제, 승인, 비용예산)은 플래그와 무관하게
**노출하지 않는다.** 에이전트가 잘못 판단하면 되돌릴 수 없어서, 그건 사람이 웹에서 한다.

## CLI

MCP 를 지원하지 않는 에이전트, CI, 그리고 사람이 직접 쓰는 경로다. 같은 클라이언트를 감싸므로
인증·스코프·오류 문장이 MCP 와 동일하다.

```bash
export QEPLOY_API_URL=https://qeploy.com
export QEPLOY_TOKEN=qp_...

npx @qeploy/cli projects
npx @qeploy/cli status 340
npx @qeploy/cli deploy 12 --yes
npx @qeploy/cli env:set 12 API_URL=https://api.example.com --scope PRODUCTION --yes
```

**토큰은 옵션으로 받지 않는다.** 명령행 인자는 셸 히스토리와 `ps` 출력에 남는다. 환경변수만 읽는다.

### CI 에서

종료 코드가 이 CLI 의 실질적 인터페이스다. 인증 실패를 일반 실패와 나눈 것은, 토큰이 만료된
파이프라인과 빌드가 깨진 파이프라인이 서로 다른 대응을 필요로 하기 때문이다.

| 코드 | 뜻 |
|---|---|
| 0 | 성공 |
| 1 | API·실행 실패 |
| 2 | 사용법 오류 (인자·필수 플래그) |
| 3 | 인증 실패 (401·403). 토큰 만료 또는 스코프 부족 |

쓰기 명령은 확인을 요구하고, **비대화형에서는 물어보는 대신 즉시 거절한다.** CI 로그에 뜬 프롬프트는
답할 사람이 없어 러너 타임아웃까지 매달릴 뿐이다. `--yes` 를 붙인다.

`--json` 은 응답을 그대로 낸다. 사람용 표는 응답 모양이 바뀌면 원본 JSON 으로 물러난다 — 이름이
바뀐 필드를 대시로 채운 표는 변화를 감추기 때문이다.

## 개발

```bash
cd agent-tools && npm install && npm test
```

실 서버 검증(별도 기동 필요):

```bash
QEPLOY_E2E_URL=http://localhost:8099 QEPLOY_E2E_READ_TOKEN=qp_... node e2e.mjs      # MCP
QEPLOY_E2E_URL=http://localhost:8099 QEPLOY_E2E_READ_TOKEN=qp_... ./e2e-cli.sh      # CLI
```
