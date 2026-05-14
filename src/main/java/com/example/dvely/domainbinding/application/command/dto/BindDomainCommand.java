package com.example.dvely.domainbinding.application.command.dto;

import com.example.dvely.domainbinding.domain.value.DomainType;
import com.example.dvely.domainbinding.domain.value.VerificationMethod;

public record BindDomainCommand(
        DomainType type,
        String label,
        String hostname,
        VerificationMethod verificationMethod
) {
}
