package com.example.dvely.agent.infrastructure.llm;

import com.example.dvely.agent.application.port.out.LlmToolPort;
import com.example.dvely.agent.application.port.out.LlmToolResponse;
import com.example.dvely.agent.application.port.out.ToolDefinition;
import com.example.dvely.agent.domain.value.AiModelOptions;
import com.example.dvely.agent.infrastructure.config.AiProperties;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiToolClient implements LlmToolPort {

    private static final String API_URL = "https://api.openai.com/v1/chat/completions";

    private final AiProperties aiProperties;

    public LlmToolResponse completeWithTools(
            String systemPrompt,
            List<Map<String, Object>> messages,
            List<ToolDefinition> tools) {
        return completeWithTools(systemPrompt, messages, tools, AiModelOptions.defaults());
    }

    @Override
    public LlmToolResponse completeWithTools(
            String systemPrompt,
            List<Map<String, Object>> messages,
            List<ToolDefinition> tools,
            AiModelOptions modelOptions) {
        return OpenAiCompatibleChat.completeWithTools(
                endpoint(), systemPrompt, messages, tools, modelOptions);
    }

    private OpenAiCompatibleChat.Endpoint endpoint() {
        return new OpenAiCompatibleChat.Endpoint(
                OpenAiClient.PROVIDER_NAME,
                API_URL,
                aiProperties.getOpenai(),
                LlmRequestOptions.ReasoningStyle.REASONING_EFFORT,
                Map.of(),
                aiProperties.getRetry()
        );
    }
}
