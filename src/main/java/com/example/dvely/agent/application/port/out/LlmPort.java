package com.example.dvely.agent.application.port.out;

import com.example.dvely.agent.domain.value.AiModelOptions;
import java.util.List;

public interface LlmPort {

    /** Runs on whatever the provider is configured with — for callers with no per-request settings. */
    default String complete(String systemPrompt, List<LlmMessage> messages) {
        return complete(systemPrompt, messages, AiModelOptions.defaults());
    }

    String complete(String systemPrompt, List<LlmMessage> messages, AiModelOptions modelOptions);
}
