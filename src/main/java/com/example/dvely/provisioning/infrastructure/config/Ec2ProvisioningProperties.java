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
        String tlsAskBaseUrl
) {
    public boolean hasInstanceProfileOverride() {
        return instanceProfileOverride != null && !instanceProfileOverride.isBlank();
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
