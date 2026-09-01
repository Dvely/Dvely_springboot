package com.example.dvely.provisioning.application.result;

import java.util.List;

/**
 * 생성 요청 응답. method 에 따라 형태가 갈리지 않도록 항상 같은 구조다(FE 합의).
 *
 * LOCAL(즉시): requiresApproval=false, database 채움(password 포함), taskId/approvalIds 는 null/[].
 * RDS·DOCKER(승인): requiresApproval=true, database=null, taskId/approvalIds 채움.
 *
 * LOCAL 이 나중에 승인을 거치게 바뀌어도 requiresApproval 만 true 되면 되고 형태는 안 깨진다.
 */
public record ProvisionSubmitResult(
        boolean requiresApproval,
        CreatedDatabase database,   // requiresApproval=false 일 때만 값
        String taskId,              // requiresApproval=true 일 때만 값
        List<Long> approvalIds
) {
    /** 생성 직후 1회 노출용 — password 를 포함한다. 조회 결과와 달리 이때만 준다. */
    public record CreatedDatabase(
            Long databaseId,
            String method,
            String engine,
            String status,
            String host,
            Integer port,
            String database,
            String username,
            String password,     // 이 응답에서만. 이후 조회는 항상 null
            java.time.LocalDateTime expiresAt
    ) {}

    public static ProvisionSubmitResult immediate(CreatedDatabase db) {
        return new ProvisionSubmitResult(false, db, null, List.of());
    }
}
