package com.example.dvely.domainbinding.application.port.out;

import com.example.dvely.domainbinding.domain.value.CertificateStatus;
import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import java.time.LocalDate;

public interface DomainHostingAdapter {

    DomainHostingTarget target();

    String resolveDnsTarget(Context context);

    void bind(Context context, String hostname);

    VerificationStatus verify(Context context, String hostname);

    void unbind(Context context, String hostname);

    record Context(
            String userToken,
            Long projectId,
            String sourceRepository,
            String deploymentRepository,
            String templateType,
            String currentVersion,
            String currentUrl
    ) {
    }

    record VerificationStatus(
            boolean domainConfigured,
            boolean httpsEnforced,
            CertificateStatus certificateStatus,
            LocalDate certificateExpiresAt
    ) {
    }
}
