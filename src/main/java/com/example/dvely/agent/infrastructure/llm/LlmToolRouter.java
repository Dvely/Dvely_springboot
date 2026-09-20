package com.example.dvely.agent.infrastructure.llm;

import com.example.dvely.agent.application.port.out.LlmToolPort;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.aiaccount.application.service.UserAiKeyResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link LlmRouter}'s counterpart for the CODE step's tool loop.
 *
 * <p>Separate from that one because the two ports are separate: a finished answer and a round of
 * tool calls are different shapes. The key resolution is identical, and deliberately so — there is
 * one rule about whose key pays, and it holds for planning, chat and code generation alike.</p>
 *
 * <p>Coding agents are absent on purpose: they do not run through our tool loop at all. The
 * vendor's CLI owns that loop, so {@code CodeAgentService} hands {@code CLAUDE_CODE}/{@code CODEX}
 * to the workspace bridge before it ever reaches a router.</p>
 */
@Component
@RequiredArgsConstructor
public class LlmToolRouter {

    private final ClaudeToolClient claudeToolClient;
    private final OpenAiToolClient openAiToolClient;
    private final GlmToolClient    glmToolClient;
    private final UserAiKeyResolver userAiKeyResolver;

    public LlmToolPort route(AiProvider provider, Long userId) {
        String apiKey = userAiKeyResolver.require(userId, provider);
        return switch (provider.credentialVendor()) {
            case ANTHROPIC -> (system, messages, tools, options) ->
                    claudeToolClient.completeWithTools(system, messages, tools, options, apiKey);
            case OPENAI -> (system, messages, tools, options) ->
                    openAiToolClient.completeWithTools(system, messages, tools, options, apiKey);
            case GLM -> (system, messages, tools, options) ->
                    glmToolClient.completeWithTools(system, messages, tools, options, apiKey);
            case CLAUDE_CODE, CODEX -> throw new IllegalStateException(
                    "credentialVendor() 가 코딩 에이전트를 반환했습니다: " + provider);
        };
    }
}
