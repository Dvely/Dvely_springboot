package com.example.dvely.provisioning.application.result;

import com.example.dvely.provisioning.domain.repository.ProvisionedDatabaseListView;
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
    /**
     * U6 6-5: password 를 읽지 않는 읽기 모델에서 만든다. method·engine·origin·status·failureCode 는
     * DB 도 응답도 문자열이라 중간에 enum 으로 되돌리지 않는다 — 값은 예전과 같다.
     */
    public static ProvisionedDatabaseResult from(ProvisionedDatabaseListView d) {
        return new ProvisionedDatabaseResult(
                d.id(), d.projectId(), d.method(), d.engine(),
                d.origin(), d.status(), d.host(), d.port(), d.databaseName(),
                d.username(), d.expiresAt(),
                d.failureCode(),
                d.errorMessage(), d.createdAt(), d.updatedAt());
    }
}
