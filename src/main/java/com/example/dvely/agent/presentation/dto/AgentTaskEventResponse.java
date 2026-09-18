package com.example.dvely.agent.presentation.dto;

import com.example.dvely.agent.application.dto.TaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "에이전트 진행 상황 이벤트 하나")
public record AgentTaskEventResponse(

        @Schema(description = "이벤트 id. 재연결 시 afterEventId 로 되돌려 보내면 끊긴 지점부터 이어 받는다")
        Long eventId,

        String taskId,

        @Schema(description = """
                이벤트 종류. 태스크 생명주기(CREATED/QUEUED/STARTED/COMPLETED/FAILED/CANCELLED/
                WAITING_APPROVAL/WAITING_INPUT/WAITING_RESULT_APPROVAL/REPLANNED/INPUT_RECEIVED/
                RETRY_QUEUED) 와 스텝 진행(STEP_STARTED/STEP_COMPLETED) 두 갈래다.
                모르는 종류가 와도 무시하고 넘어갈 것 — 종류는 늘어난다.
                """,
                example = "STEP_STARTED")
        String type,

        @Schema(description = "이벤트 시점의 태스크 상태. FAILED 면 실패다 — 진행/실패 구분은 이 값으로 한다")
        TaskStatus status,

        @Schema(description = "사용자에게 보여줄 한 줄", example = "코드를 만들고 있습니다 (1/2)")
        String message,

        @Schema(description = "스텝 이벤트일 때 1-based 순번. 생명주기 이벤트에는 없다", example = "1", nullable = true)
        Integer stepIndex,

        @Schema(description = "스텝 이벤트일 때 계획의 총 스텝 수", example = "2", nullable = true)
        Integer stepTotal,

        @Schema(description = "스텝 이벤트일 때 그 스텝의 에이전트 종류",
                example = "CODE", nullable = true)
        String agentType,

        LocalDateTime createdAt
) {
}
