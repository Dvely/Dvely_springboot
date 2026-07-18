package com.example.dvely.project.application.result;

public record ProjectChatSettingsResult(
        Long projectId,
        boolean changeApprovalRequired,
        boolean deploymentApprovalRequired,
        boolean domainApprovalRequired,
        boolean infraApprovalRequired,
        boolean resultApprovalRequired
) {
}
