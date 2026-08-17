package com.example.dvely.approval.application.result;

import java.time.LocalDateTime;

/**
 * @param input 승인할 때 값을 함께 받아야 하면 그 입력 스펙, 아니면 null. null 이면 FE 는 단순
 *              승인/거절 버튼만 그리면 된다.
 *
 *              결정 전에 결과물을 보여줄 프리뷰 주소는 여기 담지 않는다. GET /agent/tasks/{taskId}
 *              가 이미 previewUrl 을 내려주고 FE 도 그것으로 프리뷰를 띄우고 있어서, 같은 값을
 *              승인 응답에 또 넣으면 두 곳이 갈릴 여지만 생긴다.
 */
public record ApprovalResult(
        Long approvalId,
        Long projectId,
        Long conversationId,
        String taskId,
        String type,
        String status,
        String summary,
        ApprovalInput input,
        LocalDateTime createdAt,
        LocalDateTime decidedAt
) {
}
