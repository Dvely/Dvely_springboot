package com.example.dvely.agent.application.dto;

import java.time.Instant;

public record AgentTask(
        String     taskId,
        TaskStatus status,
        String     previewUrl,
        String     summary,
        String     error,
        String     question,
        Instant    createdAt
) {
    public AgentTask withStatus(TaskStatus status, String previewUrl, String summary, String error) {
        return new AgentTask(taskId, status, previewUrl, summary, error, null, createdAt);
    }

    public AgentTask withWaitingInput(String question) {
        return new AgentTask(taskId, TaskStatus.WAITING_INPUT, previewUrl, summary, error, question, createdAt);
    }
}
