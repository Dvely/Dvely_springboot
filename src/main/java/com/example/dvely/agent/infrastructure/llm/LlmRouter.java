package com.example.dvely.agent.infrastructure.llm;

import com.example.dvely.agent.application.port.out.LlmPort;
import com.example.dvely.agent.domain.value.AiProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LlmRouter {

    private final ClaudeClient claudeClient;
    private final OpenAiClient openAiClient;

    public LlmPort route(AiProvider provider) {
        return switch (provider) {
            case ANTHROPIC -> claudeClient;
            case OPENAI    -> openAiClient;
        };
    }
}
