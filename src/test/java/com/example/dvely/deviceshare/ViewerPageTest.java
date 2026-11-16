package com.example.dvely.deviceshare;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.deviceshare.application.service.ShareFeedbackService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 공유 뷰어 페이지(deviceshare/viewer.html)의 정적 약속. 뷰어는 브라우저에서 돌아 서버 테스트가 실행하지 못하므로,
 * 깨지면 바로 사고가 되는 것만 글자로 지킨다. 실제 동작은 PR 마다 브라우저로 확인한다.
 */
class ViewerPageTest {

    private static String viewer() throws IOException {
        try (InputStream in = new ClassPathResource("deviceshare/viewer.html").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void 기기와_손님이_보낸_값을_HTML_로_해석하는_길이_없다() throws IOException {
        // 요소 이름·고친 내용·손님 글은 기기·다른 사람이 보낸 값이다. textContent 로만 그린다(설계 §14.7)
        String page = viewer();
        for (String sink : List.of("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(",
                "new Function", "srcdoc")) {
            assertThat(page).as(sink).doesNotContain(sink);
        }
    }

    @Test
    void 짚기_메시지_이름이_서버와_같다() throws IOException {
        String page = viewer();
        // 브라우저 → 서버. pin-probe 는 세션이, 나머지는 피드백 서비스가 받는다
        assertThat(page).contains("type: 'pin-probe'");
        for (String type : List.of("pin-submit", "feedback-watch", "feedback-confirm", "feedback-reopen")) {
            assertThat(page).as(type).contains("type: '" + type + "'");
            assertThat(ShareFeedbackService.handlesViewerMessage(type)).as(type).isTrue();
        }
        // 서버 → 브라우저
        assertThat(page).contains("case 'pin':", "case 'feedback':", "m.pin === true");
    }

    @Test
    void 외부_자원을_불러오지_않는다() throws IOException {
        // CSP(default-src 'none', connect-src 'self')가 막지만, 막힌 자원은 조용히 깨진다
        String page = viewer();
        assertThat(page).doesNotContainPattern("(src|href)=\"https?://");
        assertThat(page).doesNotContain("@import");
    }
}
