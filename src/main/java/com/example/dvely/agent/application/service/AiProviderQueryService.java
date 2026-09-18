package com.example.dvely.agent.application.service;

import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.config.AiProperties;
import com.example.dvely.aiaccount.application.query.AiProviderCredentialQueryService;
import com.example.dvely.aiaccount.application.result.AiProviderCredentialResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 요청에 지정할 수 있는 AI 제공자·모델을 읽어 낸다. FE 의 제공자 선택 UI 가 enum 을 하드코딩하지 않고
 * 배포가 실제로 받는 것만 그리게 하려는 것. apiKey 가 없는 제공자는 호출이 불가하므로 제외하고,
 * apiKey 자체는 절대 밖으로 내보내지 않는다(모델·thinking 목록만).
 */
@Service
@RequiredArgsConstructor
public class AiProviderQueryService {

    private final AiProperties aiProperties;
    private final AiProviderCredentialQueryService credentialQueryService;

    /** 한 제공자의 노출 가능한 정보. {@link AiModelOptionsResolver} 가 검증에 쓰는 것과 같은 값들이다. */
    public record ProviderView(
            AiProvider provider, String defaultModel, List<String> models, List<String> thinkingModels) {}

    public List<ProviderView> availableProviders(Long userId) {
        List<ProviderView> views = new ArrayList<>();
        addIfConfigured(views, AiProvider.ANTHROPIC, aiProperties.getAnthropic());
        addIfConfigured(views, AiProvider.OPENAI, aiProperties.getOpenai());
        addIfConfigured(views, AiProvider.GLM, aiProperties.getGlm());
        addCodingAgents(views, userId);
        return views;
    }

    /**
     * Coding agents are gated on the <b>caller's own key</b>, not on a deployment one.
     *
     * <p>That is the whole point of BYOK: the run is billed to the user, so there is no server key
     * that could make one available. Listing an agent the user has no key for would offer a choice
     * that fails the moment it is taken.</p>
     *
     * <p>Model and thinking lists come back empty because the vendor's CLI decides both. An empty
     * list is the honest answer — a placeholder would invite a UI to render a chooser that changes
     * nothing.</p>
     */
    private void addCodingAgents(List<ProviderView> out, Long userId) {
        if (userId == null) {
            return;
        }
        Set<String> registered = credentialQueryService.list(userId).stream()
                .map(AiProviderCredentialResult::provider)
                .collect(Collectors.toSet());

        for (AiProvider agent : List.of(AiProvider.CLAUDE_CODE, AiProvider.CODEX)) {
            if (registered.contains(agent.credentialVendor().name())) {
                out.add(new ProviderView(agent, null, List.of(), List.of()));
            }
        }
    }

    private void addIfConfigured(List<ProviderView> out, AiProvider provider, AiProperties.Provider config) {
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            return;
        }
        out.add(new ProviderView(provider, config.getModel(), models(config), config.getThinkingModels()));
    }

    /** 기본 모델을 맨 앞에 두고 allowedModels 를 잇는다(중복 제거). */
    private List<String> models(AiProperties.Provider config) {
        List<String> models = new ArrayList<>();
        models.add(config.getModel());
        config.getAllowedModels().stream()
                .filter(m -> !m.equals(config.getModel()))
                .forEach(models::add);
        return models;
    }
}
