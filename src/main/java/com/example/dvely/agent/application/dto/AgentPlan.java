package com.example.dvely.agent.application.dto;

import com.example.dvely.agent.domain.value.AiModelOptions;
import com.example.dvely.agent.domain.value.AiProvider;

import java.util.List;

public record AgentPlan(
        List<AgentStep> steps,
        String          reasoning,
        AiProvider      aiProvider,
        Long            projectId,     // null = 신규 프로젝트, non-null = 기존 프로젝트 수정
        AiModelOptions  modelOptions   // 요청 시 확정된 모델/thinking 설정
) {

    /**
     * Plans are persisted as JSON and read back on the asynchronous path, so a plan written before
     * {@code modelOptions} existed deserializes with it absent. Normalising to
     * {@link AiModelOptions#defaults()} keeps those in-flight tasks running on server defaults
     * instead of failing on a null when their next step executes.
     */
    public AgentPlan {
        modelOptions = modelOptions == null ? AiModelOptions.defaults() : modelOptions;
    }

    /** Plans that take whatever the server is configured with — the shape every caller used before. */
    public AgentPlan(List<AgentStep> steps, String reasoning, AiProvider aiProvider, Long projectId) {
        this(steps, reasoning, aiProvider, projectId, AiModelOptions.defaults());
    }
}
