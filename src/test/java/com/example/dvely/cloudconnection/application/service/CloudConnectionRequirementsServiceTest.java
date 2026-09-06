package com.example.dvely.cloudconnection.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dvely.cloudconnection.application.result.CloudConnectionRequirementsResult;
import com.example.dvely.cloudconnection.infrastructure.config.CloudConnectionGuideProperties;
import com.example.dvely.cloudconnection.infrastructure.external.AwsPolicyDocumentLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CloudConnectionRequirementsServiceTest {

    // 프로비저닝 코드가 실제로 호출하는 액션들. 추천 정책에서 이게 빠지면 연결은 성공해도 배포가
    // 권한 부족으로 죽는다 — 정책 리소스가 코드와 어긋나지 않게 지키는 드리프트 가드.
    private static final List<String> MUST_HAVE_ACTIONS = List.of(
            "ec2:RunInstances", "ec2:TerminateInstances", "ec2:AllocateAddress", "ec2:AssociateAddress",
            "rds:CreateDBInstance", "rds:DeleteDBInstance",
            "s3:CreateBucket", "s3:PutObject",
            "cloudfront:CreateDistribution", "acm:RequestCertificate",
            "ssm:PutParameter", "ssm:SendCommand",
            "iam:CreateRole", "iam:PassRole",
            "sts:GetCallerIdentity"
    );

    private final AwsPolicyDocumentLoader policyDocumentLoader = new AwsPolicyDocumentLoader();

    private CloudConnectionRequirementsService service(String platformPrincipalArn) {
        return new CloudConnectionRequirementsService(
                policyDocumentLoader,
                new CloudConnectionGuideProperties(platformPrincipalArn)
        );
    }

    @Test
    void roleArnGuideCarriesTrustPolicyAndTheFullRecommendedPolicy() {
        CloudConnectionRequirementsResult result =
                service("arn:aws:iam::999888777666:role/qeploy-control-plane").getRequirements("AWS", "ROLE_ARN");

        assertThat(result.recommendedCredentialType()).isEqualTo("ROLE_ARN");
        assertThat(result.trustPolicy()).isNotNull();
        assertThat(result.fields()).anySatisfy(f -> assertThat(f.key()).isEqualTo("roleArn"));
        // 신뢰 정책 Principal 이 설정한 플랫폼 ARN 으로 치환됐는지
        assertThat(flatten(result.trustPolicy())).contains("arn:aws:iam::999888777666:role/qeploy-control-plane");
        // 추천 정책이 코드가 쓰는 모든 핵심 액션을 담는지
        assertThat(collectActions(result.recommendedPolicy())).containsAll(MUST_HAVE_ACTIONS);
    }

    @Test
    void accessKeyGuideHasNoTrustPolicyButStillRecommendsRoleArn() {
        CloudConnectionRequirementsResult result = service("").getRequirements("AWS", "ACCESS_KEY");

        assertThat(result.credentialType()).isEqualTo("ACCESS_KEY");
        assertThat(result.trustPolicy()).isNull();                 // 키 방식엔 신뢰 정책이 없다
        assertThat(result.recommendedCredentialType()).isEqualTo("ROLE_ARN");   // 그래도 권장은 역할 위임
        assertThat(result.fields()).anySatisfy(f -> {
            if (f.key().equals("secretAccessKey")) {
                assertThat(f.secret()).isTrue();                   // 시크릿 필드 표시
            }
        });
        assertThat(collectActions(result.recommendedPolicy())).containsAll(MUST_HAVE_ACTIONS);
    }

    @Test
    void unsetPlatformPrincipalShowsPlaceholderAndAWarningNote() {
        CloudConnectionRequirementsResult result = service("").getRequirements("AWS", "ROLE_ARN");

        assertThat(flatten(result.trustPolicy())).contains("미설정");
        assertThat(result.notes()).anySatisfy(n -> assertThat(n).contains("placeholder"));
    }

    @Test
    void defaultsToRoleArnWhenCredentialTypeBlank() {
        CloudConnectionRequirementsResult result = service("arn:aws:iam::1:role/x").getRequirements("AWS", "");

        assertThat(result.credentialType()).isEqualTo("ROLE_ARN");
    }

    @Test
    void gcpIsNotYetSupported() {
        assertThatThrownBy(() -> service("").getRequirements("GCP", "ROLE_ARN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AWS");
    }

    @SuppressWarnings("unchecked")
    private List<String> collectActions(Map<String, Object> policy) {
        List<String> actions = new ArrayList<>();
        for (Object statement : (List<Object>) policy.get("Statement")) {
            Object action = ((Map<String, Object>) statement).get("Action");
            if (action instanceof String single) {
                actions.add(single);
            } else if (action instanceof List<?> many) {
                many.forEach(a -> actions.add((String) a));
            }
        }
        return actions;
    }

    private String flatten(Map<String, Object> document) {
        return document.toString();
    }
}
