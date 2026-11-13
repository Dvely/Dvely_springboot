# @qeploy/mcp

> **서비스 상태는 [qeploy.com](https://qeploy.com) 에서 알린다.** 서비스가 멈춰 있으면 모든 호출이 "대상을 찾을 수 없습니다" 대신 사이트가 내건 안내([`status.json`](https://qeploy.com/status.json))를 그대로 보인다.

코딩 에이전트(Claude Code · Codex)가 [Qeploy](https://qeploy.com) 를 도구로 부르게 하는 MCP 서버(stdio).

```bash
npx @qeploy/cli login                              # 한 번. 웹에서 발급한 토큰(qp_...)을 붙여넣어 저장한다
claude mcp add qeploy -- npx -y @qeploy/mcp        # Claude Code
codex mcp add qeploy -- npx -y @qeploy/mcp         # Codex
```

쓰기 도구(배포·환경변수·도메인 연결)는 기본으로 꺼져 있다. `QEPLOY_ENABLE_WRITES=true` 로 켠다.
삭제·승인 같은 되돌리기 어려운 조작은 노출하지 않는다 — 웹에서 한다.

**0.2.0 부터 API 주소는 `https://api.qeploy.com` 이다.** `qeploy.com` 은 웹만 서빙한다. 예전 기본값 `https://qeploy.com`(0.1.x 의 `qeploy login` 이 저장한 주소, `QEPLOY_API_URL`)은 새 주소로 읽으므로 다시 설정할 필요가 없다.

자세한 내용: https://github.com/Dvely/Dvely_springboot/tree/main/agent-tools
