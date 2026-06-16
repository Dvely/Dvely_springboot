package com.example.dvely.project.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentSubmission;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.domain.value.AgentType;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.project.application.command.ProjectCommandService;
import com.example.dvely.project.application.command.dto.CreateProjectCommand;
import com.example.dvely.project.application.result.ProjectCreationResult;
import com.example.dvely.project.application.result.ProjectDetailResult;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectCreationServiceTest {

    @Mock
    private ProjectCommandService projectCommandService;

    @Mock
    private AgentOrchestrator agentOrchestrator;

    private ProjectCreationService service;

    @BeforeEach
    void setUp() {
        service = new ProjectCreationService(projectCommandService, agentOrchestrator);
    }

    @Test
    void templateQualityCreatesAnthropicCodeTaskForSavedProject() {
        CreateProjectCommand command = new CreateProjectCommand(
                "shop",
                "template",
                "e-commerce",
                "quality"
        );
        ProjectDetailResult project = project(
                "shop",
                "template",
                "e-commerce",
                "quality"
        );
        AgentSubmission submission = new AgentSubmission(
                "task-1",
                TaskStatus.WAITING_APPROVAL,
                List.of(31L)
        );
        when(projectCommandService.createProject(1L, command)).thenReturn(project);
        when(agentOrchestrator.submit(org.mockito.ArgumentMatchers.any(), eq(1L), eq(null)))
                .thenReturn(submission);

        ProjectCreationResult result = service.create(1L, command);

        ArgumentCaptor<AgentPlan> planCaptor = ArgumentCaptor.forClass(AgentPlan.class);
        verify(agentOrchestrator).submit(planCaptor.capture(), eq(1L), eq(null));
        AgentPlan plan = planCaptor.getValue();

        assertThat(result.project()).isEqualTo(project);
        assertThat(result.generation()).isEqualTo(submission);
        assertThat(plan.projectId()).isEqualTo(11L);
        assertThat(plan.aiProvider()).isEqualTo(AiProvider.ANTHROPIC);
        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().getFirst().agentType()).isEqualTo(AgentType.CODE);
        assertThat(plan.steps().getFirst().parameters().get("instruction"))
                .contains("e-commerce", "production-quality");
    }

    @Test
    void blankFastCreatesOpenAiStarterTask() {
        CreateProjectCommand command = new CreateProjectCommand("starter", "blank", null, "fast");
        ProjectDetailResult project = project("starter", "blank", null, "fast");
        when(projectCommandService.createProject(1L, command)).thenReturn(project);
        when(agentOrchestrator.submit(org.mockito.ArgumentMatchers.any(), eq(1L), eq(null)))
                .thenReturn(new AgentSubmission("task-2", TaskStatus.QUEUED, List.of()));

        service.create(1L, command);

        ArgumentCaptor<AgentPlan> planCaptor = ArgumentCaptor.forClass(AgentPlan.class);
        verify(agentOrchestrator).submit(planCaptor.capture(), eq(1L), eq(null));

        assertThat(planCaptor.getValue().aiProvider()).isEqualTo(AiProvider.OPENAI);
        assertThat(planCaptor.getValue().steps().getFirst().parameters().get("instruction"))
                .contains("blank web project", "minimum clean structure");
    }

    private ProjectDetailResult project(String name,
                                        String startMode,
                                        String templateType,
                                        String draftMode) {
        return new ProjectDetailResult(
                11L,
                name,
                "DRAFT",
                startMode,
                templateType,
                draftMode,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }
}
