package com.example.dvely.preview.presentation.dto.response;

import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.preview.application.result.PreviewSessionListResult;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code GET /api/v1/preview-sessions} — 내 살아 있는 프리뷰 목록(#651).
 *
 * <p>열 주소({@code accessToken}·{@code publicUrl})와 컨테이너 정보는 싣지 않는다. 유효한 주소는
 * {@code POST /preview-sessions/{id}/access} 만 발급한다.</p>
 */
public record PreviewSessionListResponse(int maxLivePreviews, List<Item> items) {

    public static PreviewSessionListResponse from(PreviewSessionListResult result) {
        return new PreviewSessionListResponse(result.maxLivePreviews(),
                result.items().stream().map(Item::from).toList());
    }

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

        static Item from(PreviewSessionListResult.Item item) {
            return new Item(item.sessionId(), item.status(), item.projectId(), item.projectName(), item.taskId(),
                    item.taskStatus(), item.closable(), item.closeBlockedReason(), item.lastAccessedAt(),
                    item.expiresAt(), item.createdAt());
        }
    }
}
