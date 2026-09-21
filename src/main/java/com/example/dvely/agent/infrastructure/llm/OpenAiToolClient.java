package com.example.dvely.agent.infrastructure.llm;

import com.example.dvely.agent.application.port.out.LlmToolResponse;
import com.example.dvely.agent.application.port.out.ToolDefinition;
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
public class OpenAiToolClient {

    private static final String API_URL = "https://api.openai.com/v1/chat/completions";

    private final AiProperties aiProperties;
    private final LlmUsageRecorder llmUsageRecorder;

    /** The key is the caller's own — see {@link ClaudeClient#complete} for why it is a parameter. */
    public LlmToolResponse completeWithTools(
            String systemPrompt,
            List<Map<String, Object>> messages,
            List<ToolDefinition> tools,
            AiModelOptions modelOptions,
            String apiKey) {
        LlmToolResponse response = OpenAiCompatibleChat.completeWithTools(
                endpoint(apiKey), systemPrompt, messages, tools, modelOptions);
        llmUsageRecorder.record(
                AiProvider.OPENAI, modelOptions.modelOr(aiProperties.getOpenai().getModel()), response.usage());
        return response;
    }

    private OpenAiCompatibleChat.Endpoint endpoint(String apiKey) {
        return new OpenAiCompatibleChat.Endpoint(
                OpenAiClient.PROVIDER_NAME,
                API_URL,
                apiKey,
                aiProperties.getOpenai(),
                LlmRequestOptions.ReasoningStyle.REASONING_EFFORT,
                Map.of(),
                aiProperties.getRetry()
        );
    }
}
