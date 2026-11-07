package com.example.dvely.agent.infrastructure.persistence.repository;

/**
 * {@code GET /agent/tasks/{taskId}} 가 run 행에서 읽는 <b>전부</b>(#619).
 *
 * <p>FE 는 이 API 를 2초마다 폴링한다. 예전에는 같은 행을 엔티티로 네 번 읽었고(상태·실패·되묻기·답한 되묻기),
 * 그때마다 응답에 쓰지도 않는 {@code plan_json LONGTEXT} 까지 실어 왔다. 응답에 필요한 컬럼만 한 번에 읽는다.</p>
 */
public record AgentRunStatusView(
        String taskId,
        String status,
        String previewUrl,
        String summary,
        String error,
        String question,
        String failureLog,
        String suggestedFix,
        int attempt,
        int maxAttempts,
        String clarificationJson,
        String answeredClarificationJson
) {
}
