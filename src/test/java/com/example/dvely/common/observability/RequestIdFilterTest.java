package com.example.dvely.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private AtomicReference<String> run(String inboundHeader) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
        if (inboundHeader != null) {
            request.addHeader(RequestIdFilter.HEADER, inboundHeader);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInsideChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> seenInsideChain.set(MDC.get(RequestIdFilter.MDC_KEY));

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(seenInsideChain.get());
        return seenInsideChain;
    }

    @Test
    void generatesAnIdWhenTheCallerDidNotSendOne() throws Exception {
        assertThat(run(null).get()).isNotBlank();
    }

    @Test
    void twoRequestsGetDifferentIds() throws Exception {
        assertThat(run(null).get()).isNotEqualTo(run(null).get());
    }

    @Test
    void keepsACallerSuppliedIdSoATraceSpansTheWholeCallChain() throws Exception {
        assertThat(run("fe-req-42_a.b").get()).isEqualTo("fe-req-42_a.b");
    }

    @Test
    void replacesAnIdThatCouldForgeALogLine() throws Exception {
        // 개행이 섞인 값을 그대로 MDC 에 넣으면 로그 한 줄을 통째로 위조할 수 있다.
        String forged = "abc\nWARN  [] 관리자 권한이 부여되었습니다";

        String actual = run(forged).get();

        assertThat(actual).doesNotContain("\n").doesNotContain("관리자");
        assertThat(actual).isNotEqualTo(forged);
    }

    @Test
    void replacesAnIdThatIsTooLongToBelongInEveryLogLine() throws Exception {
        String tooLong = "a".repeat(65);

        assertThat(run(tooLong).get()).isNotEqualTo(tooLong).hasSizeLessThanOrEqualTo(64);
    }

    @Test
    void clearsTheMdcSoTheNextRequestOnThisThreadDoesNotInheritIt() throws Exception {
        run(null);

        // 스레드는 풀로 돌아간다. 남아 있으면 다음 요청 로그에 남의 ID 가 붙는다.
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void clearsTheMdcEvenWhenTheRequestBlowsUp() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain exploding = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> filter.doFilterInternal(request, response, exploding))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
