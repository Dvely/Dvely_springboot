package com.example.dvely.provisioning.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "DB 생성 응답. method 에 따라 형태가 갈리지 않고 항상 이 구조. "
        + "LOCAL 은 requiresApproval=false 로 database 를 채우고(password 포함), "
        + "RDS/DOCKER 는 requiresApproval=true 로 taskId/approvalIds 를 채운다.")
public record CreateDatabaseResponse(
        @Schema(description = "승인이 필요한 방식인지. LOCAL=false, RDS/DOCKER=true") boolean requiresApproval,
        @Schema(description = "생성된 DB(password 포함, 이때 한 번만). requiresApproval=false 일 때만 값", nullable = true)
        CreatedDatabase database,
        @Schema(description = "승인 태스크 ID. requiresApproval=true 일 때만 값", nullable = true) String taskId,
        @Schema(description = "승인 ID 목록") List<Long> approvalIds
) {
    @Schema(description = "생성 직후 1회 노출용. password 를 포함한다 — 이후 조회는 항상 null.")
    public record CreatedDatabase(
            Long databaseId,
            String method,
            String engine,
            String status,
            String host,
            Integer port,
            String database,
            String username,
            @Schema(description = "DB 비밀번호. 이 응답에서만 노출됩니다.") String password,
            OffsetDateTime expiresAt
    ) {}
}
