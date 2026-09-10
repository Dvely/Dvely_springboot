package com.example.dvely.agent.application.service;

import com.example.dvely.agent.domain.value.AiModelOptions;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.domain.value.ThinkingLevel;
import com.example.dvely.agent.infrastructure.config.AiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Turns what a client asked for into the settings a task will actually run under, rejecting
 * anything the deployment has not declared acceptable.
 *
 * <p>Resolution happens once, at plan creation, so the answer is decided while there is still a
 * caller to return an error to — a rejected model surfaces as a 400 on the request that named it,
 * rather than as a failed task minutes later.</p>
 */
@Component
@RequiredArgsConstructor
public class AiModelOptionsResolver {

    private final AiProperties aiProperties;

    public AiModelOptions resolve(AiProvider provider, String requestedModel, ThinkingLevel requestedThinking) {
        if (provider.isCodingAgent()) {
            return resolveForCodingAgent(provider, requestedModel, requestedThinking);
        }
        AiProperties.Provider config = configOf(provider);
        String model = requestedModel == null || requestedModel.isBlank()
                ? config.getModel()
                : requestedModel.trim();

        if (!config.allows(model)) {
            throw new IllegalArgumentException(
                    "지원하지 않는 모델입니다: " + model + " (" + provider + " 사용 가능 모델: "
                            + availableModels(config) + ")"
            );
        }

        ThinkingLevel thinking = requestedThinking == null ? ThinkingLevel.OFF : requestedThinking;
        if (thinking.isEnabled() && !config.supportsThinking(model)) {
            throw new IllegalArgumentException(
                    "이 모델은 thinking을 지원하지 않습니다: " + model
                            + " (thinking 가능 모델: " + thinkingModels(config) + ")"
            );
        }

        return new AiModelOptions(model, thinking);
    }

    /**
     * A coding agent's model and reasoning depth belong to the vendor's CLI, not to us.
     *
     * <p>A request that names one is rejected rather than ignored: accepting it silently would tell
     * the user they chose a model when nothing carried that choice anywhere.</p>
     */
    private AiModelOptions resolveForCodingAgent(AiProvider provider,
                                                 String requestedModel,
                                                 ThinkingLevel requestedThinking) {
        if (requestedModel != null && !requestedModel.isBlank()) {
            throw new IllegalArgumentException(
                    "코딩 에이전트는 모델을 지정할 수 없습니다: " + provider + " (CLI 가 정합니다)");
        }
        if (requestedThinking != null && requestedThinking.isEnabled()) {
            throw new IllegalArgumentException(
                    "코딩 에이전트는 thinking 을 지정할 수 없습니다: " + provider + " (CLI 가 정합니다)");
        }
        return AiModelOptions.defaults();
    }

    private AiProperties.Provider configOf(AiProvider provider) {
        return switch (provider) {
            case ANTHROPIC -> aiProperties.getAnthropic();
            case OPENAI -> aiProperties.getOpenai();
            case GLM -> aiProperties.getGlm();
            // Unreachable: resolve() branches before this. Kept so that adding a caller which
            // forgets that branch fails here rather than resolving against the wrong config.
            case CLAUDE_CODE, CODEX -> throw new IllegalArgumentException(
                    "코딩 에이전트 제공자는 qeploy.ai.* 설정을 갖지 않습니다: " + provider);
        };
    }

    private String availableModels(AiProperties.Provider config) {
        StringBuilder models = new StringBuilder(config.getModel());
        config.getAllowedModels().stream()
                .filter(allowed -> !allowed.equals(config.getModel()))
                .forEach(allowed -> models.append(", ").append(allowed));
        return models.toString();
    }

    private String thinkingModels(AiProperties.Provider config) {
        return config.getThinkingModels().isEmpty()
                ? "없음"
                : String.join(", ", config.getThinkingModels());
    }
}
