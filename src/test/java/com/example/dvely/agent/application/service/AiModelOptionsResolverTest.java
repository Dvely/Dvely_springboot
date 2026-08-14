package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dvely.agent.domain.value.AiModelOptions;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.domain.value.ThinkingLevel;
import com.example.dvely.agent.infrastructure.config.AiProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiModelOptionsResolverTest {

    private static final String DEFAULT_MODEL = "claude-opus-4-5-20251101";

    @Test
    void fallsBackToTheConfiguredModelAndNoThinkingWhenTheRequestSaysNothing() {
        AiModelOptions options = resolver(properties()).resolve(AiProvider.ANTHROPIC, null, null);

        assertThat(options.model()).isEqualTo(DEFAULT_MODEL);
        assertThat(options.thinking()).isEqualTo(ThinkingLevel.OFF);
    }

    @Test
    void acceptsTheConfiguredModelEvenWhenTheAllowListDoesNotRepeatIt() {
        // The configured model has to stay usable regardless of how allowed-models is filled in,
        // or an operator adding one entry would lock out every request that names no model.
        AiProperties properties = properties();
        properties.getAnthropic().setAllowedModels(List.of("claude-sonnet-5"));

        AiModelOptions options = resolver(properties).resolve(AiProvider.ANTHROPIC, DEFAULT_MODEL, null);

        assertThat(options.model()).isEqualTo(DEFAULT_MODEL);
    }

    @Test
    void acceptsAnExplicitlyAllowedModel() {
        AiProperties properties = properties();
        properties.getAnthropic().setAllowedModels(List.of("claude-sonnet-5"));

        AiModelOptions options = resolver(properties).resolve(AiProvider.ANTHROPIC, "claude-sonnet-5", null);

        assertThat(options.model()).isEqualTo("claude-sonnet-5");
    }

    @Test
    void rejectsAModelTheDeploymentHasNotAllowed() {
        // An unrestricted model parameter would let a request name anything at all, including
        // models that cost far more per call than this deployment budgeted for.
        assertThatThrownBy(() -> resolver(properties()).resolve(AiProvider.ANTHROPIC, "some-unknown-model", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("지원하지 않는 모델")
                .hasMessageContaining(DEFAULT_MODEL);
    }

    @Test
    void rejectsThinkingOnAModelThatDoesNotSupportIt() {
        // Rejected rather than silently dropped: a request that quietly ignores the setting looks
        // exactly like one that honoured it, so the caller would trust a control that does nothing.
        AiProperties properties = properties();
        properties.getOpenai().setThinkingModels(List.of());

        assertThatThrownBy(() -> resolver(properties).resolve(AiProvider.OPENAI, null, ThinkingLevel.HIGH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("thinking을 지원하지 않습니다");
    }

    @Test
    void acceptsThinkingOnAModelDeclaredCapableOfIt() {
        AiModelOptions options = resolver(properties())
                .resolve(AiProvider.ANTHROPIC, null, ThinkingLevel.HIGH);

        assertThat(options.thinking()).isEqualTo(ThinkingLevel.HIGH);
        assertThat(options.model()).isEqualTo(DEFAULT_MODEL);
    }

    @Test
    void thinkingOffNeedsNoModelSupport() {
        AiModelOptions options = resolver(properties())
                .resolve(AiProvider.OPENAI, null, ThinkingLevel.OFF);

        assertThat(options.thinking()).isEqualTo(ThinkingLevel.OFF);
        assertThat(options.model()).isEqualTo("gpt-4o");
    }

    @Test
    void trimsAModelNameAndTreatsBlankAsAbsent() {
        AiModelOptions options = resolver(properties()).resolve(AiProvider.ANTHROPIC, "   ", null);

        assertThat(options.model()).isEqualTo(DEFAULT_MODEL);
    }

    private AiModelOptionsResolver resolver(AiProperties properties) {
        return new AiModelOptionsResolver(properties);
    }

    private AiProperties properties() {
        AiProperties properties = new AiProperties();
        properties.getAnthropic().setThinkingModels(List.of(DEFAULT_MODEL));
        return properties;
    }
}
