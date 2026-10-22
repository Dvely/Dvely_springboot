# @qeploy/mcp

코딩 에이전트(Claude Code · Codex)가 [Qeploy](https://qeploy.com) 를 도구로 부르게 하는 MCP 서버(stdio).

```bash
npx @qeploy/cli login                              # 한 번. 웹에서 발급한 토큰(qp_...)을 붙여넣어 저장한다
claude mcp add qeploy -- npx -y @qeploy/mcp        # Claude Code
codex mcp add qeploy -- npx -y @qeploy/mcp         # Codex
```

쓰기 도구(배포·환경변수·도메인 연결)는 기본으로 꺼져 있다. `QEPLOY_ENABLE_WRITES=true` 로 켠다.
삭제·승인 같은 되돌리기 어려운 조작은 노출하지 않는다 — 웹에서 한다.

자세한 내용: https://github.com/Dvely/Dvely_springboot/tree/main/agent-tools
