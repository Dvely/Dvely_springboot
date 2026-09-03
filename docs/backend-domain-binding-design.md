# 백엔드 도메인 바인딩 + EIP 설계 (C2 후속)

EC2에 뜬 사용자 백엔드에 **안정 주소(EIP)** 를 붙이고 **커스텀 도메인 / 관리형 서브도메인**으로
연결하며, 프론트↔백엔드 **CORS** 를 통합한다. 프론트(GitHub Pages)는 https가 공짜였지만
백엔드 EC2:8080 은 http 라, 안정 주소·DNS·TLS·CORS 를 새로 설계해야 한다.

## 결정 (2026-09-03)

- **EIP**: 서버당 Elastic IP 할당·연결, 종료 시 release. 재배포에도 IP 유지(도메인 안 깨지게).
  요즘 AWS 는 자동 public IPv4 도 EIP 와 동일 과금이라 비용차 거의 없음.
- **HTTPS = Cloudflare 프록시 활용**. 존은 `qeploy.com` 하나뿐이라 경로별로 다르다:
  - 관리형 `label.qeploy.com` → 우리 Cloudflare 프록시 자동 → **HTTPS 공짜**.
  - 커스텀 `example.com` → 우리 존이 아님. MVP 는 http 직접(A→EIP) 또는 사용자가 자기 Cloudflare
    로 프록시. 완전 자동 커스텀-HTTPS 는 **Cloudflare for SaaS(커스텀 호스트네임)** 로 후속.
- **CORS = 프론트 오리진 env 주입 + 백엔드 템플릿**. dvely 가 프론트 도메인을 백엔드에
  `QEPLOY_ALLOWED_ORIGINS` 로 주입(`assembleEnv`), dvely 제공 백엔드 템플릿이 그 값으로 CORS 설정.
  사용자 자작 앱은 이 규약을 문서화.
- **범위**: 커스텀 도메인 + 관리형 서브도메인 **둘 다**.

## 코드 접점 (조사 완료 2026-09-03)

- **어댑터 패턴 이미 있음**: `DomainHostingTarget{GITHUB_PAGES,AWS,GCP}` 에 AWS 선언됐으나 어댑터
  없어 `DomainHostingAdapterRegistry.resolve(AWS)` 가 throw. `AwsDomainHostingAdapter`(5메서드:
  `target/resolveDnsTarget/bind/verify/unbind`) 추가하면 열림. Context = {userToken, projectId,
  sourceRepository, deploymentRepository, currentVersion, currentUrl} — **백엔드 host 가 없음.**
- **Cloudflare 클라이언트**: `CloudflareDnsPort` 는 `createCnameRecord/recordExists/deleteRecord`
  뿐 — **A레코드 생성 없음.** 관리형→EIP(IP) 하려면 `createARecord(hostname, ip)` 추가 필요.
  프록시 기본 on(TLS 자동).
- **A-레코드 검증은 이미 있음**: `DnsLookupClient.hasAddressRecordMatching` — 커스텀+A 검증은 코드
  추가 거의 없음.
- **GitHub 토큰 게이트**: `DomainBindingCommandService.resolveUser` 가 모든 bind 에서 githubUser
  AccessToken 요구. AWS bind 도 걸림 → `hostingTarget != GITHUB_PAGES` 면 게이트 우회 필요.
- **CORS 주입 없음**: `BackendDeployRunner.assembleEnv` 가 `SERVER_PORT` + `SPRING_DATASOURCE_*` +
  사용자 env 만 주입. 여기에 `QEPLOY_ALLOWED_ORIGINS` 추가가 접점.
- **백엔드 URL 이 프로젝트에 안 박힘**: `ProvisionedServer.publicHost` 에만. AWS 도메인 바인딩의
  hostname 자체를 백엔드 URL 로 삼아, FE 가 `hostingTarget=AWS` 바인딩으로 구분(새 컬럼 불필요).
- **종료 시 도메인 정리·경고 없음**: `terminate` 는 인스턴스+SSM+S3 만. EIP release·도메인 정리 필요.

## 아키텍처 — 두 도메인 경로

```
[관리형] user picks label + AWS target
  → Cloudflare A레코드(proxied) → EIP:8080
  → 브라우저 https → Cloudflare(TLS) → http → EIP  (HTTPS 공짜)

[커스텀] user owns example.com
  → dvely 가이드: "example.com A레코드를 <EIP> 로"
  → 사용자 DNS 설정 → dvely 가 A-검증
  → HTTPS: 사용자가 자기 Cloudflare 프록시(권장) 또는 http (또는 후속 Cloudflare for SaaS)
```

EIP 는 프로젝트 백엔드에 묶여 **재배포에도 유지**(재배포 기능 생길 때 EIP 보존·재연결). 종료 시 release.

## 단계별 계획 (develop-first PR 단위)

