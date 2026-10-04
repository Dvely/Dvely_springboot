# GitHub App 권한 — 코드에서 도출한 필요 최소치

> **2026-09-30 작성 (#414).** `docs/aws-byoc-permissions.md` 는 사용자 AWS 계정에 필요한 IAM 을 문서로 관리하는데, **GitHub App 권한은 그런 문서가 없었다.** 권한 선언이 GitHub App 설정(대시보드)에만 있어서, 무엇을 요구하고 있는지 저장소에서 알 수 없었다. 그 비대칭을 없애기 위한 문서다.
>
> **여기 적힌 것은 두 가지다** — ①코드가 실제로 호출하는 것에서 도출한 필요 최소치, ②App 이 지금 실제로 요구·허용하고 있는 것. ②는 처음에 "대시보드에만 있다"고 적었는데 **틀렸다**: GitHub API 가 돌려준다(`GET /app`, `GET /app/installations`). `GithubAppPermissionProbeIntegrationTest` 가 그 둘을 받아 찍는다 — `./gradlew test --tests '*GithubAppPermissionProbe*' -Dgithubapp.it=true`.

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

## App 등록은 둘이다 — 그리고 설정은 동일하다 (실측, 2026-10-04)

**GitHub App 설정은 서버에 붙지 않는다. App 등록에 붙는다.** 그래서 "운영 서버만 조정" 같은 축이 아니고, **쓰이는 등록마다 각각** 조정해야 한다.

대시보드를 직접 읽어 확정했다.

| | dev | 운영 |
|---|---|---|
| 등록 | `dvely-test-app` (개인 소유, App id `3386167`) | `dvely-github-app` (**org `Dvely` 소유**) |
| 웹훅 URL | `http://54.251.165.129/api/v1/webhook/github` | `https://qeploy.com/api/v1/webhook/github` |
| Repository 권한 (8, metadata 는 mandatory) | `actions=write administration=write checks=write contents=write metadata=read pages=write pull_requests=write workflows=write` | **동일** |
| 이벤트 (4) | `check_run pull_request push workflow_run` | **동일** |
| Organization · Account · Enterprise 권한 | **0개** | **0개** |

**등록은 둘이지만 권한·이벤트 설정은 글자 하나까지 같다.** 두 사실이 동시에 참이다 — 그래서 "같게 처리했으니 한 번만 끄면 된다" 는 성립하지 않는다. **설정은 전파되지 않으므로 끄는 동작은 등록마다 따로** 해야 한다.

등록이 둘이라는 것은 웹훅 URL 이 증명한다. App 등록은 웹훅 URL 을 하나만 갖고, 코드는 저장소별 훅을 만들지 않는다(`repos/*/hooks` 호출 0건) — 그래서 한 등록이 두 환경에 배달할 수 없다.

> **앞서 여기 두 번 넘겨짚었다.** ①처음엔 "현재 요구 수준은 대시보드에만 있다"고 적었다(API 가 돌려준다). ②다음엔 "`GITHUB_APP_ID` 가 환경변수에서 오므로 등록도 갈린다"고 적었다 — 환경변수라는 사실은 값이 다르다는 뜻이 아니다. 결론은 맞았지만 근거가 틀렸다. 지금 표는 전부 직접 읽은 값이다.

### dev 웹훅이 `http://` 다

운영은 `https://` 인데 dev 는 평문이다. 서명(HMAC)이 있어 **위조는 막히지만** 페이로드가 평문으로 인터넷을 건너온다 — 저장소명·브랜치명·커밋 SHA·PR 내용이 노출된다.

**이것은 FE↔BE 구성과 무관하다.** 웹훅은 GitHub 서버에서 dev 박스로 직접 오므로, 프론트가 로컬이든 AWS 안이든 바뀌지 않는다.

고치려면 dev 에 **호스트명 + 인증서 + 443 리스너가 새로 필요하다** — 실측: `https://54.251.165.129` 는 응답이 없고(`000`), `dev.qeploy.com`·`api-dev.qeploy.com` 등 후보 호스트명은 어느 것도 DNS 에 없다. IP 로는 공개 CA 인증서를 받을 수 없다. dev 에 오는 것이 본인 테스트 저장소의 메타데이터뿐이라면 **그냥 두는 것도 합리적인 선택**이고, 바꾸기로 하면 그 비용이 선행 조건이다.

## 현재 요구 수준 — 실측 (두 등록 공통)

> 아래 값은 **두 등록에서 동일하다**(위 표). probe(`GET /app`, `dvely-test-app`)와 대시보드 직접 읽기(두 등록 모두)가 일치한다 — API 와 대시보드가 같은 값을 말한다는 교차 확인도 된 셈이다.
>
> 공개 App 페이지(`https://github.com/apps/<slug>`)로는 권한을 알 수 없다 — GitHub 은 설치 흐름에서만 보여준다.

```
permissions = actions=write, administration=write, checks=write, contents=write,
              metadata=read, pages=write, pull_requests=write, workflows=write
events      = [check_run, pull_request, push, workflow_run]
```

| 권한 | 현재 | 도출치 | 차이 |
|---|---|---|---|
| Contents | write | Read & write | 일치 |
| Metadata | read | Read | 일치 |
| Pull requests | write | Read & write | 일치 |
| Actions | write | Read & write | 일치 |
| Pages | write | Read & write | 일치 |
| Workflows | write | Read & write | 일치 |
| Administration | write | 폴백 경로에 달림 | **판단 필요** — 아래 |
| **Checks** | **write** | **없음** | ❌ **불필요** — `src/main` 전체에 Checks API 호출이 0건이다(`/check-runs`·`/check-suites`·`CheckRun` 어느 것도 없음) |

**이벤트 구독**

| 이벤트 | 코드가 다루나 |
|---|---|
| `push` · `pull_request` · `workflow_run` | ✅ `WebhookEventHandler:46,50,54` |
| **`check_run`** | ❌ **처리하지 않는다** — `WebhookEventHandler` 의 `switch` 에 해당 `case` 가 없어 조용히 버려진다 |

`checks` 권한과 `check_run` 구독은 **한 쌍**이다(그 이벤트를 받으려면 그 권한이 필요하다). 둘을 함께 떼는 것이 가장 안전한 첫 축소다 — 호출도 처리도 없으므로 **깨질 코드가 없다**.

### Administration 은 폴백이 실제로 쓰이는지에 달렸다

저장소 생성(`POST /user/repos`)·삭제는 **사용자 토큰**이 먼저 쓰이고, 그것이 실패할 때만 installation 토큰으로 떨어진다(아래 "토큰 선택 구조"). 즉 폴백이 실제로 타지 않는다면 `administration` 은 쓰이지 않는다. 떼기 전에 그 폴백 발생 빈도를 재야 한다 — `#413` 의 남은 항목이 이 폴백을 뒤집는 것이므로, 순서상 그쪽이 먼저다.

## 설치별 허용 범위 — 하나가 뒤처져 있다 (실측, `GET /app/installations`, **dev `dvely-test-app` 한정**)

설치 **5개**. 그중 **4개**는 위 요구 수준과 같다. **1개는 옛 범위에 머물러 있다**:

```
actions=write, checks=write, contents=write, metadata=read, pull_requests=write
→ administration · pages · workflows 가 없다
```

**이것이 "권한을 넓히면 기존 설치는 재승인까지 옛 범위를 유지한다"의 실물 증거다.** 그 설치에서는 installation 토큰으로 하는 **Pages 발행 · 저장소 생성/삭제 · `.github/workflows/` 파일 쓰기가 실패한다.** 사용자 토큰 경로가 살아 있으면 가려지지만, 폴백으로 떨어지는 순간 드러난다.

(설치 ID·계정명은 여기 적지 않는다 — 필요하면 위 probe 를 다시 돌린다.)

## ⚠️ 방향이 거꾸로 적혀 있었다 — 축소는 재승인이 필요 없다

처음에 이 문서(그리고 `#414`)는 "권한을 줄이면 이미 설치한 사용자가 재승인해야 한다"고 적었다. **반대다.**

| | 적용 시점 | 재승인 |
|---|---|---|
| 권한·웹훅 **제거** | **즉시** | **불필요** |
| 권한 **추가** | 설치별 승인 후 | **필요** — 승인 전까지 그 설치는 옛 범위를 유지한다 |

([GitHub Docs — Modifying a GitHub App registration](https://docs.github.com/en/apps/maintaining-github-apps/modifying-a-github-app-registration) · [Approving updated permissions](https://docs.github.com/en/apps/using-github-apps/approving-updated-permissions-for-a-github-app))

추가 쪽은 위 "설치별 허용 범위" 에서 **실제로 관측된다** — 5개 중 1개가 아직 옛 범위다.

**따라서 `checks` + `check_run` 제거는 사용자 영향이 0이다.** 공지도 재승인 UX 도 필요 없다. 축소를 미룰 이유로 재승인을 들 수 없다.

(`unhak/github-app-reauthorization` 브랜치는 **추가** 쪽 — 이미 넓힌 권한을 설치들이 승인하게 만드는 경로다. 뒤처진 설치 1개가 그 UX 를 아직 타지 않았다.)

## 순서

1. ~~코드에서 필요 권한 도출~~ → 이 문서
2. ~~현재 권한·이벤트 확보~~ → **API 로 받았다**(`GithubAppPermissionProbeIntegrationTest`). 대시보드가 필요하지 않았다
3. ~~차이 표 작성~~ → `checks` + `check_run` 이 불필요로 확정, `administration` 은 폴백 측정 대기
4. ~~운영 App 등록의 현황 측정~~ → **대시보드 직접 읽기로 확정**. 두 등록의 권한·이벤트가 동일하다
5. **`checks` + `check_run` 제거** ← 사용자 영향 0(재승인 불필요). **두 등록 각각** 해야 한다 — 설정은 전파되지 않는다
6. 운영 등록의 설치별 허용 범위 측정 ← 아직. dev 에서 1개가 뒤처져 있었으므로 운영도 봐야 한다
7. `administration` 은 `#413` 의 토큰 폴백 정리 뒤에 다시 본다
8. dev 웹훅 `http://` → `https://` 는 호스트명·인증서 비용을 받아들일지의 판단

## 이 문서를 다시 채우는 방법

```bash
./gradlew test --tests '*GithubAppPermissionProbe*' -Dgithubapp.it=true -i
```

기본 비활성이다(실제 App 비공개 키가 필요하고 외부 API 를 읽는다 — CI 에는 키가 없다).

**어느 App 을 읽는지는 활성 프로파일이 정한다.** 기본 프로파일이 `local` 이므로 그냥 돌리면 `application-local.yml` 의 등록(= `dvely-test-app`)을 본다. 운영·dev 수치가 필요하면 **그 환경의 설정으로** 돌려야 한다 — slug 가 출력에 찍히므로 무엇을 봤는지는 항상 확인할 수 있다. 읽기 전용 `GET` 두 번이며 App 설정을 바꾸지 않는다. 권한을 바꾼 뒤에는 이걸 돌려 위 표를 갱신한다 — **요구(`GET /app`)와 허용(`GET /app/installations`)을 둘 다 보는 것이 요점이다.** 하나만 보면 뒤처진 설치를 못 본다.
