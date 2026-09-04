package com.example.dvely.agent.application.service;

import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.config.AiProperties;
import java.util.ArrayList;
import java.util.List;
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

    /** 한 제공자의 노출 가능한 정보. {@link AiModelOptionsResolver} 가 검증에 쓰는 것과 같은 값들이다. */
    public record ProviderView(
            AiProvider provider, String defaultModel, List<String> models, List<String> thinkingModels) {}

    public List<ProviderView> availableProviders() {
        List<ProviderView> views = new ArrayList<>();
        addIfConfigured(views, AiProvider.ANTHROPIC, aiProperties.getAnthropic());
        addIfConfigured(views, AiProvider.OPENAI, aiProperties.getOpenai());
        addIfConfigured(views, AiProvider.GLM, aiProperties.getGlm());
        return views;
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
