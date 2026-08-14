package com.example.dvely.agent.domain.value;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * How much reasoning the model should spend before answering.
 *
 * <p>Deliberately a level rather than a raw provider parameter: Anthropic expresses this as a
 * token budget for extended thinking and OpenAI as a {@code reasoning_effort} string, and a client
 * should not have to know which provider it is talking to in order to ask for "more thought".</p>
 */
@Schema(description = "모델의 사고 깊이. OFF = 사용 안 함(기본)")
public enum ThinkingLevel {
    OFF,
    LOW,
    MEDIUM,
    HIGH;

    public boolean isEnabled() {
        return this != OFF;
    }
}
