package com.example.dvely.environment.presentation;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dvely.common.response.ApiResponseAdvice;
import com.example.dvely.environment.application.facade.EnvironmentVariableFacade;
import com.example.dvely.environment.application.result.EnvironmentVariableResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Review follow-up (design §3.1's explicit warning): {@code ApiResponse}'s
 * {@code @JsonInclude(NON_NULL)} only applies to the envelope's own fields (status/code/message/
 * data) — it says nothing about whether a null field *inside* {@code data} (here,
 * {@code EnvironmentVariableResponse.value} for a secret variable) survives serialization. This
 * had never been checked against the real Jackson pipeline; {@code EnvironmentVariableControllerTest}
 * only asserts on the Java record instance, never on the JSON actually sent over the wire. This
 * test drives the REAL {@link EnvironmentVariableController} through
 * {@link ApiResponseAdvice} + a real {@code MappingJackson2HttpMessageConverter} (same
 * {@code standaloneSetup(...).setControllerAdvice(...)} pattern as
 * {@code GlobalExceptionHandlerTest}/{@code ApiResponseContractTest}) to prove the "value" key is
 * present-but-null, not silently dropped — the exact contract the FE relies on to render
 * "secret configured, hidden" instead of treating the field as absent.
 */
class EnvironmentVariableResponseContractTest {

    private final EnvironmentVariableFacade facade = mock(EnvironmentVariableFacade.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Standalone MockMvc builds its own minimal converter set unless told otherwise, and the
        // default ObjectMapper it would pick has no java.time support — register JavaTimeModule
        // explicitly so EnvironmentVariableResponse's LocalDateTime fields actually serialize
        // instead of throwing, mirroring what Spring Boot's auto-configuration does at runtime.
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        mockMvc = MockMvcBuilders.standaloneSetup(new EnvironmentVariableController(facade))
                .setControllerAdvice(new ApiResponseAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                // @AuthenticationPrincipal normally resolves from Spring Security's
                // SecurityContextHolder; standalone MockMvc doesn't wire that filter chain, so we
                // supply a trivial resolver returning a fixed userId rather than pulling in the
                // full spring-security-test machinery this codebase doesn't otherwise use.
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
                    }

                    @Override
                    public Object resolveArgument(MethodParameter parameter,
                                                  ModelAndViewContainer mavContainer,
                                                  NativeWebRequest webRequest,
                                                  WebDataBinderFactory binderFactory) {
                        return 1L;
                    }
                })
                .build();
    }

    @Test
    void secretVariableJsonHasAPresentButNullValueField() throws Exception {
        EnvironmentVariableResult secretResult = new EnvironmentVariableResult(
                2L, "PRODUCTION", "STRIPE_SECRET_KEY", null, true, LocalDateTime.now(), LocalDateTime.now()
        );
        when(facade.getVariables(1L, 11L, null)).thenReturn(List.of(secretResult));

        mockMvc.perform(get("/api/v1/projects/11/environment-variables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].secret").value(true))
                // If any inclusion rule ever leaked onto this nested response type, "value"
                // would be entirely absent and this jsonPath lookup would throw
                // PathNotFoundException — failing loudly instead of the FE silently seeing a
                // missing field.
                .andExpect(jsonPath("$.data[0].value").value(nullValue()));
    }

    @Test
    void nonSecretVariableJsonKeepsThePlaintextValue() throws Exception {
        EnvironmentVariableResult plainResult = new EnvironmentVariableResult(
                1L, "PREVIEW", "API_BASE_URL", "https://api.example.com", false, LocalDateTime.now(), LocalDateTime.now()
        );
        when(facade.getVariables(1L, 11L, null)).thenReturn(List.of(plainResult));

        mockMvc.perform(get("/api/v1/projects/11/environment-variables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].secret").value(false))
                .andExpect(jsonPath("$.data[0].value").value("https://api.example.com"));
    }
}
