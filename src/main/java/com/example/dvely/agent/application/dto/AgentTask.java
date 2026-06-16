package com.example.dvely.agent.application.dto;

import java.time.Instant;

public record AgentTask(
        String     taskId,
        Long       ownerUserId,
        Long       projectId,
        Long       conversationId,
        TaskStatus status,
        String     previewUrl,
        String     summary,
        String     error,
        String     question,
        Instant    createdAt
) {
    public AgentTask withStatus(TaskStatus status, String previewUrl, String summary, String error) {
        return new AgentTask(
                taskId,
                ownerUserId,
                projectId,
                conversationId,
                status,
                previewUrl,
                summary,
                error,
                null,
                createdAt
        );
    }

    public AgentTask withWaitingInput(String question) {
        return new AgentTask(
                taskId,
                ownerUserId,
                projectId,
                conversationId,
                TaskStatus.WAITING_INPUT,
                previewUrl,
                summary,
                error,
                question,
                createdAt
        );
    }
}
