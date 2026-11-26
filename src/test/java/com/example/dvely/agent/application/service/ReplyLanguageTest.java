package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * CODE 맺음말의 말을 계획의 userSummary 로 정한다. 계획 모델이 한국어 요청을 영어 지시로 옮겨 요약이 영어로 나온 실측에서 왔다.
 */
class ReplyLanguageTest {

    @Test
    void 승인_카드_문장의_글자로_말을_정한다() {
        assertThat(ReplyLanguage.of("\"진행 표시 확인\" 제목과 남색 \"시작\" 버튼이 있는 HTML 페이지를 만듭니다")).contains("Korean");
        assertThat(ReplyLanguage.of("ToDo アプリを作ります")).contains("Japanese");
        assertThat(ReplyLanguage.of("创建一个待办事项应用")).contains("Chinese");
        // 한자가 섞인 일본어는 일본어다
        assertThat(ReplyLanguage.of("設定画面を作ります")).contains("Japanese");
    }

    @Test
    void 정할_수_없으면_지시_그대로_둔다() {
        assertThat(ReplyLanguage.of("Builds a one-page todo app")).isEmpty();
        assertThat(ReplyLanguage.of("")).isEmpty();
        assertThat(ReplyLanguage.of(null)).isEmpty();
        assertThat(ReplyLanguage.append("Create a todo app.", "Builds a todo app")).isEqualTo("Create a todo app.");
    }

    @Test
    void 영어_지시_끝에_한국어로_답하라고_붙인다() {
        String instruction = ReplyLanguage.append("Create a new single-page project.", "할 일 페이지를 만듭니다");

        assertThat(instruction).startsWith("Create a new single-page project.\n\nReply language:")
                .contains("wrote in Korean").contains("closing summary for them in Korean");
    }
}
