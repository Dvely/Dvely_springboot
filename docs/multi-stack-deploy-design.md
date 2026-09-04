# 다스택 · 다모드 EC2 배포 설계

## 목표

지금 EC2 백엔드 배포는 **native × Java 한 셀**만 된다(`Gradle → jar → user-data 가 `java -jar``).
사용자 요구는 "**여러 스택(Java·Node·Next.js·…) × 두 배포 모드(native/Docker)**"를 다 받는 것이다.
추가로 **mono(한 EC2 풀스택) / split(프론트 GH Pages + 백 EC2)**, **DB(RDS / EC2 위 컨테이너)**도 열어야 한다.

## 배포 지형 (두 축 + 옵션)

| 스택 \ 모드 | native (컨테이너 없음) | Docker 이미지 |
|---|---|---|
| Java | ✅ 현재(jar→java-jar) | 목표 |
| Node | 목표(npm build+node) | 목표 |
| Next.js(원레포 풀스택) | 목표(next build+start) | 목표 |
| Python 등 | 스택마다 추가 | 목표(무엇이든) |

- **핵심 통찰**: native 는 스택마다 구현(N개), **Docker 는 한 경로가 모든 스택 커버**(Dockerfile만 있으면). "다 가능" 최단 경로 + "전부 컨테이너"·DB 컨테이너와 자연 연결. 그래서 **Docker 모드를 먼저** 세우고 native 는 특정 스택 최적화로 확장한다.
- +axis: mono vs split(프론트는 GH Pages, 현행 DEPLOY 유지). +DB: RDS(현행) / DOCKER(EC2 컨테이너, 현재 비활성).

## 현재 코드 기준점 (재사용/확장 지점)

- **산출물 빌드**: `BackendJarBuildService`(격리 컨테이너 clone+`./gradlew build`→jar). 여기서 **스택 감지 + jar 또는 docker 이미지 빌드**로 일반화.
- **실행 스크립트**: `BackendDeployRunner.userDataScript`(하드코딩 `nohup java -jar /opt/app/app.jar`). **실행 커맨드를 모드/스택별로 파라미터화**.
- **아티팩트 전달**: 현재 `S3ArtifactStore`(jar→S3, 인스턴스 IAM 역할로 pull). Docker 이미지도 **S3 로 `docker save`/`load`**(최소 IAM) → 나중에 ECR.
- **상태 컬럼**: `provisioned_servers` 에 배포모드/런타임 컬럼 없음 → **`deploy_mode`(NATIVE/DOCKER) + `runtime`(JAVA/NODE/NEXT/…) 컬럼 추가(V-migration)**.
- **골격 복제**: 승인 게이트(SERVER_PROVISION)·상태워커(TcpHealthChecker)·terminate 정리·EIP·SSM 비밀·IAM 인스턴스 역할은 그대로 재사용.
- **DB 컨테이너**: `ProvisionMethod.DOCKER`(현재 "곧 지원" 비활성) 활성화 = EC2 위 DB 컨테이너.

## 단계 (각 PR 리뷰 가능, CODE 모델 크레딧 없이 손으로 짠 앱으로 e2e 검증)

