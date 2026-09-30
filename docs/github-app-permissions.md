# GitHub App 권한 — 코드에서 도출한 필요 최소치

> **2026-09-30 작성 (#414).** `docs/aws-byoc-permissions.md` 는 사용자 AWS 계정에 필요한 IAM 을 문서로 관리하는데, **GitHub App 권한은 그런 문서가 없었다.** 권한 선언이 GitHub App 설정(대시보드)에만 있어서, 무엇을 요구하고 있는지 저장소에서 알 수 없었다. 그 비대칭을 없애기 위한 문서다.
>
> **여기 적힌 것은 "코드가 실제로 호출하는 것에서 도출한 필요 최소치"다.** 지금 App 이 실제로 요구하는 권한은 **대시보드에서 확인해야** 하고, 그 칸은 비어 있다 — 채우는 것은 사람 손이 필요하다.

## 왜 줄여야 하는가

최소 권한은 **사고가 났을 때의 피해 크기를 정한다.** `#413` 이 정확히 그 사고의 한 형태였다 — 사용자 GitHub 토큰이 컨테이너에 평문으로 남았다(그건 고쳤다). 토큰이 새는 경로를 줄이는 것과, 새더라도 닿는 범위를 줄이는 것은 별개의 방어다.

## 도출 근거 — 코드가 실제로 호출하는 것

`grep` 으로 모은 엔드포인트와 HTTP 메서드다. 추측이 아니라 코드 리터럴이다.

| 클라이언트 | GET | 쓰기 |
|---|---|---|
| `GithubUserClient` | 1 | — |
| `GithubAppClient` | 2 | POST 2 · PUT 1 |
| `GithubProjectClient` | 8 | POST 2 · PUT 1 · DELETE 1 |
| `GithubRepoClient` | 13 | POST 2 · PUT 1 |
| `GithubPagesClient` | 6 | POST 3 · PUT 3 |
| `GithubActionsClient` | 9 | POST 1 · PUT 3 |
| `GithubPagesCustomDomainClient` | 1 | PUT 4 |

### 호출하는 엔드포인트

**사용자·설치**
```
GET  /user
GET  /user/installations
GET  /user/repos
GET  /installation/repositories
     /app/installations/…  /apps/…  /installations/new
```

**저장소**
```
GET    /repos/{owner}/{repo}
GET    /repos/{owner}/{repo}/branches/{branch}
GET    /repos/{owner}/{repo}/commits/{ref|sha}
GET    /repos/{owner}/{repo}/commits/{sha}/pulls
GET    /repos/{owner}/{repo}/compare/{base}...{head}
GET    /repos/{owner}/{repo}/contents/{path}
GET    /repos/{owner}/{repo}/tags
PUT    /repos/{owner}/{repo}/contents/.github/workflows/{file}
POST   /repos/{owner}/{repo}/git/refs
       /repos/{owner}/{repo}/git/refs/tags · /git/tags/{sha}
DELETE (저장소 삭제 — `GithubProjectClient`)
```

**Pull Request**
```
GET  /repos/{owner}/{repo}/pulls
POST /repos/{owner}/{repo}/pulls
PUT  /repos/{owner}/{repo}/pulls/{n}/merge
```

**Actions**
```
POST /repos/{owner}/{repo}/actions/workflows/{file}/dispatches
GET  /repos/{owner}/{repo}/actions/runs/{runId}      · /jobs
GET  /repos/{owner}/{repo}/actions/jobs/{jobId}/logs
```

**Pages**
```
GET/POST/PUT /repos/{owner}/{repo}/pages
```

**Webhook 수신** — `WebhookEventHandler` 가 다루는 이벤트: `push` · `pull_request` · `workflow_run`

## 코드에서 도출한 필요 권한

| 권한 | 필요 수준 | 근거 |
|---|---|---|
**Contents** | **Read & write** | `preview` 브랜치 push, `git/refs` 생성, 워크플로 파일 `PUT /contents/.github/workflows/…`, tag 생성 |
**Metadata** | Read | 필수 권한(GitHub 이 강제). `/repos/{owner}/{repo}`, 브랜치·커밋·compare 조회 |
**Pull requests** | **Read & write** | PR 생성과 merge(`PUT /pulls/{n}/merge`) — RESULT 승인의 preview→main 반영 |
**Actions** | **Read & write** | `dispatches`(write) + run·job·logs 조회(read) |
**Pages** | **Read & write** | 발행 설정과 커스텀 도메인(`PUT /pages`) |
**Workflows** | **Read & write** | `.github/workflows/` 아래 파일을 쓰려면 별도 권한이다 — Contents 만으로는 거부된다 |
**Administration** | **필요할 수 있다 — 아래 설명** | 저장소 생성(`POST /user/repos`)·삭제(`DELETE /repos/…`)를 한다. `GithubProjectClient.getRepositoryAccessToken` 이 **사용자 토큰을 먼저 쓰고, 그것이 실패하면 installation 토큰으로 폴백**한다. 폴백 경로가 실제로 쓰이면 App 에 저장소 관리 권한이 필요하다 |

### 필요해 보이지 않는 것

아래는 코드에 호출이 **없다**. 붙어 있으면 줄일 후보다.

`Issues` · `Projects` · `Secrets` · `Environments` · `Packages` · `Deployments`(GitHub Deployments API 미사용 — 우리 `deployment` 도메인은 자체 모델이다) · `Members`/`Organization` 전반 · `Security events` · `Webhooks`(App 레벨 웹훅을 쓰고 저장소별 훅을 만들지 않는다)

### 토큰 선택 구조 — 이것이 권한 산정을 어렵게 한다

`GithubProjectClient.getRepositoryAccessToken(ownerUserId)` (304행):

```
사용자 토큰이 있으면  →  사용자 토큰을 쓴다
  그것이 실패하면    →  App 이 설치돼 있을 때만 installation 토큰으로 폴백
사용자 토큰이 없으면  →  installation 토큰
```

**같은 동작이 두 자격 중 무엇으로도 나갈 수 있다.** 그래서 "App 권한만 줄이면 안전해진다" 가 아니다 — 사용자 토큰 경로가 살아 있는 한 그쪽 범위가 실제 상한이다. `#413` 이 다룬 것도 그 사용자 토큰이었다.

**두 이슈가 맞물리는 지점**: `#413` 의 남은 항목("installation 토큰으로 좁히기")은 이 폴백을 **뒤집는 것** — installation 을 기본으로, 사용자 토큰을 예외로. 그러면 App 권한 축소가 실제 효과를 갖는다. 순서가 그 반대면 축소해도 사용자 토큰 경로가 남는다.

## 대시보드에서 채울 칸 — 아직 비어 있다

**이건 사람 손이 필요하다.** GitHub App 설정 → Permissions & events 에서 아래를 받아 적으면 위 표와 대조할 수 있다.

| | 현재 요구 수준 | 위 도출치와 차이 |
|---|---|---|
| Contents | | |
| Metadata | | |
| Pull requests | | |
| Actions | | |
| Pages | | |
| Workflows | | |
| Administration | | |
| (그 외 붙어 있는 것) | | |

**Subscribe to events** 도 함께 — 코드가 다루는 것은 `push`·`pull_request`·`workflow_run` 셋이다. 그 외가 구독돼 있으면 불필요한 수신이다.

## ⚠️ 축소는 사용자에게 보이는 변경이다

**GitHub App 권한을 줄이면 이미 설치한 사용자가 재승인해야 한다.** 재승인 전까지 그 사용자의 저장소 작업이 실패할 수 있다.

이 저장소에 재승인 처리를 다룬 흔적이 있다(`unhak/github-app-reauthorization` 브랜치). 그 구현을 먼저 읽어 **재승인 UX 가 이미 있는지** 확인하면 축소 실행의 비용이 정해진다. 그 전에는 축소 시점을 정하지 않는다.

## 순서

1. ~~코드에서 필요 권한 도출~~ → 이 문서
2. **대시보드에서 현재 권한·이벤트 확보** ← 사람 손
3. 차이 표 작성 (불필요·과한 수준)
4. 재승인 UX 확인 후 축소 시점 결정 ← 사용자 판단
