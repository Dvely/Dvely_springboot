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

    private AiProperties.Provider configOf(AiProvider provider) {
        return switch (provider) {
            case ANTHROPIC -> aiProperties.getAnthropic();
            case OPENAI -> aiProperties.getOpenai();
            case GLM -> aiProperties.getGlm();
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
