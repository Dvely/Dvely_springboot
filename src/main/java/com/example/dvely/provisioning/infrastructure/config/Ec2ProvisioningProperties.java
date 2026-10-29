package com.example.dvely.provisioning.infrastructure.config;

import java.time.Duration;
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
        String buildIsolation,
        Duration imageBuildTimeout,
        Boolean allowNativeBuild,
        String imageBuildLocation
) {

    /** 빌드 위치 값 검증 — 잘못 넣은 값이 조용히 기본값으로 떨어지면 어디서 빌드했는지 모르게 된다(#556). */
    public Ec2ProvisioningProperties {
        if (imageBuildLocation != null && !imageBuildLocation.isBlank()
                && !imageBuildLocation.trim().equalsIgnoreCase("CONTROL_PLANE")
                && !imageBuildLocation.trim().equalsIgnoreCase("TARGET")) {
            throw new IllegalArgumentException(
                    "qeploy.provisioning.ec2.image-build-location 은 CONTROL_PLANE 또는 TARGET 이어야 합니다: "
                            + imageBuildLocation);
        }
    }

    /** 이미지 빌드·push·save 한 번을 기다리는 상한의 기본값(#507). 정상 빌드는 수 분이다. */
    static final Duration DEFAULT_IMAGE_BUILD_TIMEOUT = Duration.ofMinutes(30);

    /**
     * 이미지 빌드 프로세스 하나를 기다리는 상한({@code qeploy.provisioning.ec2.image-build-timeout}, 예: 45m).
     * 비었거나 0 이하면 기본 30분. 넘기면 프로세스를 강제 종료하고 빌드를 실패로 끝낸다 — 무기한 기다리면 워커
     * 스레드가 묶인다.
     */
    public Duration imageBuildTimeoutOrDefault() {
        return imageBuildTimeout == null || imageBuildTimeout.isZero() || imageBuildTimeout.isNegative()
                ? DEFAULT_IMAGE_BUILD_TIMEOUT : imageBuildTimeout;
    }

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
     * 이미지 빌드 격리 방식. 기본(빈 값/미설정)은 {@code KANIKO} — 사용자 Dockerfile 의 빌드 스텝이 <b>호스트 데몬이
     * 아니라 격리된 kaniko 컨테이너 안</b>에서 돈다(#507). kaniko 는 컨트롤 플레인 arch 로만 빌드하므로(크로스빌드
     * 없음) amd64 가 아닌 컨트롤 플레인에서는 빌드를 거부한다. {@code BUILDX} 는 명시했을 때만 쓴다 — 호스트
     * buildkit 로 빌드하고 크로스빌드(arm64 개발기→amd64 이미지)를 지원한다. 그 밖의 값은 KANIKO 로 본다(설정 실수로
     * 호스트 데몬 빌드로 떨어지지 않게).
     */
    public boolean useKaniko() {
        return buildIsolation == null || !buildIsolation.trim().equalsIgnoreCase("BUILDX");
    }

    /**
     * BUILDX 인데 buildx 플러그인이 없을 때 호스트 데몬의 {@code docker build} 로 대신 빌드해도 되는지
     * ({@code qeploy.provisioning.ec2.allow-native-build}). 기본 거부 — 명시적으로 true 일 때만 허용한다(#507).
     */
    public boolean allowNativeBuildFallback() {
        return Boolean.TRUE.equals(allowNativeBuild);
    }

    /**
     * DOCKER 배포 이미지를 어디서 빌드하는지(#545·#556). 기본(빈 값/미설정)은 {@code CONTROL_PLANE} — 지금처럼
     * 컨트롤 플레인이 빌드해 전달한다. {@code TARGET} 이면 빌드 컨텍스트만 S3 로 보내고 <b>배포 대상 EC2 가 직접
     * {@code docker build}</b> 한다. TARGET 은 이미지 전달이 필요 없으므로 {@link #useEcr()} 보다 앞선다.
     */
    public boolean buildOnTarget() {
        return imageBuildLocation != null && imageBuildLocation.trim().equalsIgnoreCase("TARGET");
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
