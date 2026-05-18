package com.example.dvely.cloudconnection.domain.value;

import java.util.Locale;

public enum CloudProvider {
    AWS,
    GCP;

    public static CloudProvider from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        try {
            return CloudProvider.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("지원하지 않는 cloud provider입니다: " + value);
        }
    }
}
