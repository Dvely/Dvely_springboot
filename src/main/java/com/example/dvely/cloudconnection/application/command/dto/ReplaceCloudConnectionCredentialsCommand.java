package com.example.dvely.cloudconnection.application.command.dto;

public record ReplaceCloudConnectionCredentialsCommand(
        String awsCredentialType,
        String accountId,
        String roleArn,
        String accessKeyId,
        String secretAccessKey
) {
    // 레코드 기본 toString 은 secretAccessKey 를 그대로 찍는다 — 예외·로그에 끌려 나가지 않게 가린다.
    @Override
    public String toString() {
        return "ReplaceCloudConnectionCredentialsCommand[awsCredentialType=" + awsCredentialType + ", accountId=" + accountId
                + ", roleArn=" + roleArn + ", accessKeyId=" + accessKeyId + ", secretAccessKey=***]";
    }
}
