package com.example.dvely.domainbinding.domain.value;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

public enum DomainType {
    MANAGED_SUBDOMAIN("managed_subdomain"),
    PURCHASABLE_DOMAIN("purchasable_domain"),
    CUSTOM_DOMAIN("custom_domain");

    private final String value;

    DomainType(String value) {
        this.value = value;
    }

    @JsonCreator
    public static DomainType from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("domain type must not be blank");
        }
        return Arrays.stream(values())
                .filter(type -> type.value.equalsIgnoreCase(value) || type.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 도메인 타입입니다: " + value));
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
