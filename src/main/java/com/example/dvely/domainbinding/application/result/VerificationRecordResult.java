package com.example.dvely.domainbinding.application.result;

public record VerificationRecordResult(
        String type,
        String host,
        String value
) {
}
