# Qeploy 에이전트 도구

사용자의 코딩 에이전트(Claude Code · Codex)가 Qeploy 를 **도구로 호출**하기 위한 패키지들이다.

방향이 중요하다. Qeploy 가 AI 를 부르는 게 아니라 **사용자의 AI 가 Qeploy 를 부른다.** 그래서
추론은 전부 사용자 쪽에서 일어나고, Qeploy 는 AI 자격증명을 보지도 중계하지도 않는다. 설계 배경은
`../docs/qeploy-mcp-cli-design.md`.

| 패키지 | 내용 |
|---|---|
| `packages/client` | Qeploy REST 클라이언트(공용) |
| `packages/mcp` | MCP 서버(stdio). Claude Code · Codex 양쪽에서 동작 |

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

## 개발

```bash
cd agent-tools && npm install && npm test
```
