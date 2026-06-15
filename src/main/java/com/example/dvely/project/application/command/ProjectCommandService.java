package com.example.dvely.project.application.command;

import com.example.dvely.chat.application.command.ChatCommandService;
import com.example.dvely.project.application.command.dto.ConnectProjectRepositoryCommand;
import com.example.dvely.project.application.command.dto.CreateProjectCommand;
import com.example.dvely.project.application.command.dto.ProjectDeleteMode;
import com.example.dvely.project.application.command.dto.UpdateProjectCommand;
import com.example.dvely.project.application.port.out.GithubRepositoryPort;
import com.example.dvely.project.application.port.out.UserProfilePort;
import com.example.dvely.project.application.result.ProjectDetailResult;
import com.example.dvely.project.application.result.ProjectRepositoryResult;
import com.example.dvely.project.domain.exception.ProjectNotFoundException;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.service.ProjectDomainService;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import java.util.Locale;
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
    private final ChatCommandService chatCommandService;

    @Transactional
    public ProjectDetailResult createProject(Long ownerUserId, CreateProjectCommand command) {
        Project project = projectDomainService.create(
                ownerUserId,
                command.name(),
                command.startMode(),
                command.templateType(),
                command.draftMode(),
                RepositoryVisibility.PRIVATE
        );
        Project savedProject = projectRepository.save(project);
        return toDetailResult(savedProject);
    }

    @Transactional
    public ProjectRepositoryResult connectRepository(Long ownerUserId,
                                                     Long projectId,
                                                     ConnectProjectRepositoryCommand command) {
        Project project = getProject(ownerUserId, projectId);
        if (project.hasSourceRepository()) {
            throw new IllegalStateException("이미 GitHub 저장소가 연결된 프로젝트입니다: " + project.getSourceRepository());
        }

        RepositoryVisibility visibility = RepositoryVisibility.from(command.repositoryVisibility());
        String repositoryMode = normalizeRepositoryMode(command.repositoryMode());
        String repositoryFullName;

        if ("existing".equals(repositoryMode)) {
            repositoryFullName = normalizeRepositoryFullName(command.repositoryFullName());
            var repository = githubRepositoryPort.getRepository(ownerUserId, repositoryFullName)
                    .orElseThrow(() -> new IllegalArgumentException("GitHub 저장소를 찾을 수 없거나 접근 권한이 없습니다: " + repositoryFullName));
            visibility = repository.privateRepository() ? RepositoryVisibility.PRIVATE : RepositoryVisibility.PUBLIC;
        } else {
            String repositoryName = requireText(command.repositoryName(), "repositoryName");
            String githubLogin = userProfilePort.getGithubLogin(ownerUserId);
            String candidateRepositoryFullName = githubLogin + "/" + repositoryName;
            if (githubRepositoryPort.repositoryExists(ownerUserId, candidateRepositoryFullName)) {
                throw new IllegalStateException("GitHub 저장소 이름이 이미 존재합니다: " + candidateRepositoryFullName);
            }
            repositoryFullName = githubRepositoryPort.createRepository(ownerUserId, repositoryName, visibility);
        }

        githubRepositoryPort.preparePreviewBranch(ownerUserId, repositoryFullName);
        project.bindRepository(repositoryFullName, visibility);
        project.updateRepositoryHealth(RepositoryHealthStatus.HEALTHY);

        Project savedProject = projectRepository.save(project);
        return toRepositoryResult(savedProject);
    }

    @Transactional
    public ProjectDetailResult updateProject(Long ownerUserId, Long projectId, UpdateProjectCommand command) {
        Project project = getProject(ownerUserId, projectId);
        projectDomainService.rename(project, command.name());
        Project savedProject = projectRepository.save(project);
        return toDetailResult(savedProject);
    }

    @Transactional
    public void deleteProject(Long ownerUserId, Long projectId, ProjectDeleteMode deleteMode) {
        Project project = getProject(ownerUserId, projectId);

        if (deleteMode == ProjectDeleteMode.PROJECT_AND_REPOSITORY) {
            deleteProjectAndRepository(ownerUserId, project);
            return;
        }

        chatCommandService.trashConversationsForProject(ownerUserId, projectId);
        projectDomainService.delete(project);
        projectRepository.save(project);
    }

    private Project getProject(Long ownerUserId, Long projectId) {
        return projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, ownerUserId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId, ownerUserId));
    }

    private void deleteProjectAndRepository(Long ownerUserId, Project project) {
        if (!project.hasSourceRepository()) {
            throw new IllegalStateException("프로젝트에 연결된 저장소가 없습니다.");
        }

        githubRepositoryPort.deleteRepository(ownerUserId, project.getSourceRepository());
        chatCommandService.deleteConversationsForProject(ownerUserId, project.getId());
        projectDomainService.delete(project);
        projectRepository.save(project);
    }

    private String normalizeRepositoryMode(String repositoryMode) {
        if (repositoryMode == null || repositoryMode.isBlank()) {
            return "create";
        }

        String value = repositoryMode.trim().toLowerCase(Locale.ROOT).replace("-", "_");
        if ("create".equals(value) || "create_new".equals(value) || "new".equals(value)) {
            return "create";
        }
        if ("existing".equals(value) || "import".equals(value) || "import_existing".equals(value)) {
            return "existing";
        }
        throw new IllegalArgumentException("repositoryMode must be create or existing");
    }

    private String normalizeRepositoryFullName(String repositoryFullName) {
        String value = requireText(repositoryFullName, "repositoryFullName");
        String[] parts = value.split("/", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("repositoryFullName must be in owner/repo format");
        }
        return parts[0].trim() + "/" + parts[1].trim();
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

    private ProjectRepositoryResult toRepositoryResult(Project project) {
        return new ProjectRepositoryResult(
                project.getId(),
                project.getSourceRepository(),
                project.getRepositoryVisibility().name(),
                project.getRepositoryBindingStatus().name(),
                project.getRepositoryHealthStatus().name()
        );
    }

}
