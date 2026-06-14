package com.example.dvely.domainbinding.presentation.dto.response;

import java.util.List;

public record DomainBindingSubmissionResponse(
        String taskId,
        String status,
        List<Long> approvalIds
) {
}
