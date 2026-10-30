# @qeploy/cli

> **⚠️ 서비스 일시 중단 (2026-10-30부터)** — 운영 서버 사정으로 Qeploy 서비스를 일시 중단했습니다. 재개할 때까지 로그인과 모든 도구 호출이 실패합니다. 0.1.4 부터는 그때 "대상을 찾을 수 없습니다" 대신 사이트가 내건 중단 안내([`status.json`](https://qeploy.com/status.json))를 그대로 보여줍니다. 재개 일정은 [qeploy.com](https://qeploy.com) 에서 알립니다.

[Qeploy](https://qeploy.com) 커맨드라인. MCP 를 못 쓰는 에이전트·CI·사람용.

```bash
npx @qeploy/cli login
npx @qeploy/cli projects
npx @qeploy/cli status <deploymentId>
npx @qeploy/cli deploy <projectId> --yes
```

토큰은 옵션으로 받지 않는다(셸 히스토리·`ps` 에 남는다). 환경변수 `QEPLOY_TOKEN` 이나 `login` 이 저장한
파일만 읽는다. 종료 코드: 0 성공 · 1 실패 · 2 사용법 오류 · 3 인증 실패(로그인 안 함 포함).

자세한 내용: https://github.com/Dvely/Dvely_springboot/tree/main/agent-tools
