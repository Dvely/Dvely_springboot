package com.example.dvely.deployment.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "배포 로그 응답")
public record DeploymentLogsResponse(
        @Schema(description = "배포 이력 ID") Long historyId,
        @Schema(description = "GitHub Actions workflow run ID. null이면 run_id가 아직 기록되지 않은 상태") Long workflowRunId,
        @Schema(description = "Job 목록. 각 Job의 step별 실행 상태 포함") List<JobResponse> jobs,
        @Schema(description = "첫 번째 Job의 전체 로그 텍스트. 각 줄은 타임스탬프로 시작") String logText
) {
    @Schema(description = "GitHub Actions Job 정보")
    public record JobResponse(
            @Schema(description = "Job ID") Long jobId,
            @Schema(description = "Job 이름") String name,
            @Schema(description = "실행 상태: queued | in_progress | completed") String status,
            @Schema(description = "완료 결과: success | failure | cancelled | null(미완료)") String conclusion,
            @Schema(description = "Step 목록") List<StepResponse> steps
    ) {}

    @Schema(description = "GitHub Actions Step 정보")
    public record StepResponse(
            @Schema(description = "Step 번호") int number,
            @Schema(description = "Step 이름") String name,
            @Schema(description = "실행 상태: queued | in_progress | completed") String status,
            @Schema(description = "완료 결과: success | failure | cancelled | null(미완료)") String conclusion
    ) {}
}
