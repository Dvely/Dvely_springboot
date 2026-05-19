package com.example.dvely.agent.application.dto;

import com.example.dvely.agent.domain.value.AiProvider;

import java.util.List;

public record AgentPlan(
        List<AgentStep> steps,
        String          reasoning,
        AiProvider      aiProvider,
        Long            projectId      // null = 신규 프로젝트, non-null = 기존 프로젝트 수정
) {}
