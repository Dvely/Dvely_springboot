package com.example.dvely.provisioning.application.result;

import com.example.dvely.provisioning.domain.model.ProvisionedDatabase;
import java.time.LocalDateTime;

/**
 * 프로비저닝 자원 조회 결과. password 는 계약상 조회에선 항상 null 이라 여기 담지 않는다.
 * 생성 직후 1회 노출은 별도 경로(CreateResult)로만 전달한다.
 */
public record ProvisionedDatabaseResult(
        Long databaseId,
        Long projectId,
        String method,
        String engine,
        String origin,
        String status,
        String host,
        Integer port,
        String database,
        String username,
        LocalDateTime expiresAt,
        String errorCode,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ProvisionedDatabaseResult from(ProvisionedDatabase d) {
        return new ProvisionedDatabaseResult(
                d.getId(), d.getProjectId(), d.getMethod().name(), d.getEngine().name(),
                d.getOrigin().name(), d.getStatus().name(), d.getHost(), d.getPort(), d.getDatabaseName(),
                d.getUsername(), d.getExpiresAt(),
                d.getFailureCode() == null ? null : d.getFailureCode().name(),
                d.getErrorMessage(), d.getCreatedAt(), d.getUpdatedAt());
    }
}
