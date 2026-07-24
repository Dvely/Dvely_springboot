package com.example.dvely.project.application.command;

import com.example.dvely.audit.application.AuditEvent;
import com.example.dvely.audit.application.AuditRecorder;
import com.example.dvely.audit.domain.value.AuditAction;
import com.example.dvely.audit.domain.value.AuditActorType;
import com.example.dvely.audit.domain.value.AuditOutcome;
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
    private final AuditRecorder auditRecorder;

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
        // H1 (design §4): "create" mode actually created a new GitHub repository (a GITHUB-scope
        // write distinct from "connected an existing one") — recorded after save, once binding is
        // durable, matching the "record after external effect + state confirmed" rule (design §4
        // intro).
        auditRecorder.record(new AuditEvent(
                "create".equals(repositoryMode) ? AuditAction.REPOSITORY_CREATED : AuditAction.REPOSITORY_CONNECTED,
                AuditOutcome.SUCCEEDED,
                AuditActorType.USER,
                ownerUserId,
                savedProject.getId(),
                "REPOSITORY",
                repositoryFullName,
                null,
                null,
                "mode=" + repositoryMode + ", visibility=" + visibility,
                null
        ));
        return toRepositoryResult(savedProject);
    }

    /**
     * Disconnects the GitHub repository binding from a project.
     * <p>
     * This is intentionally non-destructive (design D2/D3): the method only clears DB fields
     * via {@link Project#unbindRepository()} and never calls {@link GithubRepositoryPort} —
     * the GitHub repository, its workflows, and any published GitHub Pages site are left
     * untouched. Derived state in other domains (webhook sync, deployment history, domain
     * binding, in-flight agent tasks) is not inspected or cleaned up here; each naturally
     * disconnects on its own once {@code sourceRepository} is null (see design D3), which
     * keeps this service inside the project domain's own boundary. Because there is no
     * external call, the single {@code save} below is the only side effect and the whole
     * operation is atomic within the transaction.
     * <p>
     * Race with a concurrent webhook head-sync write (see
     * {@code WebhookEventHandler}/{@code synchronizeRepositoryHead}): both are guarded now
     * (Issue #45) by {@code Project}'s optimistic version — whichever {@code save} commits
     * second sees a stale version and gets {@code ObjectOptimisticLockingFailureException}
     * instead of silently discarding the other write. This is the "사용자 대면" policy (design
     * I45 §2): the exception propagates out of this {@code @Transactional} method to
     * {@code GlobalExceptionHandler} as 409, and the caller is expected to retry — no automatic
     * re-apply here, since the user already saw the state their disconnect click was based on.
     */
    @Transactional
    public void disconnectRepository(Long ownerUserId, Long projectId) {
        Project project = getProject(ownerUserId, projectId);
        // H2 (design §4): read before unbindRepository() clears it — the audit row's resource_id
        // must name the repository that *was* connected, not the null it becomes afterward.
        String disconnectedRepository = project.getSourceRepository();
        project.unbindRepository();
        Project savedProject = projectRepository.save(project);
        auditRecorder.record(new AuditEvent(
                AuditAction.REPOSITORY_DISCONNECTED,
                AuditOutcome.SUCCEEDED,
                AuditActorType.USER,
                ownerUserId,
                savedProject.getId(),
                "REPOSITORY",
                disconnectedRepository,
                null,
                null,
                null,
                null
        ));
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

        String deletedRepository = project.getSourceRepository();
        githubRepositoryPort.deleteRepository(ownerUserId, deletedRepository);
        // H3 (design §4): recorded right after the real, irreversible GitHub deletion succeeds —
        // deliberately before the DB cleanup below finishes, so this record's REQUIRES_NEW commit
        // survives even if something after this line fails and rolls the outer transaction back
        // (the deletion itself already happened and cannot be undone by a DB rollback — design §4
        // H3 note / ADR-A2 "committed vs. requested" semantics).
        auditRecorder.record(new AuditEvent(
                AuditAction.REPOSITORY_DELETED,
                AuditOutcome.SUCCEEDED,
                AuditActorType.USER,
                ownerUserId,
                project.getId(),
                "REPOSITORY",
                deletedRepository,
                null,
                null,
                null,
                null
        ));
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
