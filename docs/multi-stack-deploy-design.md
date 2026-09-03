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
- **P2 — Docker 모드 골격(스택 무관 핵심)**: `deploy_mode=DOCKER`. 빌드 컨테이너에서 앱의 `Dockerfile`로 `docker build` → 이미지 `docker save`→S3. user-data 가 Docker 설치+S3에서 `docker load`+`docker run`(포트 매핑, SSM env 주입). **Node·Java·Next 다 동일 경로.** 앱에 Dockerfile 없으면 스택 감지로 기본 Dockerfile 생성(폴백).
- **P3 — 이미지 전달 ECR 화(선택 고도화)**: S3 save/load → ECR push/pull. IAM 에 `ecr:*`(scoped) 추가. 대용량 이미지·레이어 캐시에 유리.
- **P4 — compose 다중 컨테이너**: web+back(+db) 여러 이미지 한 EC2. user-data 가 `docker compose up`. mono 풀스택을 컨테이너 분리로.
- **P5 — `ProvisionMethod.DOCKER` 활성**: EC2 위 DB 컨테이너(RDS 대안). 승인·상태·정리는 RDS 골격 복제. compose(P4)와 합쳐 "back+web+db 전부 컨테이너 한 EC2" 완성.

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
