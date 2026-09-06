package com.example.dvely.agent.presentation.dto;

import com.example.dvely.agent.application.dto.TaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 대화의 현재 살아있는(비-terminal) 태스크 포인터. 새로고침 후 FE 가 이 taskId 로 {@code GET /tasks/{id}}
 * 를 불러 상태에 맞는 UI(WAITING_INPUT 이면 되묻기 폼 등)를 복구한다. 대화에 살아있는 태스크가 없으면
 * 이 응답 대신 204 가 온다.
 */
@Schema(description = "대화의 현재 진행/대기 중 태스크 포인터")
public record ActiveTaskResponse(

        @Schema(description = "태스크 ID. GET /tasks/{id} 로 상세를 조회한다", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        String taskId,

        @Schema(description = "현재 상태(WAITING_INPUT/WAITING_APPROVAL/RUNNING/QUEUED 등). terminal(DONE/FAILED/CANCELLED)은 오지 않는다", example = "WAITING_INPUT")
        TaskStatus status
) {}
