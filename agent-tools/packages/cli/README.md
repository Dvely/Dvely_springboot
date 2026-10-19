# @qeploy/cli

[Qeploy](https://qeploy.com) 커맨드라인. MCP 를 못 쓰는 에이전트·CI·사람용.

```bash
npx @qeploy/cli login
npx @qeploy/cli projects
npx @qeploy/cli status <deploymentId>
npx @qeploy/cli deploy <projectId> --yes
```

토큰은 옵션으로 받지 않는다(셸 히스토리·`ps` 에 남는다). 환경변수 `QEPLOY_TOKEN` 이나 `login` 이 저장한
파일만 읽는다. 종료 코드: 0 성공 · 1 실패 · 2 사용법 오류 · 3 인증 실패.

자세한 내용: https://github.com/Dvely/Dvely_springboot/tree/main/agent-tools
