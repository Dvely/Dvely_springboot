package com.example.dvely.agent.application.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 이미 답한 되묻기. 질문·선택지와 <b>사용자가 고른 값</b>을 함께 담는다.
 *
 * <p>되묻기 폼은 답한 순간 사라지도록 설계돼 있다(이중 제출 방지). 그래서 답이 끝나면 화면에
 * 질문만 남고 무엇을 골랐는지 확인할 방법이 없었다. 이 값이 있으면 FE 가 "이렇게 정했습니다"
 * 카드를 그릴 수 있다 — 선택지를 그대로 보여주되 고른 것만 표시하는 읽기 전용 상태.</p>
 *
 * <p>{@link ClarificationRequest} 와 필드를 공유하되 별도 레코드로 둔다. 그쪽은 "지금 답해야 할
 * 질문"이고 이쪽은 "이미 답한 기록"이라, 하나로 합치면 FE 가 둘을 상태로만 구분해야 한다.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "이미 답한 되묻기. 질문·선택지와 사용자가 고른 값을 함께 담는다.")
public record AnsweredClarification(

        @Schema(description = "사용자가 받았던 질문", example = "할 일 앱을 어떤 프론트엔드 스택으로 만들까요?")
        String question,

        @Schema(description = "답변 형식", example = "SINGLE_SELECT")
        ClarificationRequest.InputType inputType,

        @Schema(description = "제시했던 선택지. TEXT 형식이면 비어 있다.")
        List<ClarificationRequest.Option> options,

        @Schema(description = "선택지 외 직접 입력을 허용했는지")
        boolean allowOther,

        @Schema(description = "사용자가 실제로 보낸 답. 선택형이면 고른 항목의 label 이다.",
                example = "순수 HTML/CSS/JS (빌드 없음)")
        String answer
) {
}
