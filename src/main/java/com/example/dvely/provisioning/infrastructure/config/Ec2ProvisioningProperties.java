package com.example.dvely.provisioning.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * EC2 배포 설정.
 *
 * <p>instanceProfileOverride: 인스턴스에 붙일 IAM 인스턴스 프로파일을 <b>새로 만들지 않고</b> 이미
 * 존재하는 이름을 그대로 쓰게 한다. 기본(빈 값)이면 {@code Ec2InstanceRoleProvisioner}가
 * {@code /qeploy/} 경로에 최소권한으로 생성한다(운영·개인계정 기본 동작). 하지만 IAM 역할 생성이
 * 금지된 환경 — 대표적으로 AWS Academy Learner Lab — 에서는 그 생성이 AccessDenied 로 깨진다.
 * 그런 환경에서는 이미 주어진 프로파일(예: {@code LabInstanceProfile})을 이 값으로 지정하면
 * 생성을 건너뛰고 그걸 쓴다. 단 그 프로파일의 역할이 넓은 권한을 가질 수 있어(스코프 축소 불가)
 * 실습·검증 용도에 한한다.</p>
 */
@ConfigurationProperties(prefix = "qeploy.provisioning.ec2")
public record Ec2ProvisioningProperties(
        String instanceProfileOverride,
        String tlsAskBaseUrl,
        String imageTransfer,
        String buildIsolation
) {
    public boolean hasInstanceProfileOverride() {
        return instanceProfileOverride != null && !instanceProfileOverride.isBlank();
    }

    /**
     * DOCKER 배포 모드에서 이미지를 EC2 로 넘기는 방식. 기본(빈 값/미설정)은 S3(`docker save`→S3→
     * 인스턴스가 `docker load`) — 추가 IAM 권한이 필요 없어 안전한 기본값이다. {@code ECR} 로 켜면
     * 사용자 계정 ECR 로 push/pull 한다(레이어 캐시·속도 유리). ECR 은 사용자 BYOC 정책에 ECR 권한
     * 추가가 필요하므로 명시적으로만 켠다. NATIVE(jar) 모드에는 영향 없다.
     */
    public boolean useEcr() {
        return imageTransfer != null && imageTransfer.trim().equalsIgnoreCase("ECR");
    }

    /**
     * 이미지 빌드 격리 방식. 기본(빈 값/미설정)은 {@code BUILDX} — 호스트 buildkit 로 빌드하며 크로스빌드
     * (arm64 컨트롤 플레인→amd64 이미지)를 지원한다(개발기·현행). {@code KANIKO} 로 켜면 신뢰할 수 없는
     * Dockerfile 의 빌드 스텝이 <b>호스트 데몬이 아니라 격리된 kaniko 컨테이너 안</b>에서 돈다(멀티테넌트
     * 하드닝). 단 kaniko 는 컨트롤 플레인 arch 로만 빌드하므로(크로스빌드 없음) <b>amd64 컨트롤 플레인에서만</b>
     * 켠다 — arm64 개발기에서 켜면 arm64 이미지가 나와 amd64 EC2 에서 안 뜬다.
     */
    public boolean useKaniko() {
        return buildIsolation != null && buildIsolation.trim().equalsIgnoreCase("KANIKO");
    }

    /**
     * 배포 인스턴스의 Caddy on-demand TLS ask 가 커스텀 도메인 발급 여부를 물어볼 BE 공개 base URL
     * (예: https://api.qeploy.com). 비면(기본) 커스텀 도메인은 인증서 발급을 못 하고 *.qeploy.com 만
     * HTTPS 가 붙는다 — 로컬 개발처럼 인스턴스가 BE 에 닿지 못하는 환경 대비 안전한 기본값.
     */
    public String tlsAskBaseUrlOrEmpty() {
        return tlsAskBaseUrl == null ? "" : tlsAskBaseUrl.trim();
    }
}
