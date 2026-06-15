package com.example.dvely.project.application.service;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.dto.AgentSubmission;
import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.domain.value.AgentType;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.project.application.command.ProjectCommandService;
import com.example.dvely.project.application.command.dto.CreateProjectCommand;
import com.example.dvely.project.application.result.ProjectCreationResult;
import com.example.dvely.project.application.result.ProjectDetailResult;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProjectCreationService {

    private final ProjectCommandService projectCommandService;
    private final AgentOrchestrator agentOrchestrator;

    public ProjectCreationResult create(Long ownerUserId, CreateProjectCommand command) {
        ProjectDetailResult project = projectCommandService.createProject(ownerUserId, command);
        AgentSubmission generation = agentOrchestrator.submit(
                generationPlan(project),
                ownerUserId,
                null
        );
        return new ProjectCreationResult(project, generation);
    }

    private AgentPlan generationPlan(ProjectDetailResult project) {
        String instruction = buildInstruction(project);
        return new AgentPlan(
                List.of(new AgentStep(
                        AgentType.CODE,
                        Map.of(
                                "instruction", instruction,
                                "targetFile", ""
                        )
                )),
                "프로젝트 생성 옵션에 따라 초기 코드를 생성합니다.",
                providerFor(project.draftMode()),
                project.projectId()
        );
    }

    private String buildInstruction(ProjectDetailResult project) {
        String draftInstruction = "quality".equals(project.draftMode())
                ? "Use a production-quality structure, accessible UI, responsive design, and complete error/empty states."
                : "Create a focused working draft with the minimum clean structure needed for preview.";
        if ("template".equals(project.startMode())) {
            return """
                    Create a new %s template project named "%s".
                    Implement the template as a complete working web application, not a placeholder.
                    %s
                    """.formatted(project.templateType(), project.name(), draftInstruction).trim();
        }
        return """
                Create a new blank web project named "%s".
                Start from a minimal usable page that the user can continue editing in the AI workspace.
                Do not add product-specific sample features beyond a clean starter.
                %s
                """.formatted(project.name(), draftInstruction).trim();
    }

    private AiProvider providerFor(String draftMode) {
        return "quality".equals(draftMode) ? AiProvider.ANTHROPIC : AiProvider.OPENAI;
    }
}
