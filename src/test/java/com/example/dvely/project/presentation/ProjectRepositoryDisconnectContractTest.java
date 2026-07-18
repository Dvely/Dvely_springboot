package com.example.dvely.project.presentation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dvely.common.response.ApiResponseAdvice;
import com.example.dvely.project.application.facade.ProjectFacade;
import com.example.dvely.project.application.service.ProjectChatSettingsService;
import com.example.dvely.project.application.service.ProjectCostBudgetService;
import com.example.dvely.project.application.service.ProjectInfrastructureConfigurationService;
import com.example.dvely.project.application.service.ProjectInfrastructureSettingsService;
import com.example.dvely.project.infrastructure.mapper.ProjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Review follow-up: {@code ProjectControllerTest#disconnectRepository_delegatesUsingAuthenticatedUserIdAndProjectId}
 * calls the controller method directly, which proves facade delegation but never exercises
 * Spring MVC's status resolution — a dropped {@code @ResponseStatus(HttpStatus.NO_CONTENT)}
 * annotation would still pass that test (the method still returns void and still calls the
 * facade) while silently changing the real HTTP response to 200. This test drives the actual
 * {@link ProjectController} through MockMvc (same {@code standaloneSetup(...)} +
 * {@link ApiResponseAdvice} pattern as {@code EnvironmentVariableResponseContractTest}) so the
 * wire-level status is checked for real, not just the Java return value.
 */
class ProjectRepositoryDisconnectContractTest {

    private final ProjectFacade projectFacade = mock(ProjectFacade.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProjectController(
                        projectFacade,
                        new ProjectMapper(),
                        mock(ProjectChatSettingsService.class),
                        mock(ProjectInfrastructureSettingsService.class),
                        mock(ProjectInfrastructureConfigurationService.class),
                        mock(ProjectCostBudgetService.class)
                ))
                .setControllerAdvice(new ApiResponseAdvice())
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
    void disconnectRepository_respondsWithHttp204NoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/projects/11/repository"))
                .andExpect(status().isNoContent());

        verify(projectFacade).disconnectRepository(1L, 11L);
    }
}
