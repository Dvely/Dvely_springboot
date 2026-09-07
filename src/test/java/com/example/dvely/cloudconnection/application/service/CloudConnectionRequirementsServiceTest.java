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

    private static final String PLATFORM_ARN = "arn:aws:iam::999888777666:role/qeploy-control-plane";

    private final AwsPolicyDocumentLoader policyDocumentLoader = new AwsPolicyDocumentLoader();

    private CloudConnectionRequirementsService service(String platformPrincipalArn) {
        return new CloudConnectionRequirementsService(
                policyDocumentLoader,
                new CloudConnectionGuideProperties(platformPrincipalArn)
        );
    }

    // ── 역할 위임이 준비된 환경(platform ARN 설정됨) ──────────────────────────

    @Test
    void roleArnGuideCarriesTrustPolicyAndTheFullRecommendedPolicy() {
        CloudConnectionRequirementsResult result = service(PLATFORM_ARN).getRequirements("AWS", "ROLE_ARN");

        assertThat(result.recommendedCredentialType()).isEqualTo("ROLE_ARN");
        assertThat(result.credentialOptions()).hasSize(2);   // 역할 위임 + 액세스 키
        assertThat(result.trustPolicy()).isNotNull();
        assertThat(result.fields()).anySatisfy(f -> assertThat(f.key()).isEqualTo("roleArn"));
        // 신뢰 정책 Principal 이 설정한 플랫폼 ARN 으로 치환됐는지
        assertThat(flatten(result.trustPolicy())).contains(PLATFORM_ARN);
        assertThat(collectActions(result.recommendedPolicy())).containsAll(MUST_HAVE_ACTIONS);
    }

    @Test
    void accessKeyGuideKeepsRecommendingRoleArnWhenDelegationAvailable() {
        CloudConnectionRequirementsResult result = service(PLATFORM_ARN).getRequirements("AWS", "ACCESS_KEY");

        assertThat(result.credentialType()).isEqualTo("ACCESS_KEY");
        assertThat(result.trustPolicy()).isNull();                     // 키 방식엔 신뢰 정책이 없다
        assertThat(result.recommendedCredentialType()).isEqualTo("ROLE_ARN");   // 위임 가능하니 권장은 역할
        assertThat(result.credentialOptions()).hasSize(2);
        assertThat(result.fields()).anySatisfy(f -> {
            if (f.key().equals("secretAccessKey")) {
                assertThat(f.secret()).isTrue();
            }
        });
        assertThat(collectActions(result.recommendedPolicy())).containsAll(MUST_HAVE_ACTIONS);
    }

    @Test
    void defaultsToRoleArnWhenBlankAndDelegationAvailable() {
        CloudConnectionRequirementsResult result = service(PLATFORM_ARN).getRequirements("AWS", "");

        assertThat(result.credentialType()).isEqualTo("ROLE_ARN");
    }

    // ── 역할 위임이 준비 안 된 환경(platform ARN 미설정) ─────────────────────

    @Test
    void hidesRoleArnAndFallsBackToAccessKeyWhenPlatformIdentityMissing() {
        // ROLE_ARN 을 명시적으로 요청해도, 컨트롤 플레인 신원이 없어 위임이 실동작하지 않으므로
        // 액세스 키 가이드로 떨어뜨린다 — 사용자를 안 되는 흐름으로 보내지 않는다.
        CloudConnectionRequirementsResult result = service("").getRequirements("AWS", "ROLE_ARN");

        assertThat(result.credentialType()).isEqualTo("ACCESS_KEY");
        assertThat(result.recommendedCredentialType()).isEqualTo("ACCESS_KEY");
        assertThat(result.credentialOptions()).hasSize(1);             // 액세스 키만 — FE 는 탭을 감춘다
        assertThat(result.credentialOptions().get(0).type()).isEqualTo("ACCESS_KEY");
        assertThat(result.trustPolicy()).isNull();
        // 왜 역할 위임 탭이 없는지 알리는 주의가 있어야 한다
        assertThat(result.notes()).anySatisfy(n -> assertThat(n).contains("역할 위임"));
        // 정책 자체는 그대로 전체본을 준다
        assertThat(collectActions(result.recommendedPolicy())).containsAll(MUST_HAVE_ACTIONS);
    }

    @Test
    void blankCredentialTypeAlsoFallsBackToAccessKeyWhenPlatformIdentityMissing() {
        CloudConnectionRequirementsResult result = service("   ").getRequirements("AWS", "");

        assertThat(result.credentialType()).isEqualTo("ACCESS_KEY");
        assertThat(result.credentialOptions()).hasSize(1);
    }

    @Test
    void gcpIsNotYetSupported() {
        assertThatThrownBy(() -> service(PLATFORM_ARN).getRequirements("GCP", "ROLE_ARN"))
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
