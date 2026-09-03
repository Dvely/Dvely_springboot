package com.example.dvely.agent.infrastructure.llm;

import com.example.dvely.agent.application.port.out.LlmMessage;
import com.example.dvely.agent.application.port.out.LlmPort;
import com.example.dvely.agent.domain.value.AiModelOptions;
import com.example.dvely.agent.infrastructure.config.AiProperties;
import java.net.URI;
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

    /** Used when the configured endpoint cannot be parsed for a host. */
    static final String PROVIDER_NAME = "GLM";

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
                providerName(config.getBaseUrl()),
                config.getBaseUrl(),
                config,
                reasoningStyle(config.getBaseUrl()),
                attributionHeaders(config),
                aiProperties.getRetry()
        );
    }

    /**
     * Which gateway's thinking spelling to use, decided by the endpoint actually configured.
     *
     * <p>OpenRouter and Z.ai are wire-compatible for everything except this one parameter, and each
     * ignores the other's spelling in silence rather than rejecting it — so picking the wrong one
     * produces a request that is accepted and simply does not think, which is indistinguishable
     * from one that did. Derived from the URL for the same reason the provider name is: the
     * deployment declares its gateway once, in {@code base-url}, and everything else follows.</p>
     */
    static LlmRequestOptions.ReasoningStyle reasoningStyle(String baseUrl) {
        String host = hostOf(baseUrl);
        boolean zai = host != null && (host.equals("z.ai") || host.endsWith(".z.ai"));
        return zai
                ? LlmRequestOptions.ReasoningStyle.ZAI_THINKING
                : LlmRequestOptions.ReasoningStyle.OPENROUTER_REASONING;
    }

    /** Host of the configured endpoint, or null when it cannot be parsed. */
    private static String hostOf(String baseUrl) {
        try {
            String host = URI.create(baseUrl).getHost();
            return host == null || host.isBlank() ? null : host;
        } catch (IllegalArgumentException e) {
            // A malformed base-url is a configuration error the request itself will surface; it
            // must not turn into a crash while choosing a dialect or building an error message.
            return null;
        }
    }

    /**
     * The name that reaches the user in a provider error, derived from the endpoint actually
     * configured — "GLM(openrouter.ai)", "GLM(api.z.ai)".
     *
     * <p>Derived rather than fixed because the whole point of naming the gateway is to send an
     * operator to the dashboard where a missing key or an empty balance is actually fixed. A
     * hard-coded "GLM(OpenRouter)" does the opposite the moment {@code base-url} is repointed at
     * Z.ai — 2026-09-03 실측으로 Z.ai 잔액 부족 응답이 "GLM(OpenRouter) 크레딧이 부족" 으로
     * 나오는 것을 확인했다.</p>
     */
    static String providerName(String baseUrl) {
        String host = hostOf(baseUrl);
        return host == null ? PROVIDER_NAME : PROVIDER_NAME + "(" + host + ")";
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
