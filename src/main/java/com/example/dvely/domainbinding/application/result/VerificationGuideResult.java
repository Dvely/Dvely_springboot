package com.example.dvely.domainbinding.application.result;

import com.example.dvely.domainbinding.domain.value.VerificationMethod;
import java.util.List;

public record VerificationGuideResult(
        String hostname,
        VerificationMethod verificationMethod,
        List<VerificationRecordResult> records
) {
}
