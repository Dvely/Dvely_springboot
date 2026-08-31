package com.example.dvely.provisioning.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "프로비저닝된 DB 자원. password 는 조회 응답에서 항상 null(생성 응답에서만 1회 노출).")
public record ProvisionedDatabaseResponse(
        @Schema(description = "자원 ID") Long databaseId,
        @Schema(description = "프로젝트 ID") Long projectId,
        @Schema(description = "방식", allowableValues = {"LOCAL", "RDS", "DOCKER"}) String method,
        @Schema(description = "엔진", allowableValues = {"POSTGRESQL", "MYSQL"}) String engine,
        @Schema(description = "상태. 전이(PENDING·PROVISIONING) 있으면 폴링, 종료(READY·FAILED·EXPIRED)면 정지",
                allowableValues = {"PENDING", "PROVISIONING", "READY", "FAILED", "EXPIRED"}) String status,
        @Schema(description = "접속 호스트. READY 전에는 null", nullable = true) String host,
        @Schema(description = "접속 포트. READY 전에는 null", nullable = true) Integer port,
        @Schema(description = "DB 이름", nullable = true) String database,
        @Schema(description = "접속 사용자", nullable = true) String username,
        @Schema(description = "만료 시각. LOCAL 만 값, RDS/DOCKER 는 null. 오프셋 포함", nullable = true)
        OffsetDateTime expiresAt,
        @Schema(description = "실패 분류. 성공/진행 중이면 null. 모르는 값은 PROVIDER_ERROR 로 취급 가능(열린 문자열)", nullable = true)
        String errorCode,
        @Schema(description = "실패 상세", nullable = true) String errorMessage,
        @Schema(description = "생성 시각(오프셋 포함)") OffsetDateTime createdAt,
        @Schema(description = "수정 시각(오프셋 포함)") OffsetDateTime updatedAt
) {}
