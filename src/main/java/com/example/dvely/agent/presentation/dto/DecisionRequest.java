package com.example.dvely.agent.presentation.dto;

import com.example.dvely.agent.domain.value.AiProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DecisionRequest(
        @NotBlank String    content,
        @NotNull  AiProvider aiProvider,
        Long                projectId      // optional: 기존 프로젝트 수정 시
) {}
