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

## 개발

```bash
cd agent-tools && npm install && npm test
```
