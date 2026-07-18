package com.example.dvely.agent.presentation.dto;

import com.example.dvely.agent.application.dto.TaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "에이전트 태스크 상태 응답")
public record TaskStatusResponse(

        @Schema(description = "태스크 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        String taskId,

        @Schema(description = """
                태스크 상태.
                - PENDING: 대기 중
                - WAITING_APPROVAL: 사용자 승인 대기
                - QUEUED: 실행 queue 대기
                - RETRY_WAIT: lease 만료 또는 재시도 대기
                - RUNNING: 실행 중
                - WAITING_INPUT: 사용자 입력 필요 (question 필드 확인 후 /input 호출)
                - WAITING_RESULT_APPROVAL: 마지막 CODE step 완료, 결과(preview+diff) 승인 대기 중 — RESULT 승인
                  approve 시 main 반영 후 재개, reject 시 CANCELLED
                - DONE: 완료
                - FAILED: 실패 (error 필드 참조)
                - CANCELLED: 취소
                """,
                example = "DONE")
        TaskStatus status,

        @Schema(description = "토큰 기반 프리뷰 gateway URL. CODE 스텝 완료 시에만 설정됨", example = "https://api.qeploy.com/api/v1/previews/101/token/", nullable = true)
        String previewUrl,

        @Schema(description = "작업 완료 요약. 배포 URL, 도메인 연결 결과 등 포함", nullable = true)
        String summary,

        @Schema(description = "실패 원인 메시지. status가 FAILED일 때 설정됨", nullable = true)
        String error,

        @Schema(description = "에이전트가 사용자에게 묻는 질문. status가 WAITING_INPUT일 때 설정됨. 응답은 /tasks/{taskId}/input으로 제출", nullable = true)
        String question,

        @Schema(description = "실패 로그의 마지막 일부", nullable = true)
        String failureLog,

        @Schema(description = "사용자에게 제안하는 최선의 수정안", nullable = true)
        String suggestedFix,

        @Schema(description = "현재 재시도 횟수")
        int attempt,

        @Schema(description = "최대 재시도 횟수")
        int maxAttempts,

        @Schema(description = "현재 상태에서 재시도 가능한지 여부")
        boolean retryable
) {}
