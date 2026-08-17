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

    // templateType 은 담지 않는다. 프레임워크 힌트로 쓰이던 유일한 자리였는데, 그 필드가 담는
    // 것은 프레임워크가 아니라 사용자가 고른 콘텐츠 템플릿이라 어휘가 맞지 않았다.
    record Context(
            String userToken,
            Long projectId,
            String sourceRepository,
            String deploymentRepository,
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
