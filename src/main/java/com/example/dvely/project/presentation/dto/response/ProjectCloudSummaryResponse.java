package com.example.dvely.project.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "Overview 프로젝트 클라우드 연결 요약")
public record ProjectCloudSummaryResponse(
        boolean configured,
        Long cloudConnectionId,
        String provider,
        String displayName,
        String region,
        String status,
        LocalDateTime lastCheckedAt
) {
}
