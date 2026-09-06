package com.example.dvely.agent.presentation.dto;

import com.example.dvely.agent.domain.value.AiProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "요청에 지정할 수 있는 AI 제공자와 각 제공자가 받는 모델 목록. apiKey 가 설정된 제공자만 담긴다.")
public record AiProvidersResponse(List<Provider> providers) {

    @Schema(description = "한 AI 제공자의 선택지")
    public record Provider(
            @Schema(description = "제공자", example = "GLM")
            AiProvider provider,

            @Schema(description = "모델을 지정하지 않을 때 쓰는 기본 모델", example = "z-ai/glm-4.6")
            String defaultModel,

            @Schema(description = "요청 model 로 지정 가능한 모델(기본 모델 포함)")
            List<String> models,

            @Schema(description = "thinking(추론) 파라미터를 받는 모델. 비어 있으면 이 제공자는 thinking 미지원")
            List<String> thinkingModels
    ) {}
}
