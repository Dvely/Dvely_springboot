# Qeploy 에이전트 도구

사용자의 코딩 에이전트(Claude Code · Codex)가 Qeploy 를 **도구로 호출**하기 위한 패키지들이다.

방향이 중요하다. Qeploy 가 AI 를 부르는 게 아니라 **사용자의 AI 가 Qeploy 를 부른다.** 그래서
추론은 전부 사용자 쪽에서 일어나고, Qeploy 는 AI 자격증명을 보지도 중계하지도 않는다. 설계 배경은
`../docs/qeploy-mcp-cli-design.md`.

| 패키지 | 내용 |
|---|---|
| `packages/client` | Qeploy REST 클라이언트(공용) |
| `packages/mcp` | MCP 서버(stdio). Claude Code · Codex 양쪽에서 동작 |
| `packages/cli` | `qeploy` 커맨드라인. MCP 를 못 쓰는 환경·CI·사람용 |

## 지금은 이 저장소 안에 있다

API 와 함께 두고 반복하기 위해서다. 자립적인 디렉터리라 공개 저장소로 떼어내는 것은 나중에 언제든
가능하고, 그 시점은 오픈소스 공개를 결정할 때다.

## npm 에 있다

`@qeploy/client`·`@qeploy/mcp`·`@qeploy/cli` 를 2026-10-19 공개 배포했다(#451). 현재 버전은 각 `packages/*/package.json` 이 기준이다. 아래 `npx` 명령이 그대로 동작한다.

소스에서 바로 돌리려면(이 저장소를 고치는 중일 때):

```bash
cd agent-tools && npm install
claude mcp add qeploy -- node "$(pwd)/packages/mcp/src/index.js"
node packages/cli/src/bin.js login
```

**스코프 없는 npm `qeploy` 는 우리 것이 아니다**(남의 패키지, 2018). 어디에도 그 이름을 안내하지 않는다.

## 시작하기 — 로그인 한 번

```bash
npx @qeploy/cli login          # 웹에서 발급한 개인 액세스 토큰을 붙여넣어 저장
```

`~/.config/qeploy/config.json` 에 0600 으로 저장하고, **CLI 와 MCP 서버가 같은 파일을 읽는다.**
에이전트는 `npx @qeploy/mcp` 로 실행되어 환경변수를 받을 자리가 없고, 에이전트 설정 파일에
자격증명을 적어 넣으면 비밀이 있을 필요 없는 곳에 하나 더 생긴다.

```bash
# Claude Code
claude mcp add qeploy -- npx -y @qeploy/mcp
# Codex
codex mcp add qeploy -- npx -y @qeploy/mcp
```

환경변수를 쓰고 싶으면 그쪽이 이깁니다 — CI 가 `QEPLOY_TOKEN` 을 설정했는데 개발자의 로그인
파일이 조용히 덮어쓰면 안 되기 때문입니다.

```bash
export QEPLOY_API_URL=https://qeploy.com
export QEPLOY_TOKEN=qp_...
```

주소에는 `/api/v1` 을 붙이지 않습니다(붙이면 거절합니다 — 경로가 두 번 붙는다). 그리고 **https 가 아닌 주소는
거절합니다**(localhost·127.0.0.1 만 예외). 토큰은 요청의 전부라, 평문으로 나가도 아무것도 알려주지 않기 때문입니다.

**저장된 토큰은 로그인한 주소로만 나갑니다(#482).** `QEPLOY_API_URL` 은 클론한 저장소의 `.mcp.json`·`.envrc`
에서도 올 수 있어서, 그것만 바꿔 저장된 토큰을 다른 호스트로 보내게 할 수 없어야 합니다. 다른 주소를 쓰려면
거기서 `qeploy login --api-url <주소>` 를 다시 하거나 `QEPLOY_TOKEN` 을 직접 넣습니다 — 직접 넣은 토큰은 어디로든
보냅니다.

### 지금은 붙여넣기다

`login` 은 웹의 설정 → 개인 액세스 토큰에서 발급한 토큰(`qp_...`)을 한 번 붙여넣게 합니다. 입력은
화면에 표시되지 않고, 서버에 한 번 확인한 뒤 저장합니다. 스코프·만료는 발급할 때 정한 그대로라
`--scope`·`--expires-in-days` 를 같이 주면 거절합니다(조용히 무시하면 지정이 먹은 줄 안다, #471).

아직 발급하지 않았다면 브라우저 로그인 토큰(JWT)을 붙여넣어도 됩니다. 그때는 그걸로 `--scope` 의
PAT 를 새로 발급해 저장합니다. 토큰 옵션(`--token`)은 두지 않습니다 — 셸 히스토리와 `ps` 에 남습니다.

브라우저로 왕복하는 `gh auth login` 식 흐름은 GitHub OAuth 앱을 하나 더 등록해야 해서 별건으로
두었습니다(`Dvely_springboot` #327).

## 쓰기 도구는 기본으로 꺼져 있다

```bash
export QEPLOY_ENABLE_WRITES=true   # 배포·환경변수·도메인 연결 도구가 나타난다
```

과하게 적극적인 에이전트의 실패는 틀린 답이 아니라 **진짜 배포**라서, 기본값은 시작할 수 없는 쪽이다.
플래그를 켜도 두 층이 남는다. `READ` 스코프 토큰은 서버가 변경 요청을 403 으로 막고, 배포·인프라
변경의 승인 게이트는 이 경로에서도 그대로다.

되돌리기 어려운 조작(프로젝트·서버 삭제, 저장소 연결 해제, 승인, 비용예산)은 플래그와 무관하게
**노출하지 않는다.** 에이전트가 잘못 판단하면 되돌릴 수 없어서, 그건 사람이 웹에서 한다.

## CLI

MCP 를 지원하지 않는 에이전트, CI, 그리고 사람이 직접 쓰는 경로다. 같은 클라이언트를 감싸므로
인증·스코프·오류 문장이 MCP 와 동일하다.

```bash
export QEPLOY_API_URL=https://qeploy.com
export QEPLOY_TOKEN=qp_...

npx @qeploy/cli login
npx @qeploy/cli projects
npx @qeploy/cli status 340
npx @qeploy/cli deploy 12 --yes
npx @qeploy/cli env:set 12 API_URL=https://api.example.com --scope PRODUCTION --yes
```

**토큰은 옵션으로 받지 않는다.** 명령행 인자는 셸 히스토리와 `ps` 출력에 남는다. 환경변수와
로그인 파일만 읽는다.

`logout` 은 로컬 파일만 지운다. 서버의 토큰은 살아 있고, 그 사실을 출력에 적는다 — 죽은 줄 아는
자격증명이 살아 있는 것이 로그아웃이 없는 것보다 나쁘다.

### CI 에서

종료 코드가 이 CLI 의 실질적 인터페이스다. 인증 실패를 일반 실패와 나눈 것은, 토큰이 만료된
파이프라인과 빌드가 깨진 파이프라인이 서로 다른 대응을 필요로 하기 때문이다.

| 코드 | 뜻 |
|---|---|
| 0 | 성공 |
| 1 | API·실행 실패 |
| 2 | 사용법 오류 (인자·필수 플래그) |
| 3 | 인증 실패 (401·403) 또는 쓸 토큰 없음. 토큰 만료·스코프 부족·로그인 안 함 |

쓰기 명령은 확인을 요구하고, **비대화형에서는 물어보는 대신 즉시 거절한다.** CI 로그에 뜬 프롬프트는
답할 사람이 없어 러너 타임아웃까지 매달릴 뿐이다. `--yes` 를 붙인다.

`--json` 은 응답을 그대로 낸다. 사람용 표는 응답 모양이 바뀌면 원본 JSON 으로 물러난다 — 이름이
바뀐 필드를 대시로 채운 표는 변화를 감추기 때문이다.

## 개발

```bash
cd agent-tools && npm install && npm test
```

실 서버 검증(별도 기동 필요):

```bash
QEPLOY_E2E_URL=http://localhost:8099 QEPLOY_E2E_READ_TOKEN=qp_... node e2e.mjs      # MCP
QEPLOY_E2E_URL=http://localhost:8099 QEPLOY_E2E_READ_TOKEN=qp_... ./e2e-cli.sh      # CLI
```

## npm 배포

`@qeploy` 조직(npm)에 공개 패키지로 올린다. 무료다. **`client` 를 먼저** 올린다 — 나머지 둘이 그 버전을 의존한다.
버전을 올릴 때는 `mcp`·`cli` 의 `@qeploy/client` 의존 버전도 같이 올린다.

### 보통은 GitHub Actions 로

`package.json` 의 버전을 올려 develop(또는 main)에 머지한 뒤, Actions → **Publish agent-tools to npm** → Run workflow.
테스트를 돌리고, 레지스트리에 아직 없는 버전만 올린다. npm 토큰은 저장소에 없다 — npm Trusted Publishing(OIDC)이다.

이게 동작하려면 npmjs.com 의 **세 패키지 각각** Settings → Trusted Publisher 에 다음을 등록해 둬야 한다:
조직 `Dvely` · 저장소 `Dvely_springboot` · 워크플로 `agent-tools-publish.yml`.

### 손으로 올릴 때

```bash
cd agent-tools && npm test
for p in client mcp cli; do (cd packages/$p && npm publish) || break; done
```

**일반 터미널에서 실행한다.** 계정 2단계 인증이 보안 키(브라우저 승인) 방식이라 패키지마다 승인 링크를 열고 기다린다 — 입력을 기다릴 수 없는 환경(에이전트의 셸 등)에서는 `EOTP` 로 끝나고 아무것도 올라가지 않는다. npm 은 2단계 인증 없는 계정의 배포를 403 으로 막는다.

`publishConfig.access=public` 이 패키지에 들어 있어 `--access public` 은 필요 없다(스코프 패키지의 기본값은 비공개 시도다).
올라가는 것은 `src` 와 README 뿐이다(`files`).
