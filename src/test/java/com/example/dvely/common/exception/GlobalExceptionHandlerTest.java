package com.example.dvely.common.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
        mockMvc = MockMvcBuilders.standaloneSetup(new PathVariableController(), new QueryParamController())
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
}
