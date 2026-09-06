package com.example.dvely.cloudconnection.infrastructure.external;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 클라우드 연결 가이드가 보여줄 추천 IAM 문서를 classpath 리소스에서 읽어 파싱한다.
 *
 * <p>정책은 코드가 아니라 리소스 파일(`aws/qeploy-aws-*.json`)에 둔다 — 프로비저닝 코드가 호출하는
 * 실제 AWS 액션에서 도출한 것이라 사람이 통째로 검토·diff 할 수 있어야 하고, 가이드는 그 <b>전체본</b>을
 * 그대로 서빙해야 하기 때문이다. LinkedHashMap 으로 읽어 원문 키 순서를 유지한다(가이드에 그대로 노출됨).</p>
 */
@Component
public class AwsPolicyDocumentLoader {

    private static final String DEPLOY_POLICY_PATH = "aws/qeploy-aws-deploy-policy.json";
    private static final String TRUST_POLICY_PATH = "aws/qeploy-aws-trust-policy.json";
    private static final String PLATFORM_PRINCIPAL_PLACEHOLDER = "__PLATFORM_PRINCIPAL_ARN__";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 사용자 역할/키에 붙일 권한 정책 전체본. */
    public Map<String, Object> loadDeployPolicy() {
        return parse(readRaw(DEPLOY_POLICY_PATH), DEPLOY_POLICY_PATH);
    }

    /**
     * ROLE_ARN 방식의 신뢰 정책. placeholder 를 실제(또는 미설정 안내) 플랫폼 principal ARN 으로 치환해
     * 돌려준다. 치환은 문자열 리터럴 교체라 ARN 안의 문자를 그대로 보존한다.
     */
    public Map<String, Object> loadTrustPolicy(String platformPrincipalArn) {
        String filled = readRaw(TRUST_POLICY_PATH)
                .replace(PLATFORM_PRINCIPAL_PLACEHOLDER, platformPrincipalArn);
        return parse(filled, TRUST_POLICY_PATH);
    }

    private String readRaw(String path) {
        try {
            return new String(new ClassPathResource(path).getContentAsByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("AWS 정책 리소스를 읽지 못했습니다: " + path, e);
        }
    }

    private Map<String, Object> parse(String raw, String path) {
        try {
            return objectMapper.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("AWS 정책 리소스를 파싱하지 못했습니다: " + path, e);
        }
    }
}
