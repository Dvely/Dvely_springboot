package com.example.dvely.cloudconnection.domain.value;

import java.util.Locale;

public enum GcpCredentialType {
    SERVICE_ACCOUNT_KEY,
    SERVICE_ACCOUNT_EMAIL;

    public static GcpCredentialType from(String value) {
        if (value == null || value.isBlank()) {
            return SERVICE_ACCOUNT_KEY;
        }
        try {
            return GcpCredentialType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("지원하지 않는 GCP 인증 방식입니다: " + value);
        }
    }
}
