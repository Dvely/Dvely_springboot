package com.example.dvely.cloudconnection.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "클라우드 연결 정보. 시크릿 값(accessKeyId 일부 제외)은 노출되지 않고 *Configured 불리언으로만 존재 여부를 알려줍니다.")
public record CloudConnectionResponse(
        @Schema(description = "클라우드 연결 ID") Long cloudConnectionId,
        @Schema(description = "클라우드 provider", allowableValues = {"AWS", "GCP"}) String provider,
        @Schema(description = "연결 이름") String displayName,
        @Schema(description = "AWS 계정 ID. 미설정 시 null") String accountId,
        @Schema(description = "리전") String region,
        @Schema(description = "AWS Role ARN. awsCredentialType=ROLE_ARN이 아니면 null") String roleArn,
        @Schema(description = "AWS 인증 방식", allowableValues = {"ACCESS_KEY", "ROLE_ARN"}) String awsCredentialType,

        @Schema(description = "마스킹된 AWS Access Key ID. 앞 4자리+가운데 마스킹+뒤 4자리 형식(예: AKIA************3XYZ), 8자 이하면 \"****\", 미설정이면 null")
        String accessKeyId,

        @Schema(description = "AWS Secret Access Key 저장 여부(평문은 절대 노출되지 않음)") boolean secretAccessKeyConfigured,
        @Schema(description = "AWS Session Token 저장 여부(평문은 절대 노출되지 않음)") boolean sessionTokenConfigured,
        @Schema(description = "GCP 인증 방식", allowableValues = {"SERVICE_ACCOUNT_KEY", "SERVICE_ACCOUNT_EMAIL"}) String gcpCredentialType,
        @Schema(description = "GCP 서비스 계정 키 JSON 저장 여부(평문은 절대 노출되지 않음)") boolean serviceAccountKeyConfigured,
        @Schema(description = "GCP 프로젝트 ID") String projectId,
        @Schema(description = "GCP 서비스 계정 이메일") String serviceAccountEmail,

        @Schema(
                description = "연결 상태. VALIDATED(형식 검증 통과, 등록 직후) | VERIFYING/CHECKING(재검증 진행 중) | " +
                              "CONNECTED(실 권한 확인 통과) | PERMISSION_MISSING | BILLING_DISABLED | " +
                              "REGION_UNSUPPORTED | INVALID_CREDENTIAL | UNKNOWN_ERROR",
                example = "VALIDATED"
        )
        String status,

        @Schema(description = "마지막 상태 확인 시각") LocalDateTime lastCheckedAt,
        @Schema(description = "생성 시각") LocalDateTime createdAt,
        @Schema(description = "마지막 수정 시각") LocalDateTime updatedAt
) {
}
