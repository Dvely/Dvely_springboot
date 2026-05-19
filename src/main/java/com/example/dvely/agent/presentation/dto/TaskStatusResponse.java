package com.example.dvely.agent.presentation.dto;

import com.example.dvely.agent.application.dto.TaskStatus;

public record TaskStatusResponse(
        String     taskId,
        TaskStatus status,
        String     previewUrl,
        String     summary,
        String     error,
        String     question
) {}
