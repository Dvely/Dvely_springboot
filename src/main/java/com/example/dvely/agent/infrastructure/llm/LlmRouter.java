package com.example.dvely.agent.infrastructure.llm;

import com.example.dvely.agent.application.port.out.LlmPort;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.aiaccount.application.service.UserAiKeyResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LlmRouter {

    private final ClaudeClient claudeClient;
    private final OpenAiClient openAiClient;
    private final GlmClient    glmClient;
    private final UserAiKeyResolver userAiKeyResolver;

    /**
     * A port already bound to the calling user's own key.
     *
     * <p>The key is resolved here rather than inside each client so that no call site can construct
     * an LLM call without one — the deployment holds no key to fall back to (#364).</p>
     *
     * <p>Coding agents route here too, unlike before. {@code CLAUDE_CODE} has no chat-completions
     * endpoint of its own, but the user's Anthropic key is exactly what its CLI authenticates with,
     * so planning and chat run on that same credential while the CODE step still hands the work to
     * the vendor's binary. Throwing instead — which is what this did — left the coding agents with
     * no way to reach DECISION or CHAT at all, so a user who picked one could not get past the
     * first step of any request.</p>
     */
    public LlmPort route(AiProvider provider, Long userId) {
        String apiKey = userAiKeyResolver.require(userId, provider);
        return switch (provider.credentialVendor()) {
            case ANTHROPIC -> (system, messages, options) ->
                    claudeClient.complete(system, messages, options, apiKey);
            case OPENAI -> (system, messages, options) ->
                    openAiClient.complete(system, messages, options, apiKey);
            case GLM -> (system, messages, options) ->
                    glmClient.complete(system, messages, options, apiKey);
            // credentialVendor() maps every execution mode onto one of the three above, so this
            // arm is unreachable; it exists because the switch must stay exhaustive if a provider
            // is added without deciding which vendor credential signs it.
            case CLAUDE_CODE, CODEX -> throw new IllegalStateException(
                    "credentialVendor() 가 코딩 에이전트를 반환했습니다: " + provider);
        };
    }
}
