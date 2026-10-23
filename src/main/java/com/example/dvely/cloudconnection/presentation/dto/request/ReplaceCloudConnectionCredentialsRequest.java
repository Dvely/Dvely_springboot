package com.example.dvely.cloudconnection.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "AWS 연결 자격 교체 요청. 방식에 맞는 필드만 채웁니다. secretAccessKey 는 AES 로 암호화 저장되며 "
        + "어떤 응답에도 평문으로 노출되지 않습니다. 기존 세션 토큰은 항상 지워집니다.")
public record ReplaceCloudConnectionCredentialsRequest(
        @Schema(description = "AWS 인증 방식. 생략 시 연결의 현재 방식을 유지합니다",
                allowableValues = {"ACCESS_KEY", "ROLE_ARN"}, example = "ACCESS_KEY")
        String awsCredentialType,

        @Schema(description = "AWS 계정 ID. 연결에 이미 있으면 생략하거나 같은 값이어야 합니다(다르면 400). "
                + "ROLE_ARN 이면 연결에 없을 때 필수", example = "123456789012")
        String accountId,

        @Schema(description = "AWS Role ARN. awsCredentialType=ROLE_ARN 일 때 사용",
                example = "arn:aws:iam::123456789012:role/QeployDeployRole")
        String roleArn,

        @Schema(description = "AWS Access Key ID(AKIA). awsCredentialType=ACCESS_KEY 일 때 사용. ASIA(임시 자격)는 400")
        String accessKeyId,

        @Schema(description = "AWS Secret Access Key. awsCredentialType=ACCESS_KEY 일 때 사용. 응답에 노출되지 않음")
        String secretAccessKey
) {
    // 레코드 기본 toString 은 secretAccessKey 를 그대로 찍는다 — 예외·로그에 끌려 나가지 않게 가린다.
    @Override
    public String toString() {
        return "ReplaceCloudConnectionCredentialsRequest[awsCredentialType=" + awsCredentialType + ", accountId=" + accountId
                + ", roleArn=" + roleArn + ", accessKeyId=" + accessKeyId + ", secretAccessKey=***]";
    }
}
