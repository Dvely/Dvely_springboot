package com.example.dvely.cloudconnection.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 클라우드 연결 가이드(요구사항·추천 정책)에 필요한 배포 환경별 값.
 *
 * <p>platformPrincipalArn 은 ROLE_ARN 방식의 신뢰 정책에 박히는 값이다 — 사용자가 만들 역할을
 * <b>AssumeRole 하는 주체</b>, 즉 Qeploy 컨트롤 플레인 자신의 AWS identity ARN 이다({@code
 * AwsCredentialsResolver} 가 {@code DefaultCredentialsProvider} 로 STS AssumeRole 을 호출하는 그
 * identity). 환경마다 다르고(운영·dev·로컬) 이 코드가 알 수 없으므로 설정으로 받는다. 비면 가이드가
 * placeholder 를 노출하고 노트로 대체를 안내한다 — 그 상태의 신뢰 정책은 그대로 쓸 수 없다.</p>
 */
@ConfigurationProperties(prefix = "qeploy.cloud-connection.guide")
public record CloudConnectionGuideProperties(
        String platformPrincipalArn
) {

    /** 신뢰 정책 템플릿의 placeholder 를 대체할 값. 미설정이면 대체가 필요함을 드러내는 토큰을 준다. */
    public String platformPrincipalArnOrPlaceholder() {
        if (platformPrincipalArn == null || platformPrincipalArn.isBlank()) {
            return "<QEPLOY_PLATFORM_PRINCIPAL_ARN_미설정>";
        }
        return platformPrincipalArn.trim();
    }

    public boolean hasPlatformPrincipalArn() {
        return platformPrincipalArn != null && !platformPrincipalArn.isBlank();
    }
}
