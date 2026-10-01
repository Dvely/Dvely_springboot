package com.example.dvely.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Regression coverage for {@link GlobalExceptionHandler}.
 *
 * <p>QA defect D-500-1: a non-numeric value on a {@code @PathVariable Long} route (e.g.
 * {@code GET /projects/abc}) used to fall through to the catch-all {@code Exception} handler
 * and return 500, even though the root cause is a client input error. This test proves the
 * dedicated {@link org.springframework.web.method.annotation.MethodArgumentTypeMismatchException}
 * handler now intercepts that case and answers with 400 instead.</p>
 *
 * <p>Code review follow-up (u1-review.md, F2): the same exception type is also raised when a
 * {@code @RequestParam Long} query parameter (e.g. {@code AgentController#afterEventId}) fails
 * to convert, and query strings tolerate a wider character set than path segments — so this
 * suite also covers that entry point rather than only the path-variable one.</p>
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Standalone MockMvc wired only with the controller advice under test, mirroring the
        // pattern used by ApiResponseContractTest — no full Spring context is needed to prove
        // the exception -> HTTP status mapping.
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new PathVariableController(), new QueryParamController(),
                        new ConcurrentDeleteController(), new ConflictController(),
                        new MissingPathController(), new ClientGoneController(),
                        new UnexpectedErrorController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void returnsBadRequestWhenPathVariableTypeIsInvalid() throws Exception {
        // "abc" cannot be converted to Long, triggering MethodArgumentTypeMismatchException.
        mockMvc.perform(get("/contract/projects/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("'projectId' 파라미터의 값 'abc'이(가) 올바른 형식(Long)이 아닙니다"));
    }

    @Test
    void 매핑되지_않은_경로는_500_이_아니라_404_다() throws Exception {
        // #372: 핸들러가 없으면 catch-all Exception 핸들러가 삼켜 500 이 나갔다. 미인증 요청은
        // 시큐리티가 먼저 401 을 내서 안 드러나고, 인증된 호출에서만 드러난다 — 그래서 오래
        // 남아 있었고 실제로 사람을 오도했다(클라이언트 경로 오타를 서버 장애로 읽었다).
        mockMvc.perform(get("/contract/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void returnsOkWhenPathVariableTypeIsValid() throws Exception {
        // Sanity check: a well-formed numeric id still resolves normally (no regression on the
        // happy path introduced by the new handler).
        mockMvc.perform(get("/contract/projects/12"))
                .andExpect(status().isOk());
    }

    @Test
    void returnsBadRequestWhenQueryParameterTypeIsInvalid() throws Exception {
        // "abc" cannot be converted to Long for a @RequestParam, same exception type as the
        // path-variable case but through a different Spring MVC binding path.
        mockMvc.perform(get("/contract/agent/events").param("afterEventId", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("'afterEventId' 파라미터의 값 'abc'이(가) 올바른 형식(Long)이 아닙니다"));
    }

    @Test
    void returnsConflictWhenTargetRowWasDeletedOrModifiedConcurrently() throws Exception {
        // U3 review follow-up: a PATCH/DELETE that reads-then-flushes an entity concurrently
        // deleted by another request surfaces as ObjectOptimisticLockingFailureException at
        // flush time (Hibernate's expected-row-count check), not IllegalStateException/
        // NotFoundException — without this handler it fell through to the generic 500 handler.
        //
        // I45 (#45) follow-up: this used to assert 404 (U3 F5), back when OOLFE's only real
        // cause was a concurrently-deleted row. Now that `projects` carries @Version, OOLFE's
        // dominant meaning is "version conflict on a row that still exists", so responding 404
        // to an existing resource would be wrong — the handler (and this assertion) moved to 409
        // (design D3). A genuinely deleted row still resolves correctly for the client: their
        // follow-up GET converges to 404 on its own.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/contract/variables/1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void returnsConflictWhenIllegalStateExceptionIsThrown() throws Exception {
        // U6 review follow-up F6: several deployment endpoints (retry, failure-analysis) throw
        // IllegalStateException for "not in the right state" cases (e.g. "실패한 배포만 재시도할
        // 수 있습니다") and rely on this handler mapping it to 409 — that mapping itself had no
        // direct test before, only IllegalArgumentException/NotFoundException/etc did.
        mockMvc.perform(get("/contract/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("이미 처리된 상태입니다"));
    }

    @Test
    void 클라이언트가_응답_도중_끊으면_ERROR_로_남지_않는다() throws Exception {
        // #423: SSE(/agent/tasks/{id}/events) 를 보던 브라우저가 떠나면 Spring 이
        // AsyncRequestNotUsableException 을 던진다. 전용 핸들러가 없으면 맨 아래 catch-all 이
        // 받아 "ERROR Unexpected error" + 스택트레이스로 남겼다 — 정상 동작인데 ERROR 다.
        //
        // 그래서 확인할 것은 HTTP 상태가 아니라 "무엇이 기록됐는가" 다. 결함이 로그 레벨이었으니
        // 로그를 보지 않는 테스트는 이 수정이 되돌려져도 통과한다.
        List<ILoggingEvent> logs = capturingAdviceLogs(() ->
                mockMvc.perform(get("/contract/client-gone"))
                        // 응답을 더 쓸 수 없는 상태이므로 본문이 비어야 한다. catch-all 이
                        // 받았다면 여기에 INTERNAL_SERVER_ERROR 본문이 들어온다.
                        .andExpect(content().string("")));

        assertThat(logs).noneMatch(event -> event.getLevel() == Level.ERROR);
        assertThat(logs).anyMatch(event -> event.getLevel() == Level.DEBUG
                && event.getFormattedMessage().contains("연결을 끊었습니다"));
    }

    @Test
    void 끊김이_아닌_예외는_여전히_ERROR_와_500_이다() throws Exception {
        // 위의 조용화가 넓게 번지지 않았음을 반대 방향에서 고정한다. 되돌림 검증으로는 이걸
        // 잡을 수 없다 — catch-all 을 지워도 위 테스트는 그대로 통과하기 때문이다.
        List<ILoggingEvent> logs = capturingAdviceLogs(() ->
                mockMvc.perform(get("/contract/boom"))
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR")));

        assertThat(logs).anyMatch(event -> event.getLevel() == Level.ERROR);
    }

    /**
     * advice 로거에 {@link ListAppender} 를 달고 호출을 실행한 뒤 기록된 이벤트를 돌려준다.
     *
     * <p>레벨을 DEBUG 로 내리는 것이 필요하다 — 조용해진 그 한 줄이 DEBUG 라서 기본 레벨에서는
     * 버려지고, 그러면 "ERROR 가 없다"와 "아무것도 기록되지 않았다"를 구별할 수 없다.</p>
     */
    private List<ILoggingEvent> capturingAdviceLogs(MvcCall call) throws Exception {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        Level original = logbackLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        logbackLogger.setLevel(Level.DEBUG);
        try {
            call.run();
            return List.copyOf(appender.list);
        } finally {
            logbackLogger.setLevel(original);
            logbackLogger.detachAppender(appender);
        }
    }

    private interface MvcCall {
        void run() throws Exception;
    }

    @RestController
    private static class PathVariableController {

        @GetMapping("/contract/projects/{projectId}")
        Long findProject(@PathVariable Long projectId) {
            return projectId;
        }
    }

    @RestController
    private static class QueryParamController {

        @GetMapping("/contract/agent/events")
        Long listEvents(@RequestParam Long afterEventId) {
            return afterEventId;
        }
    }

    @RestController
    private static class ConcurrentDeleteController {

        @DeleteMapping("/contract/variables/{id}")
        void delete(@PathVariable Long id) {
            throw new ObjectOptimisticLockingFailureException("EnvironmentVariable", id);
        }
    }

    @RestController
    private static class ConflictController {

        @GetMapping("/contract/conflict")
        Long conflict() {
            throw new IllegalStateException("이미 처리된 상태입니다");
        }
    }

    /**
     * 실제로는 {@code ResourceHttpRequestHandler} 가 던지는 예외다. standalone MockMvc 에는 정적
     * 리소스 핸들러가 없어 같은 경로로 재현할 수 없으므로, 같은 예외를 던져 advice 가 그것을
     * 가로채는지를 본다 — 이 버그의 본질이 "catch-all 에 먹혔다" 였으므로 확인할 것도 그것이다.
     */
    @RestController
    private static class MissingPathController {

        @GetMapping("/contract/missing")
        Long missing() throws NoResourceFoundException {
            throw new NoResourceFoundException(
                    HttpMethod.GET, "api/v1/agent/ai-credentials", "No static resource.");
        }
    }

    /**
     * SSE 스트림을 보던 클라이언트가 떠난 상태를 재현한다. 실제로는 비동기 응답 래퍼가 쓰기
     * 시점에 던지지만, 여기서 볼 것은 advice 가 그것을 가로채는지뿐이므로 같은 예외를 던진다.
     */
    @RestController
    private static class ClientGoneController {

        @GetMapping("/contract/client-gone")
        Long clientGone() throws AsyncRequestNotUsableException {
            throw new AsyncRequestNotUsableException(
                    "ServletOutputStream failed to write: java.io.IOException: Broken pipe");
        }
    }

    @RestController
    private static class UnexpectedErrorController {

        @GetMapping("/contract/boom")
        Long boom() {
            throw new RuntimeException("정말로 예상치 못한 오류");
        }
    }
}
