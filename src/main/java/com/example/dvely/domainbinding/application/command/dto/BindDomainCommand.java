package com.example.dvely.domainbinding.application.command.dto;

import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import com.example.dvely.domainbinding.domain.value.DomainType;
import com.example.dvely.domainbinding.domain.value.VerificationMethod;

public record BindDomainCommand(
        DomainType type,
        String label,
        String hostname,
        VerificationMethod verificationMethod,
        DomainHostingTarget hostingTarget
) {
    public BindDomainCommand(DomainType type,
                             String label,
                             String hostname,
                             VerificationMethod verificationMethod) {
        this(type, label, hostname, verificationMethod, DomainHostingTarget.GITHUB_PAGES);
    }

    public BindDomainCommand {
        hostingTarget = hostingTarget == null ? DomainHostingTarget.GITHUB_PAGES : hostingTarget;
    }
}
