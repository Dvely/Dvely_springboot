package com.example.dvely.cloudconnection.domain.value;

import java.util.Locale;

public enum AwsCredentialType {
    ACCESS_KEY,
    ROLE_ARN;

    public static AwsCredentialType from(String value) {
        if (value == null || value.isBlank()) {
            return ACCESS_KEY;
        }
        try {
            return AwsCredentialType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("지원하지 않는 AWS 인증 방식입니다: " + value);
        }
    }
}
