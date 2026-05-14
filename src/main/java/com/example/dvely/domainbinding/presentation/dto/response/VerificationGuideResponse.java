package com.example.dvely.domainbinding.presentation.dto.response;

import java.util.List;

public record VerificationGuideResponse(
        String hostname,
        String verificationMethod,
        List<Record> records
) {

    public record Record(
            String type,
            String host,
            String value
    ) {
    }
}
