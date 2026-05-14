package com.example.dvely.domainbinding.domain.value;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Arrays;

public enum VerificationMethod {
    CNAME,
    A;

    @JsonCreator
    public static VerificationMethod from(String value) {
        if (value == null || value.isBlank()) {
            return CNAME;
        }
        return Arrays.stream(values())
                .filter(method -> method.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 DNS 검증 방식입니다: " + value));
    }
}
