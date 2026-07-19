package com.example.dvely.deployment.presentation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dvely.common.response.ApiResponseAdvice;
import com.example.dvely.deployment.application.facade.DeploymentFacade;
import com.example.dvely.deployment.application.result.DeployResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDateTime;
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
 * Review follow-up F7: {@code @ResponseStatus(HttpStatus.CREATED)} on
 * {@link DeploymentController#retryDeployment} had no test actually dispatching an HTTP request
 * through Spring MVC — {@code DeploymentControllerTest} calls the controller method directly in
 * Java, which never exercises the annotation. This drives the real controller through
 * {@link ApiResponseAdvice} + real Jackson message conversion (same
 * {@code standaloneSetup(...).setControllerAdvice(...)} pattern as
 * {@code EnvironmentVariableResponseContractTest}/{@code GlobalExceptionHandlerTest}) to prove
 * the response is actually 201, not just that the facade is called correctly.
 */
class DeploymentRetryContractTest {

    private final DeploymentFacade facade = mock(DeploymentFacade.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        mockMvc = MockMvcBuilders.standaloneSetup(new DeploymentController(facade))
                .setControllerAdvice(new ApiResponseAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
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
    void retryReturnsHttp201WithTheNewDeploymentId() throws Exception {
        DeployResult result = new DeployResult(52L, 11L, "VERSION", "v3", "PENDING", null, LocalDateTime.now());
        when(facade.retryDeployment(1L, 51L)).thenReturn(result);

        mockMvc.perform(post("/api/v1/deployments/51/retry"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.deploymentId").value(52L))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }
}
