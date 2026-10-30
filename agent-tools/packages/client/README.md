# @qeploy/client

> **⚠️ 서비스 일시 중단 (2026-10-30부터)** — 운영 서버 사정으로 Qeploy 서비스를 일시 중단했습니다. 재개할 때까지 로그인과 모든 도구 호출이 실패합니다. 0.1.3 부터는 그때 "대상을 찾을 수 없습니다" 대신 사이트가 내건 중단 안내([`status.json`](https://qeploy.com/status.json))를 그대로 보여줍니다. 재개 일정은 [qeploy.com](https://qeploy.com) 에서 알립니다.

[Qeploy](https://qeploy.com) REST 클라이언트. `@qeploy/mcp` 와 `@qeploy/cli` 가 함께 쓴다. ESM 전용, 의존성 없음, Node 20+.

```js
import { QeployClient, QeployApiError } from '@qeploy/client';

const client = new QeployClient({
  baseUrl: 'https://qeploy.com', // /api/v1 은 붙이지 않는다. https 만(localhost 제외)
  token: process.env.QEPLOY_TOKEN, // 웹에서 발급한 개인 액세스 토큰 qp_...
});

try {
  console.log(await client.listProjects());
} catch (e) {
  if (e instanceof QeployApiError) console.error(e.toAgentMessage());
  else throw e;
}
```

- 응답 봉투 `{status, code, message, data}` 는 풀어서 `data` 만 돌려준다.
- 2xx 가 아니면 `QeployApiError`(`status`·`code`·`path`). ID 인자는 양의 정수만 받는다(아니면 `TypeError`).
- `qeploy login` 이 저장한 토큰을 쓰려면 `@qeploy/client/config` 의 `resolveApiUrl`·`resolveToken` 을 쓴다.

자세한 내용: https://github.com/Dvely/Dvely_springboot/tree/main/agent-tools
