package com.example.dvely.provisioning.domain.repository;

import java.time.LocalDateTime;

/**
 * 프로비저닝된 DB 목록 전용 읽기 모델. U6(#341) 6-5.
 *
 * <p><b>password 가 없다.</b> 그 컬럼은 MEDIUMTEXT + {@code @Convert(AesEncryptor)} 라 엔티티로 읽으면
 * 행마다 AES 복호화가 도는데, 조회 응답({@code ProvisionedDatabaseResult})은 계약상 비밀번호를 담지
 * 않는다 — 생성 직후 1회 노출만 별도 경로다. 즉 복호화해서 곧바로 버리고 있었다.</p>
 *
 * <p>여기에 password 필드를 추가하지 말 것. 목록 응답에 비밀번호가 실릴 경로가 생긴다.</p>
 */
public record ProvisionedDatabaseListView(
        Long id,
        Long projectId,
        String method,
        String engine,
        String origin,
        String status,
        String host,
        Integer port,
        String databaseName,
        String username,
        LocalDateTime expiresAt,
        String failureCode,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
