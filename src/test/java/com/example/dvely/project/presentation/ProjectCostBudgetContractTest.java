package com.example.dvely.project.presentation;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dvely.common.response.ApiResponseAdvice;
import com.example.dvely.project.application.facade.ProjectFacade;
import com.example.dvely.project.application.result.ProjectCostBudgetResult;
import com.example.dvely.project.application.service.ProjectChatSettingsService;
import com.example.dvely.project.application.service.ProjectCostBudgetService;
import com.example.dvely.project.application.service.ProjectInfrastructureConfigurationService;
import com.example.dvely.project.application.service.ProjectInfrastructureSettingsService;
import com.example.dvely.project.infrastructure.mapper.ProjectMapper;
import com.example.dvely.project.presentation.dto.request.UpdateProjectBudgetRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Wire-level contract coverage for {@code .../settings/cost-budget} (same
 * {@code standaloneSetup(...)} + {@link ApiResponseAdvice} pattern as
 * {@code ProjectRepositoryDisconnectContractTest}) — proves the actual HTTP status/body Spring MVC
 * produces, not just that the controller method calls the service (which
 * {@code ProjectControllerTest} already covers at the Java level). In particular this is the only
 * place a dropped {@code @ResponseStatus(HttpStatus.NO_CONTENT)} on DELETE, or a Bean Validation
 * annotation removed from {@code UpdateProjectBudgetRequest}, would actually be caught.
 */
class ProjectCostBudgetContractTest {

    private final ProjectCostBudgetService projectCostBudgetService = mock(ProjectCostBudgetService.class);
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProjectController(
                        mock(ProjectFacade.class),
                        new ProjectMapper(),
                        mock(ProjectChatSettingsService.class),
                        mock(ProjectInfrastructureSettingsService.class),
                        mock(ProjectInfrastructureConfigurationService.class),
                        projectCostBudgetService
                ))
                .setControllerAdvice(new ApiResponseAdvice())
                // @AuthenticationPrincipal normally resolves from Spring Security's
                // SecurityContextHolder; standalone MockMvc doesn't wire that filter chain, so we
                // supply a trivial resolver returning a fixed userId (same approach as
                // ProjectRepositoryDisconnectContractTest).
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
    void getCostBudget_respondsWithHttp200AndEnvelopedBody() throws Exception {
        ProjectCostBudgetResult result = new ProjectCostBudgetResult(
                11L, false, null, "USD", null, List.of(), List.of("가정 문구"), "2026-07.static.1",
                null, "NO_BUDGET", null
        );
        when(projectCostBudgetService.get(1L, 11L)).thenReturn(result);

        mockMvc.perform(get("/api/v1/projects/11/settings/cost-budget"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.costAvailable").value(false))
                .andExpect(jsonPath("$.data.budgetStatus").value("NO_BUDGET"));
    }

    @Test
    void updateCostBudget_validRequest_respondsWithHttp200() throws Exception {
        ProjectCostBudgetResult result = new ProjectCostBudgetResult(
                11L, false, null, "USD", null, List.of(), List.of(), "2026-07.static.1",
                new ProjectCostBudgetResult.Budget(new BigDecimal("30.00"), "USD", null),
                "NOT_EVALUABLE", null
        );
        when(projectCostBudgetService.update(eq(1L), eq(11L), eq(new BigDecimal("30.00")), eq("USD")))
                .thenReturn(result);

        mockMvc.perform(put("/api/v1/projects/11/settings/cost-budget")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateProjectBudgetRequest(new BigDecimal("30.00"), "USD"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.budgetStatus").value("NOT_EVALUABLE"));

        verify(projectCostBudgetService).update(1L, 11L, new BigDecimal("30.00"), "USD");
    }

    // Bean Validation (@NotNull @DecimalMin) on UpdateProjectBudgetRequest — this is the one path
    // that actually invokes MethodArgumentNotValidException through GlobalExceptionHandler; a
    // Mockito-level service test can never exercise the validation annotations themselves.
    @Test
    void updateCostBudget_zeroAmount_respondsWithHttp400() throws Exception {
        mockMvc.perform(put("/api/v1/projects/11/settings/cost-budget")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monthlyBudgetAmount\": 0, \"currency\": \"USD\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateCostBudget_missingAmount_respondsWithHttp400() throws Exception {
        mockMvc.perform(put("/api/v1/projects/11/settings/cost-budget")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\": \"USD\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void clearCostBudget_respondsWithHttp204NoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/projects/11/settings/cost-budget"))
                .andExpect(status().isNoContent());

        verify(projectCostBudgetService).clear(1L, 11L);
    }
}
