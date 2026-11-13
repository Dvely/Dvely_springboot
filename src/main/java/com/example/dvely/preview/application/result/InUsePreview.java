package com.example.dvely.preview.application.result;

import com.example.dvely.agent.application.dto.TaskStatus;

/** 진행 중이거나 결정을 기다리는 작업이 쓰고 있어 닫지 않은 프리뷰(#650). */
public record InUsePreview(String sessionId, Long projectId, String taskId, TaskStatus taskStatus) {
}
