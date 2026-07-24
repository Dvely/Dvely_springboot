package com.example.dvely.domainbinding.application.command.dto;

import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import com.example.dvely.domainbinding.domain.value.DomainType;
import com.example.dvely.domainbinding.domain.value.VerificationMethod;

/**
 * {@code taskId} (design ad-audit-log-design.md F7/ADR-A8, issue #74) is the sole signal this
 * command layer has for "was this an Agent-driven bind, or a direct user action" — the HTTP
 * controller always passes {@code null} (it never sees a taskId; see
 * {@code DomainBindingController}), while {@link com.example.dvely.agent.application.service.DomainBindAgentService}
 * passes the Agent task's real id. Added as a trailing component rather than changing the existing
 * 4-arg constructor's meaning, so no other call site needs to change.
 */
public record BindDomainCommand(
        DomainType type,
        String label,
        String hostname,
        VerificationMethod verificationMethod,
        DomainHostingTarget hostingTarget,
        String taskId
) {
    public BindDomainCommand(DomainType type,
                             String label,
                             String hostname,
                             VerificationMethod verificationMethod) {
        this(type, label, hostname, verificationMethod, DomainHostingTarget.GITHUB_PAGES, null);
    }

    public BindDomainCommand(DomainType type,
                             String label,
                             String hostname,
                             VerificationMethod verificationMethod,
                             DomainHostingTarget hostingTarget) {
        this(type, label, hostname, verificationMethod, hostingTarget, null);
    }

    public BindDomainCommand {
        hostingTarget = hostingTarget == null ? DomainHostingTarget.GITHUB_PAGES : hostingTarget;
    }
}
