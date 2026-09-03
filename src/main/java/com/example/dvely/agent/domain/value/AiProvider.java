package com.example.dvely.agent.domain.value;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "사용할 AI 제공자. ANTHROPIC = Claude, OPENAI = GPT, GLM = OpenRouter 경유 GLM")
public enum AiProvider {
    ANTHROPIC,
    OPENAI,
    GLM
}
