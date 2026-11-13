# @qeploy/client

> **서비스 상태는 [qeploy.com](https://qeploy.com) 에서 알린다.** 서비스가 멈춰 있으면 모든 호출이 "대상을 찾을 수 없습니다" 대신 사이트가 내건 안내([`status.json`](https://qeploy.com/status.json))를 그대로 보인다.

[Qeploy](https://qeploy.com) REST 클라이언트. `@qeploy/mcp` 와 `@qeploy/cli` 가 함께 쓴다. ESM 전용, 의존성 없음, Node 20+.

```js
import { QeployClient, QeployApiError } from '@qeploy/client';

const client = new QeployClient({
  baseUrl: 'https://api.qeploy.com', // /api/v1 은 붙이지 않는다. https 만(localhost 제외)
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
- **0.2.0 부터 API 주소는 `https://api.qeploy.com` 이다.** `qeploy.com` 은 웹만 서빙한다. 예전 기본값 `https://qeploy.com`(0.1.x 의 `qeploy login` 이 저장한 주소, `QEPLOY_API_URL`)은 새 주소로 읽으므로 다시 설정할 필요가 없다.

자세한 내용: https://github.com/Dvely/Dvely_springboot/tree/main/agent-tools
