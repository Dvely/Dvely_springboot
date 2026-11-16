package com.example.dvely.project.presentation;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dvely.cloudresource.application.LiveResource;
import com.example.dvely.common.response.ApiResponseAdvice;
import com.example.dvely.project.application.command.dto.ProjectDeleteMode;
import com.example.dvely.project.application.facade.ProjectFacade;
import com.example.dvely.project.application.service.ProjectChatSettingsService;
import com.example.dvely.project.application.service.ProjectCostBudgetService;
import com.example.dvely.project.application.service.ProjectDeletionBlockedException;
import com.example.dvely.project.application.service.ProjectInfrastructureConfigurationService;
import com.example.dvely.project.application.service.ProjectInfrastructureSettingsService;
import com.example.dvely.project.infrastructure.mapper.ProjectMapper;
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
 * 프로젝트 삭제가 막혔을 때의 응답 모양(#595). FE 는 409 의 {@code data} 와 미리 보기 응답을 같은 모양으로 읽는다.
 * {@code ProjectRepositoryDisconnectContractTest} 와 같은 standalone MockMvc 다.
 */
class ProjectDeletionContractTest {

    private static final LiveResource SERVER =
            new LiveResource(LiveResource.Type.SERVER, 7L, 11L, "i-0abc", "RUNNING");
    private static final LiveResource DEPLOYMENT =
            new LiveResource(LiveResource.Type.DEPLOYMENT, 30L, 11L, "첫 배포", "PENDING");

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
    void blockedDeletionAnswers409WithTheLiveResources() throws Exception {
        doThrow(new ProjectDeletionBlockedException(List.of(SERVER, DEPLOYMENT)))
                .when(projectFacade).deleteProject(1L, 11L, ProjectDeleteMode.PROJECT_AND_REPOSITORY);

        mockMvc.perform(delete("/api/v1/projects/11").param("deleteMode", "PROJECT_AND_REPOSITORY"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_DELETION_BLOCKED"))
                .andExpect(jsonPath("$.data.deletable").value(false))
                .andExpect(jsonPath("$.data.liveResources.length()").value(2))
                .andExpect(jsonPath("$.data.liveResources[0].type").value("SERVER"))
                .andExpect(jsonPath("$.data.liveResources[0].id").value(7))
                .andExpect(jsonPath("$.data.liveResources[0].projectId").value(11))
                .andExpect(jsonPath("$.data.liveResources[0].label").value("i-0abc"))
                .andExpect(jsonPath("$.data.liveResources[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.data.liveResources[1].type").value("DEPLOYMENT"));
    }

    @Test
    void deletionCheckListsTheSameResources() throws Exception {
        when(projectFacade.getDeletionBlockers(1L, 11L)).thenReturn(List.of(SERVER));

        mockMvc.perform(get("/api/v1/projects/11/deletion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deletable").value(false))
                .andExpect(jsonPath("$.data.liveResources[0].type").value("SERVER"))
                .andExpect(jsonPath("$.data.liveResources[0].label").value("i-0abc"));
    }

    @Test
    void deletionCheckWithNothingLeftIsDeletable() throws Exception {
        when(projectFacade.getDeletionBlockers(1L, 11L)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/projects/11/deletion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deletable").value(true))
                .andExpect(jsonPath("$.data.liveResources.length()").value(0));
    }
}
