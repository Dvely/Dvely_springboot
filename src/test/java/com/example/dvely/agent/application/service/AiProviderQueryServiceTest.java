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

/**
 * 제공자 노출의 근거는 <b>사용자가 등록한 벤더 키</b>다(#364). 배포는 키를 갖지 않으므로
 * {@link AiProperties} 는 모델 카탈로그(기본 모델·허용 모델·thinking 모델)만 댄다.
 *
 * <p>노출 순서는 벤더(ANTHROPIC, OPENAI, GLM) 다음 코딩 에이전트(CLAUDE_CODE, CODEX) 이고,
 * 코딩 에이전트는 대응 벤더 키({@code CLAUDE_CODE}→ANTHROPIC, {@code CODEX}→OPENAI)로 켜진다.
 * 그래서 벤더 키 하나를 등록하면 그 벤더와 대응 코딩 에이전트가 함께 나온다.</p>
 */
class AiProviderQueryServiceTest {

    private static final Long USER = 1L;

    /** 사용자가 {@code vendors} 의 키를 등록해 둔 상태. 아무것도 넘기지 않으면 키가 하나도 없는 사용자다. */
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
    void excludesVendorsTheUserHasNotRegisteredAKeyFor() {
        // OPENAI 키만 없다 → OPENAI 와 그 코딩 에이전트(CODEX) 가 빠진다.
        List<AiProviderQueryService.ProviderView> views =
                serviceWith(new AiProperties(), "ANTHROPIC", "GLM").availableProviders(USER);

        assertThat(views).extracting(AiProviderQueryService.ProviderView::provider)
                .containsExactly(AiProvider.ANTHROPIC, AiProvider.GLM, AiProvider.CLAUDE_CODE);
    }

    @Test
    void returnsNothingWhenTheUserRegisteredNoKey() {
        // 기본 AiProperties 는 세 제공자의 모델 카탈로그를 이미 채워 두고 있다. 카탈로그가 있다는
        // 사실만으로는 아무것도 노출되지 않아야 한다 — 그것이 "서버 키로는 돌지 않는다" 의 뜻이다.
        List<AiProviderQueryService.ProviderView> views =
                serviceWith(new AiProperties()).availableProviders(USER);

        assertThat(views).isEmpty();
    }

    @Test
    void defaultModelFirstThenAllowedModelsDeduped() {
        AiProperties props = new AiProperties();
        props.getGlm().setModel("glm-4.7-flash");
        props.getGlm().setAllowedModels(List.of("glm-4.6", "glm-4.7-flash")); // 기본과 중복 포함
        props.getGlm().setThinkingModels(List.of("glm-4.6"));

        List<AiProviderQueryService.ProviderView> views =
                serviceWith(props, "GLM").availableProviders(USER);

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
        List<AiProviderQueryService.ProviderView> views =
                serviceWith(new AiProperties(), "OPENAI").availableProviders(USER);

        // OPENAI 키 → OPENAI 와 CODEX. ANTHROPIC 키가 없으니 CLAUDE_CODE 는 나오지 않는다.
        assertThat(views).extracting(AiProviderQueryService.ProviderView::provider)
                .containsExactly(AiProvider.OPENAI, AiProvider.CODEX);
    }

    @Test
    void bothCodingAgentsAppearWhenBothVendorKeysExist() {
        List<AiProviderQueryService.ProviderView> views =
                serviceWith(new AiProperties(), "ANTHROPIC", "OPENAI").availableProviders(USER);

        assertThat(views).extracting(AiProviderQueryService.ProviderView::provider)
                .containsExactly(
                        AiProvider.ANTHROPIC, AiProvider.OPENAI, AiProvider.CLAUDE_CODE, AiProvider.CODEX);
    }

    @Test
    void codingAgentsCarryNoModelChoiceBecauseTheCliDecides() {
        List<AiProviderQueryService.ProviderView> views =
                serviceWith(new AiProperties(), "OPENAI").availableProviders(USER);

        AiProviderQueryService.ProviderView codex = views.stream()
                .filter(view -> view.provider() == AiProvider.CODEX)
                .findFirst()
                .orElseThrow();
        // 빈 목록이 정직한 답이다. 자리표시자를 두면 아무것도 바꾸지 않는 선택 UI 를 그리게 된다.
        assertThat(codex.models()).isEmpty();
        assertThat(codex.thinkingModels()).isEmpty();
        assertThat(codex.defaultModel()).isNull();
    }

    @Test
    void anonymousCallerSeesNoProviderAtAll() {
        // 벤더 제공자도 더는 배포 설정으로 노출되지 않으므로, 사용자가 없으면 코딩 에이전트뿐
        // 아니라 아무 제공자도 나가지 않는다.
        List<AiProviderQueryService.ProviderView> views =
                serviceWith(new AiProperties(), "ANTHROPIC", "OPENAI", "GLM").availableProviders(null);

        assertThat(views).isEmpty();
    }
}
