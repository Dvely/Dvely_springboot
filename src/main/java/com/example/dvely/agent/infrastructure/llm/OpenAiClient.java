package com.example.dvely.agent.infrastructure.llm;

import com.example.dvely.agent.application.port.out.LlmMessage;
import com.example.dvely.agent.application.port.out.LlmPort;
import com.example.dvely.agent.domain.value.AiModelOptions;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.config.AiProperties;
import com.example.dvely.agent.infrastructure.usage.LlmUsageRecorder;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiClient implements LlmPort {

    private static final String API_URL = "https://api.openai.com/v1/chat/completions";
    static final String PROVIDER_NAME = "OpenAI";

    private final AiProperties aiProperties;
    private final LlmUsageRecorder llmUsageRecorder;

    @Override
    public String complete(String systemPrompt, List<LlmMessage> messages, AiModelOptions modelOptions) {
        OpenAiCompatibleChat.Completion completion =
                OpenAiCompatibleChat.complete(endpoint(), systemPrompt, messages, modelOptions);
        llmUsageRecorder.record(AiProvider.OPENAI, completion.model(), completion.usage());
        return completion.content();
    }

    private OpenAiCompatibleChat.Endpoint endpoint() {
        return new OpenAiCompatibleChat.Endpoint(
                PROVIDER_NAME,
                API_URL,
                aiProperties.getOpenai(),
                LlmRequestOptions.ReasoningStyle.REASONING_EFFORT,
                Map.of(),
                aiProperties.getRetry()
        );
    }
}
