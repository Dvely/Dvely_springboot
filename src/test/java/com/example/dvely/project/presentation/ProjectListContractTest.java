package com.example.dvely.project.presentation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dvely.common.response.ApiResponseAdvice;
import com.example.dvely.project.application.facade.ProjectFacade;
import com.example.dvely.project.application.result.ProjectSummaryResult;
import com.example.dvely.project.application.service.ProjectChatSettingsService;
import com.example.dvely.project.application.service.ProjectCostBudgetService;
import com.example.dvely.project.application.service.ProjectInfrastructureConfigurationService;
import com.example.dvely.project.application.service.ProjectInfrastructureSettingsService;
import com.example.dvely.project.infrastructure.mapper.ProjectMapper;
import java.time.LocalDateTime;
import java.util.List;
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
 * 프로젝트 목록 응답(GET /api/v1/projects)의 실제 JSON. 목록 카드는 templateType·startMode 로 템플릿을 보여 주는데,
 * 목록 응답에 두 필드가 빠져 있어 템플릿을 고른 프로젝트까지 전부 "템플릿 미설정"으로 보였다. 자바 레벨 테스트로는
 * 응답 DTO 에 필드가 없는 것을 잡지 못했으므로 직렬화된 본문을 본다.
 */
class ProjectListContractTest {

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
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
                    }

                    @Override
                    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
                        return 1L;
                    }
                })
                .build();
    }

    @Test
    void eachListItemCarriesTheTemplateTheProjectWasCreatedWith() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        when(projectFacade.getProjects(1L)).thenReturn(List.of(
                new ProjectSummaryResult(17L, "craft-roast-demo", "LIVE", null, now, "template", "craft-roast"),
                new ProjectSummaryResult(3L, "blank-app", "DRAFT", null, now, "blank", null)
        ));

        mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].projectId").value(17))
                .andExpect(jsonPath("$.data[0].startMode").value("template"))
                .andExpect(jsonPath("$.data[0].templateType").value("craft-roast"))
                .andExpect(jsonPath("$.data[1].startMode").value("blank"))
                .andExpect(jsonPath("$.data[1].templateType").doesNotExist());
    }
}
