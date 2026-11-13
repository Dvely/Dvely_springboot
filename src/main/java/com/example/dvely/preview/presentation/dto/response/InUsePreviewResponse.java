package com.example.dvely.preview.presentation.dto.response;

import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.preview.application.result.InUsePreview;
import java.util.List;

/** 진행 중이거나 결정을 기다리는 작업이 쓰고 있어 닫지 않은 프리뷰 하나(#650). */
public record InUsePreviewResponse(String sessionId, Long projectId, String taskId, TaskStatus taskStatus) {

    public static InUsePreviewResponse from(InUsePreview preview) {
        return new InUsePreviewResponse(preview.sessionId(), preview.projectId(), preview.taskId(), preview.taskStatus());
    }

    public static List<InUsePreviewResponse> from(List<InUsePreview> previews) {
        return previews.stream().map(InUsePreviewResponse::from).toList();
    }
}
