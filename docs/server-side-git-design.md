# 서버 측 git 작업 설계 (#523)

> 상태: 설계 · 작성 2026-10-27 · 기준 커밋 `e48d7d1`(운영 3차 릴리스)
> 결정된 것(2026-10-27 확정):
> - 컨테이너 안 커밋 이력은 보존하지 않는다. 한 번 반영할 때 스냅샷 커밋 1개로 합친다.
> - 템플릿·Qeploy 생성 `.github` 파일은 서버 측 콘텐츠 해시 허용목록으로 처리한다.
> - JGit `org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r` 을 쓴다(EDL 1.0, 버전 고정).

## 1. 목표

프리뷰·작업 컨테이너 안에서 하던 git 작업(clone·fetch·commit·push·diff)을 **컨트롤 플레인(서버)** 으로 옮긴다.

- 컨테이너는 **작업 트리만** 가진다. 서버가 그 작업 트리를 스냅샷으로 받아 커밋하고 push 한다.
- clone·fetch 도 서버가 받아 컨테이너에 파일로 넣는다.
- 인증이 필요한 git 호출이 컨테이너 안에서 사라진다. GitHub 자격은 서버 밖으로 나가지 않는다.
- 변경 경로 판정(#508)·변경 내역(diff)을 서버가 만든다. 판정 근거가 서버가 만든 트리다.

## 2. 현재 구조

| 단계 | 어디서 | 코드 |
|---|---|---|
| 저장소 clone / 기존 작업 트리 fetch | 프리뷰 컨테이너의 git | `PreviewWorkspaceService.syncRepository` (clone·fetch·`fetch origin preview`·`checkout -B preview`) |
| CODE 직후 변경 내역 | 프리뷰 컨테이너의 git | `ChangeService.captureDiff` (`git add -N` + `git diff`, 저장소 없으면 별도 GIT_DIR) |
| preview 브랜치 커밋·push | 프리뷰 컨테이너의 git | `PreviewBranchPushService.push` ← `ResultApprovalGate`, `DeployAgentService`(3곳), `RepositoryBindingService` |
| 배포용 소스 clone | 빌드 컨테이너의 git | `BackendSourceClone` (`clone --depth 1`), `FrontendStaticHostingAdapter`(tag fetch·checkout) |
| preview→main 반영 | 서버 → GitHub API | `ResultApprovalService.reflect`, `DeploymentCommandService.prepareRelease` (PR 생성·merge) |

인증 git 호출은 모두 `ContainerGitCredentials` 를 지난다(#522 하드닝 적용).

## 3. 목표 구조

```
            ┌────────────────────── 컨트롤 플레인 ───────────────────────┐
 GitHub ◀──▶│ ServerGitWorkspace (JGit, 임시 저장소, 작업 단위로 생성·삭제)   │
            │   ├ fetchBase(repo, branch)      : preview 끝 커밋을 얕게 받음 │
            │   ├ snapshotTree(container)      : 컨테이너 작업 트리 → 트리   │
            │   ├ changedPaths(base, tree)     : 경로 판정·diff            │
            │   ├ commit(base, tree, message)  : 스냅샷 커밋 1개           │
            │   └ push(repo, commit, preview)  : fast-forward 만          │
            └───────────────▲──────────────────────────┬────────────────┘
                            │ docker archive (데몬 경유)  │ docker copy-in
                     ┌──────┴──────────────────────────▼──────┐
                     │ 프리뷰 컨테이너: /workspace/app (작업 트리만) │
                     └─────────────────────────────────────────┘
```

### 3.1 구성요소

**`ServerGitWorkspace`** (신규, `agent/infrastructure/git`)

- JGit(`org.eclipse.jgit`)으로 서버 임시 디렉터리에 bare 저장소를 만든다. 작업 하나가 끝나면 지운다(try-with-resources).
- 인증은 JGit `UsernamePasswordCredentialsProvider` 에 GitHub App User Token 을 넣는다. 토큰은 이 객체 밖으로 나가지 않는다.
- `fetchBase`: `preview` 브랜치(없으면 기본 브랜치)를 depth 1 로 받는다. 대상 커밋과 그 트리만 있으면 된다.

**스냅샷 → 트리** (`snapshotTree`)

- `DockerContainerService.copyArchiveFromContainerCmd` 로 `/workspace/app` tar 스트림을 받는다. 데몬이 만드는 tar 라 컨테이너 안 도구를 거치지 않는다.
- **tar 를 두 번 읽는다.**
  - 1패스는 헤더만 읽는다(경로·모드·크기·링크, `.gitignore` 본문). 그 결과로 무엇을 넣을지 정한다.
  - 2패스는 넣기로 정한 파일의 본문만 읽어 저장한다.
  - 그래서 ignore 로 빠질 빌드 산출물·캐시는 서버 저장소에 blob 으로 쌓이지 않는다.
  - 1패스에서 엔트리 수·바이트가 원시 상한(`raw-max-entries`·`raw-max-bytes`)을 넘으면 그 자리에서 멈춘다. 새 경로의 `.git`·`node_modules` 는 이 수에 넣지 않는다.
  - 스트림 하나에서 읽는 양에도 상한(`archive-max-bytes`)을 건다.
  - 두 패스 사이에 작업 트리가 바뀌면(크기 불일치·파일 사라짐) 실패한다.
- 하드링크는 tar 안에서 먼저 나온 정규 파일의 내용·모드를 쓴다. 해석되지 않으면, 추적 중인 경로는 실패하고(삭제로 바꾸지 않음) 새 경로는 뺀다.
- **파일시스템에 풀지 않는다.** tar 엔트리를 읽는 대로 JGit `ObjectInserter` 로 blob 을 쓰고, `DirCache` 에 경로·모드를 쌓아 트리를 만든다.
  - 그래서 서버 디스크에 심볼릭 링크나 `..` 경로가 생길 일이 없다.
- 포함 규칙은 `git add -A` 와 같은 의미로 맞춘다. **ignore 는 base 트리에 없는 경로(새 파일)에만 적용한다.**
  - base 에 있는 경로: 작업 트리에 있으면 갱신하고, 없으면 삭제로 본다. ignore 패턴에 걸려도 빼지 않는다. 커밋된 `dist/`·`build/`·`.env.example`, 나중에 규칙이 추가된 파일이 여기에 해당한다. `.gitignore` 에 한 줄을 더한다고 추적 중인 파일이 지워지지 않는다.
  - base 에 없는 경로: 모든 깊이의 `.git`, `node_modules` 는 항상 뺀다. 그다음 경로별 계층 `.gitignore` 를 JGit `IgnoreNode` 로 적용한다.
  - `.git` 은 base 에 있을 수 없으므로(git 이 추적하지 않는 이름) 언제나 빠진다. `node_modules` 는 base 에 추적 중인 것이 있으면 위 원칙대로 유지·갱신한다.
  - tar 를 두 번 읽지 않도록 `.gitignore` 엔트리를 먼저 모은다(9.1 참고).
- 모드:
  - 정규 파일은 실행 비트에 따라 `REGULAR_FILE` 또는 `EXECUTABLE_FILE` 이다.
  - 심볼릭 링크는 `SYMLINK`(링크 대상 문자열을 blob 으로) 이다.
  - 그 밖의 엔트리(장치 파일 등)는 건너뛴다.
- 상한: 파일 수와 총 바이트(설정값)를 넘으면 실패한다. 조용히 자르지 않는다. 상한은 `node_modules`·`.git` 을 뺀 뒤의 수로 센다.
  - 사용자 메시지: "변경 내용이 너무 커서 저장소에 반영하지 못했습니다(파일 N개 / 상한 M개, 또는 X MB / 상한 Y MB). 빌드 산출물이나 큰 파일이 .gitignore 에 들어 있는지 확인해 주세요." 태스크는 FAILED 로 끝나고 `/retry` 로 다시 시도할 수 있다.

**경로 판정·diff** (`changedPaths`, `diff`)

- base 트리와 스냅샷 트리를 `TreeWalk` 로 비교한다. 이름 변경 감지를 쓰지 않아 옛 경로와 새 경로가 모두 나온다.
- 보호 경로(`.github`, `.github/workflows/**`, `.github/actions/**`) 판정과 정규화는 #520 에서 만든 규칙을 서버 쪽으로 옮긴다.
- **보호 경로 허용목록**: Qeploy 가 만든 파일과 바이트까지 같은 변경은 승인 없이 통과시킨다. 한 글자라도 다르거나 목록에 없으면 승인을 받는다.
  - 허용목록은 서버 코드·리소스에만 둔다. "템플릿 기준과의 차이"처럼 기준을 컨테이너에서 가져오는 방식은 쓰지 않는다.
  - 대상: 템플릿 카탈로그가 넣는 `.github` 파일(서버 리소스에 콘텐츠 해시로 고정)과 `DeployWorkflowTemplate` 출력이다. 후자는 서버가 그 프로젝트 설정으로 다시 생성해 바이트 비교한다.
  - 참고: 배포 workflow 는 서버가 GitHub API 로 기본 브랜치에 직접 쓰므로(`ensureWorkflow`), preview 가 그 위에서 갈라졌다면 base 에 이미 있다. 내용이 바뀌지 않으면 변경 경로에 나타나지 않는다.
- diff 텍스트는 JGit `DiffFormatter` 로 만든다. `ChangeService` 가 지금 저장하는 형식(unified diff)과 같다.

**커밋·push** (`commit`, `push`)

- base 커밋을 부모로 스냅샷 트리 커밋 1개를 만든다.
  - 메시지는 지금과 같다(`feat: apply Qeploy Agent task <taskId>`).
  - 작성자는 `Qeploy Agent <agent@qeploy.com>` 이다.
  - 트리가 base 와 같으면 커밋하지 않는다(지금의 `diff --cached --quiet` 와 같은 동작).
- `refs/heads/preview` 로 fast-forward push 한다. 거부되면(그 사이 preview 가 움직임) base 를 다시 받아 한 번 재시도하고, 그래도 거부되면 실패한다.
- 같은 프로젝트의 push 는 프로젝트 단위 잠금으로 직렬화한다.

### 3.2 호출부 변경

| 호출부 | 지금 | 바뀐 뒤 |
|---|---|---|
| `ResultApprovalGate.pushPreviewBranch` | 컨테이너 git push | `ServerGitWorkspace` 로 스냅샷 커밋·push. **보호 경로가 있으면 push 를 미루고** 승인을 연다(3.3) |
| `DeployAgentService` (83·90·99) | 컨테이너 git push | 같은 서버 경로. 보호 경로가 있고 승인 이력이 없으면 실패하고 결과 승인을 안내한다 |
| `RepositoryBindingService` (132) | 컨테이너 git init·push(새 저장소) | 같은 서버 경로. base 는 방금 만든 저장소의 기본 브랜치다. `.gitignore` 기본값은 서버가 스냅샷에 더한다 |
| `ChangeService.captureDiff` | 컨테이너 git diff | 서버 diff(base=preview 끝, 없으면 기본 브랜치). 저장소 없는 신규 프로젝트는 빈 트리 기준이다 |
| `PreviewWorkspaceService.syncRepository` | 컨테이너 clone·fetch·checkout | 서버가 `preview`(없으면 기본 브랜치) 트리를 받아 `copyDirectoryToContainer` 로 넣는다. 기존 작업 트리가 있으면 비우고 넣는다 |
| `BackendSourceClone` | 빌드 컨테이너 clone | 서버가 기본 브랜치(또는 지정 ref) 트리를 받아 넣는다 |
| `FrontendStaticHostingAdapter` | 빌드 컨테이너 tag fetch·checkout | 서버가 그 tag 의 트리를 받아 넣는다 |
| `PreviewBranchPushService`, `ContainerGitCredentials` | — | 모든 호출부가 옮겨 가면 제거한다 |

### 3.3 결과 승인 흐름 변화

- 보호 경로 변경이 **없으면** 지금과 같다. push 다음에 RESULT 승인(정책이 켜진 경우)이 오고, merge 가 이어진다.
- 보호 경로 변경이 **있으면** 정책과 무관하게 RESULT 승인을 연다. 이때는 **push 하지 않는다.**
  - 승인 카드는 프리뷰(컨테이너)와 서버 diff 로 검토한다. 둘 다 GitHub 을 거치지 않는다.
  - 승인하면 `ResultApprovalService.reflect` 앞에서 같은 스냅샷을 서버가 커밋·push 한 뒤 merge 한다.
  - 그 사이 작업 트리가 바뀌지 않았는지는 승인 시점에 스냅샷 트리 해시로 확인한다. 다르면 다시 검토를 요구한다.
  - 거절하면 아무것도 push 하지 않는다.
- 미룬 스냅샷은 별도로 저장하지 않는다. 승인 대기 동안 컨테이너를 붙잡는 기존 장치(`holdForApproval`, #376)를 그대로 쓴다. 승인 시점에 다시 스냅샷을 뜨고 해시를 비교한다.

### 3.4 컨테이너에서 `.git` 이 사라지는 영향

- CODE 에이전트 프롬프트는 이미 "push 는 에이전트 뒤에서 일어난다"고 안내한다(`CodeAgentService` 81행). 에이전트가 스스로 `git init` 을 해도 스냅샷에서 `.git` 은 제외되므로 영향이 없다.
- 에이전트가 컨테이너에서 만든 커밋 이력은 반영되지 않는다. 스냅샷 1개로 합친다(결정됨).
- 컨테이너에 git 을 설치할 이유가 줄어든다. 빌드 도구가 git 을 요구하는 경우는 남으므로 설치 자체는 막지 않는다.

## 4. 단계 (PR 단위)

| PR | 내용 | 끝나면 |
|---|---|---|
| PR-1 | `ServerGitWorkspace`(fetchBase·snapshotTree·changedPaths·diff·commit·push) + 단위·통합 테스트. 호출부는 아직 그대로 | 서버 쪽 부품 완성 |
| PR-2 | push 이전: `ResultApprovalGate`·`DeployAgentService`·`RepositoryBindingService` + 보호 경로 미룸(3.3) + `ChangeService` diff 이전 | 컨테이너 push·diff 없음. #508 해소 |
| PR-3 | clone·fetch 이전: `PreviewWorkspaceService`·`BackendSourceClone`·`FrontendStaticHostingAdapter`. `PreviewBranchPushService`·`ContainerGitCredentials` 제거 | 컨테이너 인증 git 호출 0 |

- 단계마다 설정 플래그(`qeploy.git.server-side.push-enabled`, `...clone-enabled`)로 켠다. 기본값은 dev 에서 먼저 켜고 운영은 dev e2e 뒤에 켠다. 문제가 생기면 플래그만 끄면 옛 경로로 돌아간다. 옛 경로는 PR-3 에서 지운다.
- 마이그레이션은 없다.

### 4.1 PR-2 구현 메모

- push 진입점은 `PreviewBranchPushService.push` 한 곳이다. 플래그가 켜지면 `ServerPreviewBranch` 로 넘기고, 보호 경로 변경이 있으면 승인 없이는 올리지 않는다(`ProtectedChangeRequiresApprovalException`). `DeployAgentService`·`RepositoryBindingService` 는 이 진입점을 그대로 쓴다.
- 결과 승인 게이트는 서버가 만든 트리로 보호 경로를 본다. 있으면 정책과 무관하게 승인을 열고, `project_changes.push_deferred`·`snapshot_tree`(V68)에 남긴 뒤 push 하지 않는다. 승인하면 `ResultApprovalService.reflect` 가 지금 작업 트리가 그 트리와 같을 때만 올리고 merge 한다.
- `ChangeService` 의 변경 내역은 플래그가 켜지면 서버 diff 다. 같은 저장소로 가는 push 는 서버에서 하나씩 돈다(저장소 단위 잠금, 단일 인스턴스 기준).
- 저장소 연결 첫 push(`RepositoryBindingService`)는 결과 승인 대상이 아니다(미연결 프로젝트). 그 첫 반영에 보호 경로 변경이 있으면 반영을 멈추고 안내한다.

### 4.2 PR-3 구현 메모(clone·fetch, #523)

- 플래그 `qeploy.git.server-side.clone-enabled`. **push 플래그도 켜져 있을 때만** 동작한다 — 이 경로로 넣은 작업 트리에는 `.git` 이 없어 컨테이너 git 으로 push 하는 옛 경로와 같이 쓸 수 없다.
- `ServerGitCheckout` 이 대상 커밋을 얕게 받아(`fetchBase`·`fetchTag`) 트리를 tar 로 만들고, `DockerContainerService.copyArchiveToContainer` 가 `/workspace` 밑 `app` 으로 푼다. 실행 비트·심볼릭 링크는 그대로, 서브모듈 자리는 빈 디렉터리(clone 과 같음), 트리 안 `.git` 은 넣지 않는다. 파일 수·크기는 스냅샷과 같은 상한을 쓴다.
- 소유자: `copyUIDGID` 로 컨테이너 실행 사용자(프리뷰는 node)에게 맞추고, 상위 디렉터리를 모두 tar 항목으로 명시한다. 암묵적으로 만들어진 디렉터리는 root 소유가 될 수 있고, 프리뷰는 DAC_OVERRIDE 가 없어 그 아래에 쓰지 못한다.
- 프리뷰(`PreviewWorkspaceService`): preview(없으면 기본 브랜치). 같은 저장소의 작업 트리가 이미 있으면 **그대로 둔다** — 한 작업에서 CODE 스텝이 다시 돌면 같은 컨테이너를 다시 준비하는데, 그 트리에는 아직 push 하지 않은 앞 스텝의 작업이 있다(3.2 표의 "비우고 넣는다"에서 바꿨다). 어느 저장소인지는 `/workspace/.qeploy-source` 표시 파일로, 플래그 전에 clone 한 트리는 `origin` 으로 본다. 다른 저장소면 비우고 넣는다.
- 배포 빌드(`BackendSourceClone.cloneAt`): 기본 브랜치, 또는 지정 태그. 태그 체크아웃(`FrontendStaticHostingAdapter`)도 서버가 자격을 붙여 받으므로, 옛 경로가 자격 없이 `origin` 에서 태그를 받던 문제(6절 2번)가 이 경로에서는 없다.
- 4.3 의 base 표시와 맞물린다. 서버가 넣는 tar 에 `.qeploy-source`(저장소)와 `.qeploy-base`(넣은 커밋, 빈 저장소면 `EMPTY`)를 트리와 함께 담는다 — 같은 tar 라 트리와 base 가 어긋난 채 들어가지 않는다. 같은 저장소 트리를 그대로 두는 분기는 base 표시도 덮어쓰지 않는다(그 트리를 준비한 시점의 base 라야 push 직전 검사가 맞다). push 가 `RemoteMovedException` 으로 멈추면 `.qeploy-source` 가 지워져, 다음 준비는 비우고 다시 받아 base 를 새로 쓴다.
- 옛 경로(`ContainerGitCredentials`·컨테이너 clone·`PreviewBranchPushService` 의 컨테이너 push)는 운영에서 clone 플래그까지 켠 뒤 별도 PR 로 지운다.

### 4.3 준비 시점 base 검사(되돌림 방지)

- 스냅샷 커밋은 트리를 통째로 갈아끼운다. 작업 트리를 준비한 뒤 원격 preview 가 움직였는데(GitHub 에서 직접 수정, 다른 작업이 먼저 반영) 새 tip 위에 그 트리를 올리면, 그 사이 변경이 조용히 되돌려진다. 옛 컨테이너 push 는 이 경우 non-fast-forward 로 거부됐는데, 서버 경로의 재시도가 base 를 다시 받으면서 덮어쓰기가 됐다(PR-2 회귀).
- 이 변경은 반영 범위(`main...preview`)에도 남지 않는다 — 같은 구간에서 생겼다가 사라지므로. 그래서 막는다(fail-closed).
- 작업 트리를 준비할 때 base 를 컨테이너의 `/workspace/.qeploy-base` 에 남긴다(옛 clone 경로는 checkout 뒤 `rev-parse HEAD`, 빈 저장소는 `EMPTY`). 서버 push 가 받은 base 가 이것과 다르면 `RemoteMovedException` 으로 멈춘다. non-fast-forward 재시도도 같은 검사를 받는다.
- push 에 성공하면 표시를 방금 올린 커밋으로 바꾼다(같은 작업의 다음 push 가 자기 커밋 때문에 막히지 않게). 멈추면 `.qeploy-source` 를 지워 다음 준비가 최신 원격에서 다시 받게 한다.
- 멈춘 작업의 안내(#541): 전용 분기로 받아 WARN 한 줄만 남기고, 채팅에는 예외 메시지를 접두 없이 그대로 보인다(원인과 다음 행동이 이미 들어 있다).
- 재시도: 게이트 push 가 멈추면 CODE 스텝이 완료되지 않아 "이어서 다시 시도"는 CODE 스텝부터 다시 돈다. 작업 공간 준비가 최신 원격을 다시 받고 base 표시도 새로 쓰므로 대개 성공한다. 예외는 옛 컨테이너 clone 경로에서 에이전트가 고친 파일을 원격도 같이 고친 경우다 — `git checkout -B preview origin/preview` 가 충돌로 실패해 base 가 그대로 남고 같은 거부가 반복된다. 이때는 새 요청이 맞다. 서버 측 clone 경로는 멈출 때 `.qeploy-source` 를 지워 다음 준비가 트리를 비우고 다시 받으므로 이 경우가 없다(대신 앞 시도의 작업 트리는 버려진다).
- 표시가 없는 컨테이너(이 장치 이전에 준비됨)는 검사하지 않는다. 표시는 에이전트가 고칠 수 있는 위치지만, 이 장치는 동시 원격 변경을 막는 용도다(에이전트는 어차피 트리 전체를 바꿀 수 있다).

## 5. 테스트 전략

- **`snapshotTree`**: 손으로 만든 tar 와 base 트리로 검증한다.
  - 새 경로의 `.git`·`node_modules` 제외
  - 계층 `.gitignore`(부정 규칙 `!` 포함)는 새 경로에만 적용
  - base 에 있고 ignore 에 걸리는 파일이 커밋에서 사라지지 않음
  - `.gitignore` 한 줄 추가로 추적 중인 파일이 삭제되지 않음
  - base 에 있는 파일이 작업 트리에서 없어지면 삭제로 반영
  - base 에 추적 중인 `node_modules` 는 유지
  - 실행 비트, 심볼릭 링크(대상 문자열 보존, 디스크에 만들지 않음)
  - 장치 파일 건너뜀, 상한 초과 실패
  - 같은 입력에 같은 트리 해시
- **`changedPaths`**: #520 의 우회 표기 케이스를 그대로 옮긴다(이름 변경 양쪽, `.github` 자체 등). 허용목록은 바이트가 같으면 통과하고, 한 바이트라도 다르면 승인을 요구하는지 본다.
- **commit·push**: 로컬 bare 저장소를 원격으로 쓰는 통합 테스트.
  - 트리가 같으면 커밋하지 않음
  - preview 가 움직였을 때 1회 재시도
  - non-ff 거부 시 실패
- **결과 승인**:
  - 보호 경로가 있으면 push 하지 않고 승인을 연다
  - 승인 시 해시가 같으면 push → merge
  - 해시가 다르면 재검토
  - 거절 시 push 없음
- **e2e(dev)**: 새 프로젝트 저장소 연결, 기존 저장소 프리뷰, CODE→결과 승인→반영, CODE→DEPLOY(정책 OFF), 백엔드 배포 clone.

## 6. 운영 고려

- **디스크**: 작업 단위 임시 저장소는 depth 1 이다. 끝나면 지운다. 실패해도 `finally` 로 지운다. 서버 재시작 때 남은 임시 디렉터리를 치우는 정리 작업을 둔다.
- **메모리**: tar 를 스트리밍으로 읽어 blob 을 바로 쓴다. 파일 하나를 통째로 메모리에 올리지 않는다(상한 이하라도).
- **시간**: 지금 컨테이너 push 와 비슷하거나 빠를 것으로 본다(서버 네트워크). PR-1 통합 테스트와 dev e2e 에서 잰다.
- **GitHub 호출 수**: git 프로토콜(fetch 1·push 1)이라 REST 한도를 쓰지 않는다.

## 7. 규모

- 설계(이 문서) 1일.
- PR-1 1.5~2일, PR-2 2일, PR-3 1.5일.
- dev e2e 0.5일.

## 8. 결정 기록 (열린 질문이었던 것)

1. 템플릿 `.github` 파일 → **서버 측 콘텐츠 해시 허용목록**(3.1)으로 처리한다. 확정.
2. `FrontendStaticHostingAdapter` 의 tag fetch 는 자격 없이 `origin` 을 쓴다. 비공개 저장소에서 지금도 동작하는지 PR-3 에서 확인한다. 지금 실패하고 있다면 별도 결함으로 기록한다.
3. JGit(EDL 1.0) → `7.8.0.202609011348-r`(2026-09 최신 안정판)로 고정한다. 확정.

## 9. 부록

### 9.1 `.gitignore` 를 스트림에서 적용하는 순서

tar 는 한 번만 읽는다.

1. base 트리의 경로 집합을 먼저 읽는다(트리만, blob 내용은 읽지 않음).
2. 엔트리를 순서대로 읽는다. 새 경로의 `.git`·`node_modules` 는 내용을 읽지 않고 건너뛴다. 나머지 blob 은 바로 쓴다. 경로·모드·blob id 는 목록으로 둔다(내용은 이미 저장소에 있음).
3. 읽는 동안 `.gitignore` 엔트리는 내용도 따로 모은다.
4. 다 읽은 뒤 디렉터리별 `IgnoreNode` 를 만든다.
5. 목록을 거른다. base 에 있는 경로는 그대로 두고, 새 경로에만 ignore 를 적용한다. 결과를 `DirCache` 에 넣는다.

걸러진 blob 은 커밋에 들어가지 않으므로 push 되지 않는다. 임시 저장소와 함께 지워진다.
