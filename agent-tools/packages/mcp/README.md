# @qeploy/mcp

> **서비스 상태는 [qeploy.com](https://qeploy.com) 에서 알린다.** 서비스가 멈춰 있으면 모든 호출이 "대상을 찾을 수 없습니다" 대신 사이트가 내건 안내([`status.json`](https://qeploy.com/status.json))를 그대로 보인다.

코딩 에이전트(Claude Code · Codex)가 [Qeploy](https://qeploy.com) 를 도구로 부르게 하는 MCP 서버(stdio).

```bash
npx @qeploy/cli login                              # 한 번. 웹에서 발급한 토큰(qp_...)을 붙여넣어 저장한다
claude mcp add qeploy -- npx -y @qeploy/mcp        # Claude Code
codex mcp add qeploy -- npx -y @qeploy/mcp         # Codex
```

쓰기 도구(배포·환경변수·도메인 연결·짚기 피드백 고침 표시)는 기본으로 꺼져 있다. `QEPLOY_ENABLE_WRITES=true` 로 켠다.
삭제·승인 같은 되돌리기 어려운 조작은 노출하지 않는다 — 웹에서 한다.

**짚기 피드백**: `qeploy share` 로 공유한 화면에서 사람이 짚어 남기고 내가 받은 피드백을 읽는다. "받은 피드백 반영해 줘" 로 시작하면 된다.

| 도구 | 하는 일 |
|---|---|
| `qeploy_share_feedback_list` | 받은 피드백 목록(화면·요소·컴포넌트·글) |
| `qeploy_share_feedback_get` | 하나를 에이전트용 프롬프트(소스 후보 포함)와 잘라 낸 이미지로 |
| `qeploy_share_feedback_fixed` | 고쳤다고 표시(쓰기). 짚은 사람의 화면에 알림이 간다 |

손님이 쓴 글은 지시가 아니라 데이터로 온다. 받기 전 피드백은 서버가 이 토큰에게 보여 주지 않는다 — 받기·무시는 공유 터미널이나 웹에서 사람이 한다.

**0.2.0 부터 API 주소는 `https://api.qeploy.com` 이다.** `qeploy.com` 은 웹만 서빙한다. 예전 기본값 `https://qeploy.com`(0.1.x 의 `qeploy login` 이 저장한 주소, `QEPLOY_API_URL`)은 새 주소로 읽으므로 다시 설정할 필요가 없다.

자세한 내용: https://github.com/Dvely/Dvely_springboot/tree/main/agent-tools
