package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.config.AiProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiProviderQueryServiceTest {

    @Test
    void excludesProvidersWithoutApiKey() {
        AiProperties props = new AiProperties();
        props.getAnthropic().setApiKey("key-a");
        props.getOpenai().setApiKey("");      // 빈 값 = 미설정 → 제외
        props.getGlm().setApiKey("key-g");

        List<AiProviderQueryService.ProviderView> views =
                new AiProviderQueryService(props).availableProviders();

        assertThat(views).extracting(AiProviderQueryService.ProviderView::provider)
                .containsExactly(AiProvider.ANTHROPIC, AiProvider.GLM);
    }

    @Test
    void returnsNothingWhenNoProviderConfigured() {
        // 기본 AiProperties 는 세 제공자 모두 apiKey 가 null 이다.
        List<AiProviderQueryService.ProviderView> views =
                new AiProviderQueryService(new AiProperties()).availableProviders();

        assertThat(views).isEmpty();
    }

    @Test
    void defaultModelFirstThenAllowedModelsDeduped() {
        AiProperties props = new AiProperties();
        props.getGlm().setApiKey("key-g");
        props.getGlm().setModel("glm-4.7-flash");
        props.getGlm().setAllowedModels(List.of("glm-4.6", "glm-4.7-flash")); // 기본과 중복 포함
        props.getGlm().setThinkingModels(List.of("glm-4.6"));

        List<AiProviderQueryService.ProviderView> views =
                new AiProviderQueryService(props).availableProviders();

        assertThat(views).hasSize(1);
        AiProviderQueryService.ProviderView glm = views.get(0);
        assertThat(glm.provider()).isEqualTo(AiProvider.GLM);
        assertThat(glm.defaultModel()).isEqualTo("glm-4.7-flash");
        assertThat(glm.models()).containsExactly("glm-4.7-flash", "glm-4.6"); // 기본 먼저, 중복 제거
        assertThat(glm.thinkingModels()).containsExactly("glm-4.6");
    }
}
