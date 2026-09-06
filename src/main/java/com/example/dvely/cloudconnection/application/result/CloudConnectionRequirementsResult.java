package com.example.dvely.cloudconnection.application.result;

import java.util.List;
import java.util.Map;

/**
 * 클라우드 연결을 만들기 전에 사용자가 자기 계정에서 무엇을 준비해야 하는지 — 입력할 값, 그 값을
 * 어디서 얻는지, 우리가 접근·처리하려면 붙여야 할 추천 IAM 정책 — 를 담은 가이드.
 *
 * <p>연결 검증(STS GetCallerIdentity)은 자격 유효성만 확인하고 프로비저닝 권한은 확인하지 않으므로,
 * 이 정책을 미리 붙이지 않으면 연결은 성공해도 배포 때 권한 부족으로 실패한다. 그 사실을 사용자가
 * 연결 화면에서 알 수 있게 하는 것이 이 가이드의 목적이다.</p>
 */
public record CloudConnectionRequirementsResult(
        String provider,
        String credentialType,
        String recommendedCredentialType,
        List<CredentialOption> credentialOptions,
        List<RequirementField> fields,
        List<SetupStep> steps,
        String policyName,
        String roleName,
        Map<String, Object> recommendedPolicy,
        Map<String, Object> trustPolicy,
        List<String> notes
) {

    /** 인증 방식 선택지(ROLE_ARN 권장 / ACCESS_KEY). 어느 것을 기본 추천하는지는 recommended 로 표시. */
    public record CredentialOption(String type, String label, boolean recommended, String summary) {
    }

    /** 연결 폼이 받을 값 하나. whereToFind 로 "어디서 이 값을 얻는지"를 안내한다. */
    public record RequirementField(
            String key,
            String label,
            String description,
            String whereToFind,
            String example,
            boolean required,
            boolean secret
    ) {
    }

    /** 사용자가 자기 AWS 콘솔에서 밟을 준비 단계. */
    public record SetupStep(int order, String title, String detail) {
    }
}
