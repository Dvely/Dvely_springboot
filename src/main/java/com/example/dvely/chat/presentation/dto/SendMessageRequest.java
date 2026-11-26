package com.example.dvely.chat.presentation.dto;

import com.example.dvely.agent.domain.value.AiProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

@Schema(description = "대화 메시지 생성 요청")
public record SendMessageRequest(
        @Schema(description = "저장할 사용자 메시지 본문", example = "랜딩 페이지에 FAQ 섹션을 추가해줘")
        @NotBlank String content,

        @Schema(description = "이 메시지로 실행할 에이전트가 쓸 AI 제공자. 본인 키가 등록된 것만 쓸 수 있고 "
                + "생략할 수 없다 — 서버 키로 도는 기본 경로는 없다. "
                + "선택지는 GET /api/v1/agent/ai-providers 로 조회한다.",
                example = "CLAUDE_CODE")
        @NotNull AiProvider aiProvider,

        @Schema(description = """
                서버 프리뷰에서 짚은 곳(선택, 5개까지 — 순서대로 ①~⑤). 프리뷰 문서의 짚기 브리지가 낸 결과를 그대로 넣는다
                (docs/device-preview-share-design.md §16.4). 서버가 기기 공유 짚기와 같은 규칙으로 다시 정리하고, 형식이 틀린
                것이 하나라도 있으면 400 이다. 계획·코드 작업에 "프리뷰에서 짚은 곳" 으로 데이터로만 전달된다.
                """, nullable = true)
        @Size(max = 5) @Valid List<PinRequest> pins
) {

    public SendMessageRequest(String content, AiProvider aiProvider) {
        this(content, aiProvider, null);
    }

    @Schema(description = "짚은 곳 하나")
    public record PinRequest(
            @Schema(description = "브리지의 짚기 결과(probeId · ok · screen · point · element · ancestors · components · scopes …)")
            @NotNull Map<String, Object> result,

            @Schema(description = "사람이 고른 범위 사다리 칸. 없거나 사다리 밖이면 결과의 기본 칸(defaultScope)", nullable = true)
            Integer scope,

            @Schema(description = "고른 칸의 \"같은 것 모두\"(그 칸에 peers 가 있을 때만 의미가 있다)", nullable = true)
            Boolean peers
    ) {

        public PinRequest(Map<String, Object> result, Integer scope) {
            this(result, scope, null);
        }
    }
}
