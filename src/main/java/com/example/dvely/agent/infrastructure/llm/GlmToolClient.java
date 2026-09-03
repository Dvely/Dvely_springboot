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

/**
 * The tool-calling half of {@link GlmClient} — what the CODE agent's loop runs GLM through.
 *
 * <p>OpenRouter returns OpenAI-shaped {@code tool_calls}, so the transcript CodeAgentService builds
 * for GLM is byte-for-byte the OpenAI one; that is why the two share a loop rather than each having
 * their own.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GlmToolClient implements LlmToolPort {

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
                GlmClient.endpoint(aiProperties), systemPrompt, messages, tools, modelOptions);
    }
}
