package com.example.dvely.agent.infrastructure.config;

import com.example.dvely.agent.domain.value.AiProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 제공자 설정에서 <b>서로 맞아야 하는 두 값이 어긋난 것</b>을 기동 때 드러낸다.
 *
 * <p>{@code model} 과 {@code thinking-models} 는 독립된 스위치인데 함께 맞아야 의미가 생긴다.
 * 어긋나도 기동은 성공하고, 요청이 들어와 thinking 을 달라고 할 때에야 400 으로 나타난다 —
 * 설정을 바꾼 사람과 증상을 보는 사람이 다른 시점에 있는 형태다.</p>
 *
 * <p>운영에서 실제로 났다(2026-09-18). {@code model} 을 기본값에서 바꾸면서 {@code thinking-models}
 * 는 그대로 둬, 기본 모델로는 thinking 을 쓸 수 없는 상태가 조용히 만들어졌다.</p>
 *
 * <p><b>기동을 막지는 않는다.</b> 어긋남이 의도인 배포가 있을 수 있고(예: 기본은 싼 모델로 두고
 * thinking 은 {@code allowed-models} 의 다른 모델로만 허용), 설정 한 줄 때문에 서비스를 못 뜨게
 * 하는 것은 과하다. 다만 조용히 두지는 않는다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiModelConfigInspector {

    private final AiProperties aiProperties;

    @EventListener(ApplicationReadyEvent.class)
    public void inspect() {
        Map<AiProvider, AiProperties.Provider> providers = new LinkedHashMap<>();
        providers.put(AiProvider.ANTHROPIC, aiProperties.getAnthropic());
        providers.put(AiProvider.OPENAI, aiProperties.getOpenai());
        providers.put(AiProvider.GLM, aiProperties.getGlm());

        providers.forEach(this::inspectProvider);
    }

    private void inspectProvider(AiProvider provider, AiProperties.Provider config) {
        List<String> thinkingModels = config.getThinkingModels();
        if (thinkingModels == null || thinkingModels.isEmpty()) {
            // 비어 있는 것은 "이 제공자는 thinking 을 받지 않는다" 는 분명한 선언이다(gpt-4o 가 그렇다).
            return;
        }

        String model = config.getModel();
        if (model != null && !thinkingModels.contains(model)) {
            log.warn("[AiConfig] {} 의 기본 모델로는 thinking 을 쓸 수 없습니다 — model={} 이 thinking-models={} 에 없습니다. "
                            + "이 모델에 thinking 을 요청하면 400 으로 거절됩니다. 의도한 것이 아니면 thinking-models 에 추가하세요.",
                    provider, model, thinkingModels);
        }

        // 요청이 지정할 수 있는 모델은 model 과 allowed-models 뿐이다. 그 밖의 thinking 항목은
        // 아무도 쓸 수 없다 — 모델을 바꾸면서 예전 이름을 지우지 않았을 때 남는 흔적이다.
        List<String> unreachable = thinkingModels.stream()
                .filter(candidate -> !config.allows(candidate))
                .toList();
        if (!unreachable.isEmpty()) {
            log.warn("[AiConfig] {} 의 thinking-models 에 아무도 지정할 수 없는 모델이 있습니다: {} — "
                            + "model 도 아니고 allowed-models 에도 없습니다. 쓰려면 allowed-models 에 넣고, "
                            + "아니면 목록에서 지우세요.",
                    provider, unreachable);
        }
    }
}
