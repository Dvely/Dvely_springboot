package com.example.dvely.agent.application.dto;

import java.time.LocalDateTime;

/**
 * 진행 상황 이벤트 하나.
 *
 * <p>{@code stepIndex}/{@code stepTotal}/{@code agentType} 은 <b>스텝 이벤트에만</b> 있다
 * (STEP_STARTED / STEP_COMPLETED). 여태 이벤트는 태스크 생명주기뿐이라 코드 생성처럼 몇 분
 * 걸리는 스텝이 도는 동안 화면에 아무 변화가 없었고, 사용자는 진행 중인지 멈춘 건지 오류인지
 * 구분할 수 없었다.</p>
 */
public record AgentTaskEvent(
        Long eventId,
        String taskId,
        String type,
        TaskStatus status,
        String message,
        Integer stepIndex,
        Integer stepTotal,
        String agentType,
        LocalDateTime createdAt
) {
}
