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
        List<String> notes,
        List<CredentialCase> credentialCases,
        List<PolicyModule> policyModules
) {

    /**
     * 자격의 종류 하나 — 무엇이고, 얼마나 사는지, 여기서 받는지, 어떻게 얻는지. 받지 않는 종류(임시 자격)도
     * 이유와 함께 싣는다. 사용자가 가진 키가 그것이면 왜 안 되는지, 무엇으로 바꾸면 되는지를 알아야 해서다.
     */
    public record CredentialCase(
            String type,
            String label,
            String lifetime,
            boolean supported,
            boolean recommended,
            String summary,
            List<String> howToGet,
            String caution
    ) {
    }

    /**
     * 권한 정책을 기능별로 고르는 묶음 하나. 고른 묶음들의 statements 를 Sid 기준으로 합치면 붙여넣을
     * 정책 한 장이 된다(같은 Sid 는 한 번만). 필수가 아닌 기본 묶음을 모두 고르면 recommendedPolicy 와 같다.
     */
    public record PolicyModule(
            String key,
            String label,
            String description,
            boolean required,
            boolean defaultSelected,
            List<Map<String, Object>> statements
    ) {
    }

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
