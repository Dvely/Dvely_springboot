# 이미지 빌드를 컨트롤 플레인 밖으로 (Issue #545)

> 상태: **설계** — 구현은 결정 후 별도 이슈. 대상은 EC2 DOCKER 배포의 앱·웹 이미지 빌드다.

## 목표

DOCKER 배포의 이미지 빌드(사용자 `Dockerfile` 의 `RUN` 단계 포함)를 컨트롤 플레인이 아니라 **사용자 AWS 계정의 자원**에서 돌린다.

- 사용자 코드는 사용자 계정에서 실행한다는 BYOC 원칙과 맞춘다. 앱 실행은 이미 그렇고, 빌드만 컨트롤 플레인에 남아 있다
- 컨트롤 플레인의 CPU·디스크·빌드 시간이 사용자 수와 빌드 크기에 비례해 늘지 않게 한다
- 사용자가 보는 결과(배포 성공·실패, 실패 원인, 비용)는 지금보다 나빠지지 않아야 한다

## 지금 흐름 (코드 기준, 2026-10-28)

1. `BackendDeployRunner.deploy` → `DockerImageBuildService`(앱) / `WebImageBuildService`(웹)
2. 소스 clone(격리 샌드박스 또는 서버 측 git, #523) → `Dockerfile` 확인, 없으면 `DefaultDockerfileFactory`·`WebAssetsFactory` 가 생성 → **빌드 컨텍스트 tar**
3. `ContainerImageBuilder` 가 컨트롤 플레인 호스트에서 빌드한다. 기본 kaniko 컨테이너(#507), BUILDX 는 명시할 때만
4. 전달: S3 모드는 `docker save` tar 를 `qeploy-artifacts-*` 에 올리고, ECR 모드(기본 꺼짐)는 push 한다
5. 대상 EC2 를 새로 띄우고 user-data 가 Docker 설치 → `docker load`(또는 pull) → `docker compose up`
6. `ProvisionedServerStatusWorker` 가 TCP 헬스체크로 마무리. `BOOT_TIMEOUT` 20분을 넘기면 terminate + FAILED

2 단계까지는 사용자 코드를 실행하지 않는다(파일 읽기와 Dockerfile 생성). 사용자 코드가 도는 것은 3 단계다. 배포할 때마다 인스턴스를 새로 띄우므로 재배포도 매번 1~6 을 다 탄다.

선례: NATIVE Node(P1)는 이미 소스 tar 만 보내고 **대상 EC2 부팅 때 `npm ci`** 한다(크로스 arch 때문).

## 후보

### A. 대상 EC2 에서 빌드

2 단계의 컨텍스트 tar 를 S3 에 올리고, user-data 가 받아서 그 인스턴스에서 `docker compose up --build`(앱·웹 컨텍스트 둘 다) 한다.

- **이미지 전달**: 없음. 컨텍스트 tar 만 기존 버킷으로 간다. 대상이 amd64 라 buildx·크로스빌드도 필요 없다
- **IAM**: 변경 없음. 인스턴스 역할의 `s3:GetObject`(`qeploy-artifacts-*/{projectId}/*`)로 충분하다
- **사용자 비용**: 추가 자원 없음. 빌드하는 동안의 인스턴스 시간만 늘어난다(어차피 떠 있을 인스턴스)
- **빌드 시간**: 대상 사양에 묶인다. 기본 t3.micro(2 vCPU 버스트, 1 GiB)는 Node 빌드는 대체로 되지만 Gradle·Maven(Spring Boot) 빌드는 메모리가 빠듯하다 → swap 을 user-data 에서 잡거나, JVM 스택은 t3.small 이상을 권하는 하한이 필요하다. 레이어 캐시가 없어 매 배포 전체 빌드다
- **실패 진단**: 빌드 출력을 `/var/log/qeploy-build.log` 로 남기고 BOOT 로그 소스(SSM)로 보인다. 타임아웃으로 terminate 하기 직전에는 부트 진단 캡처(#260)가 잡는다(SSM 에 닿을 때만). "빌드 중/빌드 실패"를 SSM 파라미터로 자가 보고하면(독립 DB 의 준비 보고와 같은 방식) 20분 타임아웃을 기다리지 않고 빌드 실패를 바로 FAILED 로 닫을 수 있다
- **실패 시점**: 인스턴스를 띄운 뒤에 드러난다. 실패하면 바로 terminate 하므로 과금은 빌드 시간만큼이다
- **구현량**: 작다. 컨텍스트 tar 는 이미 만들고 있고, user-data·compose 생성기와 상태 워커의 빌드 상태 처리, `BOOT_TIMEOUT` 재조정이 바뀐다

### B. 사용자 계정의 일회용 빌더 EC2

빌드 전용 인스턴스를 띄워 컨텍스트를 받아 빌드하고, 이미지를 S3(`docker save`) 또는 ECR 로 보낸 뒤 스스로 종료한다(`InstanceInitiatedShutdownBehavior=terminate`). 그다음은 지금과 같다.

- **이미지 전달**: 지금과 같다(S3 tar 또는 ECR). 빌더가 올린다
- **IAM**: 사용자 BYOC 정책은 그대로일 가능성이 높다 — `ec2:RunInstances`, `qeploy-instance-*` 역할 생성·`PassRole` 이 이미 있다. 빌더 역할(예: `qeploy-instance-build-*`)에 `s3:PutObject`(자기 프로젝트 접두사)와 `/qeploy/{projectId}/build/*` SSM 쓰기를 준다. 확인 필요: 지금 정책이 빌더용 인라인 정책과 종료 동작 지정을 막지 않는지
- **사용자 비용**: 빌드 시간만큼의 빌더 인스턴스(초 단위 과금, 최소 60초)와 EBS. 사양은 빌드에 맞게 고를 수 있다(스팟도 가능하나 중단 시 재시도가 필요)
- **빌드 시간**: 빌더 부팅·Docker 설치(1~2분)가 더해지는 대신 대상 사양과 무관하게 빠른 인스턴스로 빌드할 수 있다. 캐시는 A 와 마찬가지로 없다(ECR 캐시 이미지로 나중에 보완 가능)
- **실패 진단**: 빌더가 빌드 로그를 S3 에 올리고 컨트롤 플레인이 읽는다. 대상을 띄우기 **전에** 실패가 드러난다
- **운영 부담**: 빌드 상태 워커, 빌더가 스스로 못 죽었을 때의 태그 기반 회수 스윕(EIP 고아 청소와 같은 형태), 빌드 타임아웃
- **구현량**: 중간. 새 인스턴스 종류·역할·워커·스윕

### C. AWS CodeBuild (사용자 계정)

컨텍스트를 S3 에 올리고 CodeBuild 프로젝트(Docker 빌드라 privileged)로 빌드해 ECR push 또는 S3 산출물로 받는다.

- **IAM**: **사용자 BYOC 정책 변경이 필요하다** — `codebuild:CreateProject`·`StartBuild`·`BatchGetBuilds`·`DeleteProject`, CodeBuild 서비스 역할 생성과 `codebuild.amazonaws.com` 으로의 `PassRole`, 로그 조회용 `logs:GetLogEvents`. 기존 사용자는 정책을 다시 붙여야 한다
- **사용자 비용**: 빌드 분 단위 과금(컴퓨트 유형·리전별 단가와 무료 사용량은 착수 시 확인)
- **빌드 시간**: 관리형 환경 준비 대기 + 빌드. 캐시 옵션이 있다
- **실패 진단**: CloudWatch Logs 로 빌드 로그를 API 로 읽을 수 있다(가장 정돈돼 있다)
- **운영 부담**: 인스턴스 관리가 없다. 대신 새 서비스 의존, 리전 지원 확인
- **구현량**: 중간. 새 클라이언트, 정책 문서(`docs/aws-byoc-permissions.md`)와 연결 가이드의 추천 정책(#296) 갱신

### D. 현행 유지 (컨트롤 플레인 kaniko)

- 사용자 비용·IAM 변경 없음, 빌드 출력을 바로 보여 줄 수 있다
- 컨트롤 플레인이 사용자 `Dockerfile` 을 계속 실행하고, 동시 빌드가 컨트롤 플레인 자원을 쓴다. 목표를 만족하지 않는다

## 비교

| | A 대상 EC2 | B 일회용 빌더 | C CodeBuild | D 현행 |
|---|---|---|---|---|
| 컨트롤 플레인이 사용자 빌드를 실행 | 안 함 | 안 함 | 안 함 | 함 |
| 사용자 BYOC 정책 변경 | 없음 | 없음(확인 필요) | 필요 | 없음 |
| 사용자 추가 비용 | 없음 | 빌더 분 | 빌드 분 | 없음 |
| 대상 사양 의존 | 큼 | 없음 | 없음 | 없음 |
| 실패가 드러나는 시점 | 대상 기동 후 | 대상 기동 전 | 대상 기동 전 | 대상 기동 전 |
| 실패 진단 경로 | SSM BOOT 로그·부트 캡처 | S3 빌드 로그 | CloudWatch Logs | 즉시 |
| 구현량 | 작음 | 중간 | 중간 | — |

## 권장안

**A 를 먼저, B 를 다음으로.** C 는 B 의 운영 부담이 실제로 문제가 될 때 다시 본다.

- A 는 정책 변경도 새 자원도 없이 목표를 만족하는 가장 짧은 길이고, NATIVE Node 가 이미 같은 모델로 돈다
- A 의 약점은 작은 대상에서의 빌드(메모리·시간)다. B 는 그 약점만 정확히 메운다 — 대상이 작거나 JVM 스택일 때 빌더로 보내는 식으로 둘을 함께 둔다
- C 는 진단은 가장 좋지만 모든 기존 사용자에게 정책 재부착을 요구한다. 지금 단계에서 그 비용이 크다

## 단계별 전환

1. **A 구현(플래그)** — `qeploy.provisioning.ec2.image-build-location=CONTROL_PLANE|TARGET`, 기본 `CONTROL_PLANE`
   - 컨텍스트 tar(앱·웹) S3 업로드, user-data 의 `docker compose up --build`, 빌드 로그 파일
   - 빌드 상태 SSM 자가 보고 → 상태 워커가 빌드 실패를 바로 FAILED 로 닫고 원인(빌드 로그 꼬리)을 남김
   - `BOOT_TIMEOUT` 을 빌드 포함 기준으로 다시 잡거나, 빌드 완료 보고 뒤부터 기동 타임아웃을 센다
   - JVM 스택 + t3.micro 는 swap 을 잡거나 사양 하한을 안내(되묻기 C2 의 배포 형태 질문과 만나는 지점)
2. **실 EC2 e2e** — Node·Gradle·웹(+번들 DB) 각각, t3.micro·t3.small. 빌드 시간과 실패 진단 화면을 기록
3. **기본값 전환** — dev → 운영 순으로 `TARGET`. 한 릴리스 동안 `CONTROL_PLANE` 을 비상 폴백으로 남긴다
4. **B 구현** — 대상이 작거나 사용자가 고를 때 빌더로. 빌드 상태 워커·회수 스윕
5. **컨트롤 플레인 빌드 경로 제거** — `ContainerImageBuilder` 의 kaniko·buildx·native 경로와 ECR push(빌더가 맡지 않으면)를 걷어 낸다

## 범위 밖 · 따로 볼 것

- **NATIVE Java 의 jar 빌드**(`NativeBuildService`)도 컨트롤 플레인의 격리 샌드박스에서 Gradle·Maven 을 돌린다. 같은 원칙이면 대상 EC2 에서 JDK 로 빌드(A)하거나 빌더(B)로 옮길 수 있다. 이 문서의 1~3 단계가 끝난 뒤 같은 틀로 다룬다
- 이미지 캐시(ECR 캐시 이미지, BuildKit 캐시 내보내기)는 B·C 에서만 의미가 있다. 빌드 시간이 문제가 되면 그때 본다

## 결정이 필요한 것

- 권장안(A → B) 채택 여부
- A 의 JVM 스택 처리: swap 으로 버틸지, 사양 하한(t3.small)을 둘지
- 빌더(B)의 기본 사양과 스팟 사용 여부(비용 대 재시도)
- 전환 기간 동안 `CONTROL_PLANE` 폴백을 얼마나 남길지
