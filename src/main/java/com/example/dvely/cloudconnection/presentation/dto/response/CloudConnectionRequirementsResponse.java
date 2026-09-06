package com.example.dvely.cloudconnection.presentation.dto.response;

import com.example.dvely.cloudconnection.application.result.CloudConnectionRequirementsResult;
import com.example.dvely.cloudconnection.application.result.CloudConnectionRequirementsResult.CredentialOption;
import com.example.dvely.cloudconnection.application.result.CloudConnectionRequirementsResult.RequirementField;
import com.example.dvely.cloudconnection.application.result.CloudConnectionRequirementsResult.SetupStep;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

@Schema(description = "클라우드 연결 가이드. 입력할 값·얻는 위치·밟을 단계와, 우리가 접근·처리하려면 붙여야 할 추천 IAM 정책(전체본)을 담는다. "
        + "recommendedPolicy/trustPolicy 는 그대로 복사해 AWS 콘솔에 붙여넣을 수 있는 JSON 문서다.")
public record CloudConnectionRequirementsResponse(
        @Schema(description = "provider", example = "AWS")
        String provider,

        @Schema(description = "이 가이드의 인증 방식", example = "ROLE_ARN")
        String credentialType,

        @Schema(description = "권장 인증 방식", example = "ROLE_ARN")
        String recommendedCredentialType,

        @Schema(description = "선택 가능한 인증 방식과 권장 표시")
        List<CredentialOption> credentialOptions,

        @Schema(description = "연결 폼이 받을 값들(어디서 얻는지 포함)")
        List<RequirementField> fields,

        @Schema(description = "사용자가 자기 AWS 콘솔에서 밟을 준비 단계")
        List<SetupStep> steps,

        @Schema(description = "추천 권한 정책 이름", example = "QeployDeployPolicy")
        String policyName,

        @Schema(description = "추천 역할 이름(ROLE_ARN)", example = "QeployDeployRole")
        String roleName,

        @Schema(description = "붙여넣을 IAM 권한 정책 전체본(JSON)")
        Map<String, Object> recommendedPolicy,

        @Schema(description = "ROLE_ARN 방식의 신뢰 정책(JSON). ACCESS_KEY 방식은 null")
        Map<String, Object> trustPolicy,

        @Schema(description = "주의·안내 문구")
        List<String> notes
) {

    public static CloudConnectionRequirementsResponse from(CloudConnectionRequirementsResult result) {
        return new CloudConnectionRequirementsResponse(
                result.provider(),
                result.credentialType(),
                result.recommendedCredentialType(),
                result.credentialOptions(),
                result.fields(),
                result.steps(),
                result.policyName(),
                result.roleName(),
                result.recommendedPolicy(),
                result.trustPolicy(),
                result.notes()
        );
    }
}
