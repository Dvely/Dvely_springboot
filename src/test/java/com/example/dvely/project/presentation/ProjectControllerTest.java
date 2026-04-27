package com.example.dvely.project.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.project.application.command.dto.CreateProjectCommand;
import com.example.dvely.project.application.facade.ProjectFacade;
import com.example.dvely.project.application.result.ProjectDetailResult;
import com.example.dvely.project.application.result.ProjectSummaryResult;
import com.example.dvely.project.infrastructure.mapper.ProjectMapper;
import com.example.dvely.project.presentation.dto.request.CreateProjectRequest;
import com.example.dvely.project.presentation.dto.response.ProjectCreateResponse;
import com.example.dvely.project.presentation.dto.response.ProjectSummaryResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectControllerTest {

    @Mock
    private ProjectFacade projectFacade;

    @Mock
    private ProjectMapper projectMapper;

    @InjectMocks
    private ProjectController projectController;

    @Test
    void getProjects_delegatesUsingAuthenticatedUserId() {
        LocalDateTime now = LocalDateTime.now();
        ProjectSummaryResult result = new ProjectSummaryResult(1L, "my-project", "DRAFT", null, now);
        ProjectSummaryResponse response = new ProjectSummaryResponse(1L, "my-project", "DRAFT", null, now, "방금 전");

        when(projectFacade.getProjects(1L)).thenReturn(List.of(result));
        when(projectMapper.toSummaryResponse(result)).thenReturn(response);

        List<ProjectSummaryResponse> responses = projectController.getProjects(1L);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).projectId()).isEqualTo(1L);
        assertThat(responses.get(0).name()).isEqualTo("my-project");
        verify(projectFacade).getProjects(1L);
    }

    @Test
    void createProject_delegatesUsingAuthenticatedUserIdAndRequestBody() {
        CreateProjectRequest request = new CreateProjectRequest(
                "my-landing",
                "blank",
                null,
                "fast",
                "private"
        );

        ProjectDetailResult result = new ProjectDetailResult(
                11L,
                "my-landing",
                "DRAFT",
                "blank",
                null,
                "fast",
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        ProjectCreateResponse response = new ProjectCreateResponse(11L, "my-landing", "DRAFT");

        when(projectFacade.createProject(eq(1L), any(CreateProjectCommand.class))).thenReturn(result);
        when(projectMapper.toCreateResponse(result)).thenReturn(response);

        ProjectCreateResponse actual = projectController.createProject(1L, request);

        ArgumentCaptor<CreateProjectCommand> commandCaptor = ArgumentCaptor.forClass(CreateProjectCommand.class);
        verify(projectFacade).createProject(eq(1L), commandCaptor.capture());
        CreateProjectCommand captured = commandCaptor.getValue();

        assertThat(captured.name()).isEqualTo("my-landing");
        assertThat(captured.startMode()).isEqualTo("blank");
        assertThat(captured.draftMode()).isEqualTo("fast");
        assertThat(captured.repositoryVisibility()).isEqualTo("private");

        assertThat(actual.projectId()).isEqualTo(11L);
        assertThat(actual.name()).isEqualTo("my-landing");
        assertThat(actual.status()).isEqualTo("DRAFT");
    }
}
