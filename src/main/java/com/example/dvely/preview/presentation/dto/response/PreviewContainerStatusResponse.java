package com.example.dvely.preview.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "Preview 컨테이너 상태 응답")
public record PreviewContainerStatusResponse(
        @Schema(description = "Preview 세션 ID") String sessionId,
        @Schema(description = "프로젝트 ID") Long projectId,
        @Schema(description = "연결된 agent 태스크 ID") String taskId,
        @Schema(description = "세션 상태: ACTIVE | CLOSED | EXPIRED") String sessionStatus,
        @Schema(description = "Docker 컨테이너 실행 여부") boolean containerRunning,
        @Schema(description = "OOM(메모리 초과) kill 여부. 컨테이너가 없거나 확인 불가하면 null") Boolean oomKilled,
        @Schema(description = "컨테이너 종료 코드. 실행 중이거나 확인 불가하면 null") Long exitCode,
        @Schema(description = "컨테이너 기동 시각. 없으면 null") LocalDateTime startedAt,
        @Schema(description = "세션 만료 예정 시각") LocalDateTime expiresAt,
        @Schema(description = "리소스 사용량. 미실행이거나 stats 조회 실패(3초 초과 포함) 시 null")
        ResourceUsageResponse resources
) {
    @Schema(description = "컨테이너 리소스 사용량")
    public record ResourceUsageResponse(
            @Schema(description = "메모리 사용량(byte)") long memoryUsageBytes,
            @Schema(description = "메모리 제한(byte). 격리 정책값(1 GiB)과 동일") long memoryLimitBytes,
            @Schema(description = "메모리 사용률(%), 소수 1자리") double memoryUsagePercent,
            @Schema(description = "CPU 사용률(%), 소수 1자리. cpu/precpu 델타 기반") double cpuPercent
    ) {
    }
}
