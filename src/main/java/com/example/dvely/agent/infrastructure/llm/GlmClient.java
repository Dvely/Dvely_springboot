package com.example.dvely.agent.infrastructure.llm;

import com.example.dvely.agent.application.port.out.LlmMessage;
import com.example.dvely.agent.application.port.out.LlmPort;
import com.example.dvely.agent.domain.value.AiModelOptions;
import com.example.dvely.agent.infrastructure.config.AiProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * GLM, called through OpenRouter's OpenAI-compatible chat-completions endpoint.
 *
 * <p>There is no GLM-specific wire code here on purpose: OpenRouter speaks OpenAI's format, so this
 * client is the endpoint description and nothing else — {@link OpenAiCompatibleChat} does the
 * work.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GlmClient implements LlmPort {

    /**
     * The name that reaches the user in a provider error. It names OpenRouter as well as GLM
     * because that is where a missing key or an empty balance actually has to be fixed — being told
     * "GLM 크레딧이 부족합니다" would send an operator to the wrong dashboard.
     */
    static final String PROVIDER_NAME = "GLM(OpenRouter)";

    private final AiProperties aiProperties;

    @Override
    public String complete(String systemPrompt, List<LlmMessage> messages, AiModelOptions modelOptions) {
        return OpenAiCompatibleChat.complete(endpoint(aiProperties), systemPrompt, messages, modelOptions);
    }

    /**
     * Shared with {@link GlmToolClient} so the two cannot drift apart in which URL they post to or
     * which headers they send.
     */
    static OpenAiCompatibleChat.Endpoint endpoint(AiProperties aiProperties) {
        AiProperties.Glm config = aiProperties.getGlm();
        return new OpenAiCompatibleChat.Endpoint(
                PROVIDER_NAME,
                config.getBaseUrl(),
                config,
                LlmRequestOptions.ReasoningStyle.OPENROUTER_REASONING,
                attributionHeaders(config)
        );
    }

    /** OpenRouter's optional attribution headers, sent only when the deployment configured them. */
    private static Map<String, String> attributionHeaders(AiProperties.Glm config) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (config.getReferer() != null && !config.getReferer().isBlank()) {
            headers.put("HTTP-Referer", config.getReferer().trim());
        }
        if (config.getTitle() != null && !config.getTitle().isBlank()) {
            headers.put("X-Title", config.getTitle().trim());
        }
        return Map.copyOf(headers);
    }
}
