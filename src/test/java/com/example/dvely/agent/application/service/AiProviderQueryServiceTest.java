package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.agent.domain.value.AiProvider;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.infrastructure.config.AiProperties;
import com.example.dvely.aiaccount.application.query.AiProviderCredentialQueryService;
import com.example.dvely.aiaccount.application.result.AiProviderCredentialResult;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiProviderQueryServiceTest {

    private static final Long USER = 1L;

    /** A user with no BYOK key registered — the default for every existing case here. */
    private static AiProviderQueryService serviceWith(AiProperties props, String... vendors) {
        AiProviderCredentialQueryService credentials = mock(AiProviderCredentialQueryService.class);
        when(credentials.list(anyLong())).thenReturn(
                java.util.Arrays.stream(vendors)
                        .map(v -> new AiProviderCredentialResult(
                                1L, v, "sk-****", null, LocalDateTime.now(), LocalDateTime.now()))
                        .toList());
        return new AiProviderQueryService(props, credentials);
    }

    @Test
    void excludesProvidersWithoutApiKey() {
        AiProperties props = new AiProperties();
        props.getAnthropic().setApiKey("key-a");
        props.getOpenai().setApiKey("");      // 빈 값 = 미설정 → 제외
        props.getGlm().setApiKey("key-g");

        List<AiProviderQueryService.ProviderView> views =
                serviceWith(props).availableProviders(USER);

        assertThat(views).extracting(AiProviderQueryService.ProviderView::provider)
                .containsExactly(AiProvider.ANTHROPIC, AiProvider.GLM);
    }

    @Test
    void returnsNothingWhenNoProviderConfigured() {
        // 기본 AiProperties 는 세 제공자 모두 apiKey 가 null 이다.
        List<AiProviderQueryService.ProviderView> views =
                serviceWith(new AiProperties()).availableProviders(USER);

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
                serviceWith(props).availableProviders(USER);

        assertThat(views).hasSize(1);
        AiProviderQueryService.ProviderView glm = views.get(0);
        assertThat(glm.provider()).isEqualTo(AiProvider.GLM);
        assertThat(glm.defaultModel()).isEqualTo("glm-4.7-flash");
        assertThat(glm.models()).containsExactly("glm-4.7-flash", "glm-4.6"); // 기본 먼저, 중복 제거
        assertThat(glm.thinkingModels()).containsExactly("glm-4.6");
    }

    /**
     * 코딩 에이전트는 <b>본인이 등록한 키</b>로 갈린다.
     *
     * <p>BYOK 의 핵심이 그것이다 — 사용량이 사용자에게 청구되므로 이걸 대신 켜 줄 서버 키가 존재할
     * 수 없다. 키 없는 사용자에게 목록에 띄우면, 고르는 순간 실패하는 선택지를 주는 셈이다.</p>
     */
    @Test
    void codingAgentsAppearOnlyForTheVendorKeysTheUserRegistered() {
        AiProperties props = new AiProperties();   // 서버 제공자는 전부 미설정

        List<AiProviderQueryService.ProviderView> views =
                serviceWith(props, "OPENAI").availableProviders(USER);

        assertThat(views).extracting(AiProviderQueryService.ProviderView::provider)
                .containsExactly(AiProvider.CODEX);
    }

    @Test
    void bothCodingAgentsAppearWhenBothVendorKeysExist() {
        List<AiProviderQueryService.ProviderView> views =
                serviceWith(new AiProperties(), "ANTHROPIC", "OPENAI").availableProviders(USER);

        assertThat(views).extracting(AiProviderQueryService.ProviderView::provider)
                .containsExactly(AiProvider.CLAUDE_CODE, AiProvider.CODEX);
    }

    @Test
    void codingAgentsCarryNoModelChoiceBecauseTheCliDecides() {
        List<AiProviderQueryService.ProviderView> views =
                serviceWith(new AiProperties(), "OPENAI").availableProviders(USER);

        AiProviderQueryService.ProviderView codex = views.get(0);
        // 빈 목록이 정직한 답이다. 자리표시자를 두면 아무것도 바꾸지 않는 선택 UI 를 그리게 된다.
        assertThat(codex.models()).isEmpty();
        assertThat(codex.thinkingModels()).isEmpty();
        assertThat(codex.defaultModel()).isNull();
    }

    @Test
    void anonymousCallerSeesNoCodingAgent() {
        List<AiProviderQueryService.ProviderView> views =
                serviceWith(new AiProperties(), "OPENAI").availableProviders(null);

        assertThat(views).isEmpty();
    }
}