- **P1 — native-Node 추가**: 빌드 감지(package.json)→`npm ci && npm run build`, user-data 가 node 설치+`node`(또는 `npm start`) 실행. `runtime=NODE`. Java native 와 대칭. *(원하면 P2 뒤로 미뤄도 됨 — Docker가 Node도 커버하므로)*
- **P2 — Docker 모드 골격(스택 무관 핵심)** ✅ *완료(#221~#224)*: `deploy_mode=DOCKER`. 빌드 컨테이너에서 앱의 `Dockerfile`로 buildx(`--platform linux/amd64`)→이미지 `docker save`→S3. user-data 가 Docker 설치+S3에서 `docker load`+`docker run`(포트 매핑, SSM env 주입). **Node·Java·Next 다 동일 경로.** 실 EC2 e2e 로 Node 앱 배포 실증. *(docker-java legacy builder 는 크로스빌드 불가 → buildx 필수, e2e 로 발견.)*
  - **Dockerfile 폴백(자동생성)** ✅ *구현*: 앱에 Dockerfile 없으면 루트 마커로 스택 감지(`DefaultDockerfileFactory`) → 기본 Dockerfile 생성. Gradle·Maven(Spring Boot)·Node·Next 지원, 못 알아보면 명확히 실패. 포트는 `SERVER_PORT`(Spring)+`PORT`(Node 관례) 둘 다 주입. **Node·Gradle 폴백은 실제 최소 앱으로 buildx 빌드+실행 검증**(Maven·Next 는 동일 패턴, 런타임 미검증).
- **P3 — 이미지 전달 ECR 화(선택 고도화)** 🟡 *구현(기본 비활성)*: S3 save/load → ECR push/pull. `qeploy.provisioning.ec2.image-transfer=ECR` 로 켠다(기본 S3 — 추가 권한 불필요라 안전한 기본). 컨트롤 플레인이 `EcrImageRegistry` 로 저장소 멱등생성→docker login→buildx `--push`, EC2 는 인스턴스 역할로 `get-login-password`→pull. 인스턴스 역할에 ECR pull 권한 추가(useEcr 시), terminate 시 저장소 삭제.
  - **필요 IAM(사용자 BYOC 정책)**: push=`ecr:CreateRepository`·`GetAuthorizationToken`·`InitiateLayerUpload`·`UploadLayerPart`·`CompleteLayerUpload`·`PutImage`·`BatchCheckLayerAvailability`·`DeleteRepository`. 사용자 계정 정책 변경이라 켜기 전 합의 필요.
  - **검증**: buildx `--push`→pull→run 메커니즘을 로컬 레지스트리로 실검증(내 코드와 동일 명령), ECR 토큰 디코드·URI 구성 단위테스트. **실 ECR e2e 는 크레딧 확보 시**(get-login-password·repo create/delete 는 미검증).
  - **한계**: 배포와 종료 사이 `image-transfer` 를 바꾸면 반대편 아티팩트가 남을 수 있다(플래그가 전역이라). 기존 인스턴스 역할은 정책 재부착 안 함 → ECR 권한은 새 프로젝트부터 반영.
- **P4 — compose 다중 컨테이너** 🟢 *back+db·web 완료*: web+back(+db) 여러 이미지 한 EC2. user-data 가 `docker compose up`.
  - **번들 DB(back+db) 완료**: DOCKER 배포에 `bundledDbEngine`(MYSQL/POSTGRESQL) — 같은 EC2 에 앱+DB 컨테이너("올인원", RDS 없이). `provisioned_servers.bundled_db_engine`(V41). DB 비번은 배포 시 생성→SSM→`.env`, 앱은 `jdbc:{engine}://db/appdb`. S3·ECR 둘 다. **실 EC2 e2e 검증**(S3·ECR 둘 다).
  - **웹(프론트) 컨테이너 완료**: DOCKER 배포에 `frontendRepo`(split)·`frontendDir`(모노)·`apiPathPrefix`(기본 /api, 콤마 다중) — 같은 EC2 에 프론트 nginx 컨테이너를 함께("back+web+db 전부 컨테이너", 같은 오리진). `provisioned_servers`(V42). 프론트→nginx 이미지(`WebImageBuildService`+`WebAssetsFactory`, `ContainerImageBuilder` 공용). **포트 모델: web 이 호스트 포트, app 은 내부**(nginx 가 프리픽스를 app 으로 프록시[유지]+SPA 폴백, resolver 지연해석). 앱·웹 이미지 둘 다 전달(S3=image.tar+web.tar / ECR=qeploy-app+qeploy-web). convention: API 는 단일(콤마 다중) 프리픽스, 프론트는 상대경로 호출. SSR(런타임 node)은 대상 아님. **보안: 사용자 입력(frontendRepo/dir)을 셸 주입 차단 검증.**
  - **검증**: 생성되는 compose.yml 을 로컬 `docker compose up` 으로 실구동 — 번들 DB=JPA `/db` 200(insert+count), 웹=web+app+db 3서비스 기동+nginx→app 프록시+SPA 폴백+app 내부전용. 생성물이 검증본과 바이트 동일함을 테스트로 못박음. compose 플러그인 다운로드·SSM→.env 는 EC2 실 e2e 로 검증(번들 DB).
- **P5 — `ProvisionMethod.DOCKER`(독립 DB 자원) 활성**: DB 도메인의 독립 DB 컨테이너(RDS 대안, 자체 EC2/승인/상태워커). 위 번들 DB 와 별개 — 번들은 앱과 한 인스턴스, 이건 DB 단독 자원. 아직 "곧 지원".

## 결정

- **배포 모드는 명시 선택**(agent/FE): `NATIVE` | `DOCKER`. 기본은 스택으로 추정하되 사용자가 덮어쓸 수 있게(이게 "되묻기 C2"와 만나는 지점 — 배포형태를 스펙으로 물음).
- **이미지 전달 = P2 는 S3 save/load**(IAM 그대로, 느리지만 최소권한) → **P3 에서 ECR**.
- **비밀/포트/헬스체크**는 현행 그대로(SSM SecureString + 인스턴스 역할, TCP port-ready). Docker 도 컨테이너에 env 주입, 호스트 포트 매핑 후 같은 헬스체크.
- **프론트**: split 은 현행 GH Pages(DEPLOY) 유지. "프론트도 EC2/컨테이너"는 P4 compose(web 컨테이너=nginx)로.

## 검증 전략 (CODE 모델 무관)

각 단계마다 **손으로 짠 최소 앱**(스택별 + Dockerfile)을 테스트 repo 로 두고 실 EC2 배포로 완주 검증 — dvely-be-db(jar)로 native-Java 를 검증했던 방식 그대로. CODE 에이전트(앱 생성)는 별도 트랙(모델 확보 후).

## 보안 하드닝 (P2 후속, 멀티테넌트 운영 전)

P2 는 `docker build` 를 **호스트 Docker 데몬**에서 돈다(`DockerContainerService.buildImage`) — 신뢰할 수
없는 사용자 Dockerfile 의 빌드 스텝이 우리 컨트롤 플레인 데몬에서 실행된다. **단일 테넌트·검증 단계
한정**이며, 기존에 gradle/npm 을 샌드박스 컨테이너에서 돌리는 것과 유사한 트러스트 수준이다. 다만
멀티테넌트 SaaS 운영 전에는 **kaniko 또는 rootless buildkit** 로 옮겨 호스트 데몬 노출을 없애야 한다
(이미지 빌드를 유저스페이스에서 데몬 없이 수행). 소스 clone 은 지금도 격리 샌드박스에서 한다.

## 별도 트랙 (이 설계와 분리)

- **되묻기(C2)**: 개발 전 스택·아키텍처·배포모드를 물음. 여기 "배포 모드 명시 선택"과 만난다.
- **CODE 모델**: glm-4.7-flash 는 레이트리밋·비수렴으로 CODE 툴루프에 부적합(검증됨). 완주엔 다른 모델 필요.
