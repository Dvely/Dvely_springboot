package com.example.dvely.domainbinding.presentation.dto.request;

import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import com.example.dvely.domainbinding.domain.value.DomainType;
import com.example.dvely.domainbinding.domain.value.VerificationMethod;
import jakarta.validation.constraints.NotNull;

public record BindDomainRequest(
        @NotNull DomainType type,
        String label,
        String hostname,
        VerificationMethod verificationMethod,
        DomainHostingTarget hostingTarget
) {
}
