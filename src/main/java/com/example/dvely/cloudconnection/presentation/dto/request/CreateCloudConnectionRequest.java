package com.example.dvely.cloudconnection.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "클라우드 연결 등록 요청. provider에 따라 AWS 또는 GCP 필드만 채웁니다(둘 다 optional — 사용하지 않는 " +
        "provider의 필드는 무시됩니다). 시크릿 필드(secretAccessKey, sessionToken, serviceAccountKeyJson)는 저장 시 AES로 암호화되며 어떤 응답에도 평문으로 노출되지 않습니다.")
public record CreateCloudConnectionRequest(
        @Schema(description = "클라우드 provider", allowableValues = {"AWS", "GCP"}, example = "AWS")
        @NotBlank String provider,

        @Schema(description = "사용자에게 보여줄 연결 이름", example = "AWS Seoul Account")
        @NotBlank @Size(max = 100) String displayName,

        @Schema(description = "AWS 계정 ID (선택). Role ARN 방식에서 주로 사용", example = "123456789012")
        String accountId,

        @Schema(description = "리전. AWS는 ap-northeast-2 형식, GCP는 asia-northeast3 형식", example = "ap-northeast-2")
        @NotBlank String region,

        @Schema(description = "AWS Role ARN. awsCredentialType=ROLE_ARN일 때 사용", example = "arn:aws:iam::123456789012:role/QeployDeployRole")
        String roleArn,

        @Schema(description = "AWS 인증 방식. 생략 시 ACCESS_KEY", allowableValues = {"ACCESS_KEY", "ROLE_ARN"}, example = "ACCESS_KEY")
        String awsCredentialType,

        @Schema(description = "AWS Access Key ID. awsCredentialType=ACCESS_KEY일 때 사용")
        String accessKeyId,

        @Schema(description = "AWS Secret Access Key. awsCredentialType=ACCESS_KEY일 때 사용. 응답에 노출되지 않음")
        String secretAccessKey,

        @Schema(description = "AWS Session Token. ASIA로 시작하는 임시 자격 증명일 때만 사용, AKIA 장기 키는 비워둠")
        String sessionToken,

        @Schema(description = "GCP 인증 방식. 생략 시 SERVICE_ACCOUNT_KEY", allowableValues = {"SERVICE_ACCOUNT_KEY", "SERVICE_ACCOUNT_EMAIL"}, example = "SERVICE_ACCOUNT_KEY")
        String gcpCredentialType,

        @Schema(description = "GCP 서비스 계정 키 JSON 원문. gcpCredentialType=SERVICE_ACCOUNT_KEY일 때 사용. 응답에 노출되지 않음")
        String serviceAccountKeyJson,

        @Schema(description = "GCP 프로젝트 ID. SERVICE_ACCOUNT_KEY 방식은 키 JSON의 project_id에서 자동 추출되므로 선택 사항", example = "qeploy-user-project")
        String projectId,

        @Schema(description = "GCP 서비스 계정 이메일. SERVICE_ACCOUNT_KEY 방식은 키 JSON의 client_email에서 자동 추출되므로 선택 사항", example = "qeploy-deploy@qeploy-user-project.iam.gserviceaccount.com")
        String serviceAccountEmail
) {
}
