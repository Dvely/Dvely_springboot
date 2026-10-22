# @qeploy/client

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