- **Phase 1 — EIP(안정 주소)**: `ProvisionedServer.elasticIpAllocationId` + 마이그레이션.
  `Ec2Provisioner` allocate/associate/disassociate/release + `describeAddresses`. `BackendDeployRunner`
  launch 후 allocate+associate, publicHost=EIP. `terminate` 에서 release(best-effort).
  IAM 정책 +`ec2:AllocateAddress/AssociateAddress/DisassociateAddress/ReleaseAddress/DescribeAddresses`
  (docs + 온보딩 + 사용자 키). **실계정 검증**(EIP 붙은 채 curl, 종료 후 release 확인).
- **Phase 2 — AWS 어댑터 + 커스텀 도메인(MVP)**: `AwsDomainHostingAdapter`(target=AWS). GitHub 토큰
  게이트 우회(비-GITHUB_PAGES). 어댑터가 프로젝트 RUNNING 서버 publicHost(EIP) 조회(port 로
  provisioning 참조). 커스텀 bind: 가이드에 EIP 노출, A-검증. FE 는 이미 있는 AWS 옵션 활성화.
- **Phase 3 — 관리형 서브도메인 → 백엔드**: `CloudflareDnsPort.createARecord` 추가. 관리형 bind 가
  AWS target 이면 CNAME 대신 A레코드(→EIP, proxied) → **HTTPS 자동**.
- **Phase 4 — CORS 주입**: `assembleEnv` 가 `QEPLOY_ALLOWED_ORIGINS`(프론트 오리진) 주입. dvely 백엔드
  템플릿이 그 값으로 CORS 설정(템플릿 소유 주체 미정 이슈와 연계). 프론트 도메인 변경 시 재주입 필요(후속).
- **Phase 5 — 종료 정리·경고**: terminate 에서 EIP release(Phase1) + 프로젝트의 AWS 도메인 바인딩
  처리(관리형은 Cloudflare 레코드 삭제, 공통은 broken 표시). FE 종료 대화상자에 "연결된 도메인이 끊깁니다" 줄.

## 미정·후속

- **커스텀 도메인 완전 자동 HTTPS** = Cloudflare for SaaS(커스텀 호스트네임, 유료 애드온). MVP 이후.
- **EIP 재배포 보존**: 재배포 기능(별건 미구현)과 함께. 지금은 서버당 EIP + 종료 release.
- **CORS 재주입**: 도메인이 배포 후에 붙으므로 프론트 오리진 변경 시 백엔드 env 갱신·재기동 경로 필요.
- **백엔드 템플릿**: CORS 규약을 읽는 dvely 제공 템플릿 — 템플릿 소유(FE/BE) 미정 이슈에 걸림.
- Phase1 종료 견고성: EIP release 도 best-effort(정리 실패가 종료 막지 않게, 기존 패턴 따름).

## 향후 — 프론트 호스팅 다양화 (로드맵, MVP 아님)

지금은 프론트 = GitHub Pages 고정(`DeployWorkflowTemplate` 하드코딩). 향후 **EC2 가 있으니 프론트도
GitHub Pages 외에 S3(정적, +CloudFront 로 CDN·HTTPS) 또는 EC2 배포**로 고를 수 있어야 한다.

**지금 정해둘 것 — 타깃 네이밍 충돌 방지.** 현재 `DomainHostingTarget{GITHUB_PAGES, AWS, GCP}` 에서
이번 설계는 `AWS` 를 **EC2 백엔드** 뜻으로 쓴다. 그런데 프론트-on-S3 도 "AWS" 라 나중에 의미가
충돌한다. → **타깃은 클라우드가 아니라 "무엇을 어디에" 로 구분해야 한다.** 향후 확장 시 `AWS` 를
덮어쓰지 말고 별도 값 추가:

- `GITHUB_PAGES` — 프론트 정적(현행)
- `AWS`(=현 MVP, EC2 백엔드) → 명확화가 필요해지면 `AWS_EC2_BACKEND` 로 별칭/이관
- (향후) `AWS_S3_FRONTEND` — 프론트 정적 on S3+CloudFront (HTTPS·CDN 공짜에 가까움, 프론트엔 이게 정석)
- (향후) `AWS_EC2_FRONTEND` — 굳이 EC2 로 프론트 서빙(비권장, 정적은 S3 가 맞음)

즉 호스팅 타깃은 **(프론트/백엔드) × (호스트 종류)** 2축으로 커진다. 이번 PR 에서 enum 을 미리
쪼갤 필요는 없지만(FE zod 도 GITHUB_PAGES/AWS/GCP 로 맞춰져 있음), **`AWS` 가 "EC2 백엔드" 라는
전제를 코드·문서에 명시**해 두어 나중에 프론트 타깃이 들어올 때 혼선이 없게 한다. 프론트 배포 파이프라인
자체(정적 산출물 → S3 업로드 → CloudFront 무효화)는 별도 설계 필요 — deployment 도메인이 GitHub Pages
에 하드코딩돼 있어 백엔드 배포처럼 골격을 새로 얹는 접근이 맞다.
