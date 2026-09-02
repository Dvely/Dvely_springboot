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
        String instanceProfileOverride
) {
    public boolean hasInstanceProfileOverride() {
        return instanceProfileOverride != null && !instanceProfileOverride.isBlank();
    }
}
