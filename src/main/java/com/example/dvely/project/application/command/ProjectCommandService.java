package com.example.dvely.project.application.command;

import com.example.dvely.project.application.command.dto.CreateProjectCommand;
import com.example.dvely.project.application.command.dto.CreateRepositoryBindingCommand;
import com.example.dvely.project.application.command.dto.UpdateProjectCommand;
import com.example.dvely.project.application.command.dto.UpdateRepositoryBindingCommand;
import com.example.dvely.project.application.port.out.GithubRepositoryPort;
import com.example.dvely.project.application.port.out.UserProfilePort;
import com.example.dvely.project.application.result.ProjectDetailResult;
import com.example.dvely.project.application.result.RepositoryBindingResult;
import com.example.dvely.project.domain.exception.ProjectNotFoundException;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.service.ProjectDomainService;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProjectCommandService {

    private final ProjectRepository projectRepository;
    private final ProjectDomainService projectDomainService;
    private final GithubRepositoryPort githubRepositoryPort;
    private final UserProfilePort userProfilePort;

    @Transactional
    public ProjectDetailResult createProject(Long ownerUserId, CreateProjectCommand command) {
        RepositoryVisibility visibility = RepositoryVisibility.from(command.repositoryVisibility());

        Project project = projectDomainService.create(
                ownerUserId,
                command.name(),
                command.startMode(),
                command.templateType(),
                normalizeDraftMode(command.draftMode()),
                visibility
        );

        Project savedProject = projectRepository.save(project);
        return toDetailResult(savedProject);
    }

    @Transactional
    public ProjectDetailResult updateProject(Long ownerUserId, Long projectId, UpdateProjectCommand command) {
        Project project = getProject(ownerUserId, projectId);
        projectDomainService.rename(project, command.name());
        Project savedProject = projectRepository.save(project);
        return toDetailResult(savedProject);
    }

    @Transactional
    public void deleteProject(Long ownerUserId, Long projectId) {
        Project project = getProject(ownerUserId, projectId);
        projectDomainService.delete(project);
        projectRepository.save(project);
    }

    @Transactional
    public RepositoryBindingResult createRepositoryBinding(Long ownerUserId,
                                                           Long projectId,
                                                           CreateRepositoryBindingCommand command) {
        Project project = getProject(ownerUserId, projectId);
        RepositoryVisibility visibility = RepositoryVisibility.from(command.visibility());

        // 1. 바인딩 유형에 따라 저장소 연결 정보를 결정한다.
        if ("create".equalsIgnoreCase(command.bindingType())) {
            String repositoryName = requireText(command.repositoryName(), "repositoryName");
            String createdRepository = githubRepositoryPort.createRepository(ownerUserId, repositoryName, visibility);
            if (createdRepository == null || createdRepository.isBlank()) {
                String githubLogin = userProfilePort.getGithubLogin(ownerUserId);
                projectDomainService.bindRepository(
                        project,
                        command.bindingType(),
                        command.repositoryFullName(),
                        repositoryName,
                        githubLogin,
                        visibility
                );
            } else {
                project.bindRepository(createdRepository, createdRepository, visibility);
            }
        } else {
            projectDomainService.bindRepository(
                    project,
                    command.bindingType(),
                    command.repositoryFullName(),
                    command.repositoryName(),
                    null,
                    visibility
            );
        }

        // 2. preview 브랜치 준비를 위임한다.
        githubRepositoryPort.preparePreviewBranch(project.getSourceRepository());

        Project savedProject = projectRepository.save(project);
        return toBindingResult(savedProject);
    }

    @Transactional
    public RepositoryBindingResult updateRepositoryBinding(Long ownerUserId,
                                                           Long projectId,
                                                           UpdateRepositoryBindingCommand command) {
        Project project = getProject(ownerUserId, projectId);
        RepositoryVisibility visibility = command.visibility() == null
                ? project.getRepositoryVisibility()
                : RepositoryVisibility.from(command.visibility());

        projectDomainService.updateRepositoryBinding(project, command.deploymentRepository(), visibility);
        Project savedProject = projectRepository.save(project);
        return toBindingResult(savedProject);
    }

    private Project getProject(Long ownerUserId, Long projectId) {
        return projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, ownerUserId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId, ownerUserId));
    }

    private String normalizeDraftMode(String draftMode) {
        if (draftMode == null || draftMode.isBlank()) {
            return "fast";
        }
        return draftMode.trim();
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private ProjectDetailResult toDetailResult(Project project) {
        return new ProjectDetailResult(
                project.getId(),
                project.getName(),
                project.getStatus().name(),
                project.getStartMode(),
                project.getTemplateType(),
                project.getDraftMode(),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }

    private RepositoryBindingResult toBindingResult(Project project) {
        return new RepositoryBindingResult(
                project.getSourceRepository(),
                project.getDeploymentRepository(),
                project.getRepositoryVisibility().name(),
                project.getRepositoryBindingStatus().name(),
                project.getRepositoryHealthStatus().name()
        );
    }
}
