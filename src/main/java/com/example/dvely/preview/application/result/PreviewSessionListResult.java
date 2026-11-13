package com.example.dvely.preview.application.result;

import com.example.dvely.agent.application.dto.TaskStatus;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 내 살아 있는 프리뷰 목록(#651). 상한에 걸린 사용자가 무엇을 닫을지 고르는 데 쓴다.
 *
 * @param maxLivePreviews 사용자 상한. 서버 전체 수는 다른 사용자의 활동이라 싣지 않는다
 * @param items           오래 안 본 순 — 상한 회수가 고르는 순서와 같다
 */
public record PreviewSessionListResult(int maxLivePreviews, List<Item> items) {

    public PreviewSessionListResult {
        items = List.copyOf(items);
    }

    /**
     * @param status             ACTIVE 또는 PROVISIONING
     * @param projectName        프로젝트를 찾지 못하면 null — 그래도 항목은 보여 준다(닫을 수 있어야 한다)
     * @param taskId             null 이면 작업 없이 띄운 프로젝트 프리뷰
     * @param taskStatus         프로젝트 프리뷰이거나 런 행이 없으면 null
     * @param closeBlockedReason closable 이 false 일 때만. 작업 상태 이름 또는 PROVISIONING
     */
    public record Item(
            String sessionId,
            String status,
            Long projectId,
            String projectName,
            String taskId,
            TaskStatus taskStatus,
            boolean closable,
            String closeBlockedReason,
            LocalDateTime lastAccessedAt,
            LocalDateTime expiresAt,
            LocalDateTime createdAt
    ) {
    }
}
