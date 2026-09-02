package com.example.dvely.provisioning.application.result;

import java.util.List;

/**
 * EC2 서버 배포 요청 응답. 서버는 과금 자원이라 항상 승인을 거친다 — 즉시 만들어지지 않으므로
 * requiresApproval=true, serverId(대기 행)와 approvalIds 를 돌려준다(RDS submit 과 동형).
 */
public record ServerProvisionSubmitResult(
        boolean requiresApproval,
        Long serverId,
        List<Long> approvalIds
) {}
