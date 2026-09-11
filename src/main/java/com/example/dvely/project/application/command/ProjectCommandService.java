package com.example.dvely.project.application.command;

import com.example.dvely.audit.application.AuditEvent;
import com.example.dvely.audit.application.AuditRecorder;
import com.example.dvely.audit.domain.value.AuditAction;
import com.example.dvely.audit.domain.value.AuditActorType;
import com.example.dvely.audit.domain.value.AuditOutcome;
import com.example.dvely.project.application.command.dto.ConnectProjectRepositoryCommand;
import com.example.dvely.project.application.command.dto.CreateProjectCommand;
import com.example.dvely.project.application.command.dto.ProjectDeleteMode;
import com.example.dvely.project.application.command.dto.UpdateProjectCommand;
import com.example.dvely.project.application.port.out.GithubRepositoryPort;
import com.example.dvely.project.application.port.out.UserProfilePort;
import com.example.dvely.project.application.result.ProjectDetailResult;
import com.example.dvely.project.application.result.ProjectRepositoryResult;
import com.example.dvely.project.application.service.ProjectDeletionService;
import com.example.dvely.project.application.service.RepositoryProvisioningService;
import com.example.dvely.project.domain.exception.ProjectNotFoundException;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.service.ProjectDomainService;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import com.example.dvely.template.application.service.TemplateCatalogGuard;
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
    private final AuditRecorder auditRecorder;
    private final RepositoryProvisioningService repositoryProvisioningService;
    private final TemplateCatalogGuard templateCatalogGuard;
    private final ProjectDeletionService projectDeletionService;

    /**
     * 트랜잭션을 걸지 않는다 — 아래 카탈로그 확인이 HTTP 호출이라 그 응답을 기다리는 동안
     * 커넥션이 묶였다(#337). 저장은 {@code save} 한 건뿐이고 그 앞이 전부 검증이라, 카탈로그
     * 확인이 실패하면 저장에 도달하지 않는 것은 그대로다(예전 롤백과 같은 결과).
     */
    public ProjectDetailResult createProject(Long ownerUserId, CreateProjectCommand command) {
        Project project = projectDomainService.create(
                ownerUserId,
                command.name(),
                command.startMode(),
                command.templateType(),
                command.draftMode(),
                RepositoryVisibility.PRIVATE
        );
        // 형식 검증은 도메인이 끝냈다. 실재 여부는 카탈로그에 물어야 하고 그건 네트워크라 여기서 한다.
        // 정규화된 값으로 물어야 한다 — 대소문자·공백이 정리되기 전 값은 카탈로그 ID 와 다르다.
        templateCatalogGuard.ensureExists(project.getTemplateType());
        Project savedProject = projectRepository.save(project);
        return toDetailResult(savedProject);
    }

    /**
     * 트랜잭션을 걸지 않는다 — GitHub 조회·생성과 {@code preparePreviewBranch} 까지 최대 네 번의
     * 외부 호출이 트랜잭션 안에 있었다(#337).
     *
     * <p>실패 시 동작은 그대로다: 외부 호출이 전부 유일한 저장({@code bindToProject} 안의
     * {@code projectRepository.save})보다 앞에 있어, 어느 하나가 던지면 저장에 도달하지 않는다.
     * 저장이 한 건이라 원자성도 줄지 않는다. {@code create} 모드에서 저장소를 만든 뒤 뒷단계가
     * 실패하면 GitHub 에 고아 저장소가 남는 것은 롤백으로도 되돌릴 수 없던 일이라 이전과 같다.</p>
     */
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

        // preview 브랜치 준비부터 저장·감사까지는 저장소 연결 승인 경로와 완전히 같은 순서라
        // 공용 코어에 맡긴다.
        Project savedProject = repositoryProvisioningService.bindToProject(
                project,
                ownerUserId,
                repositoryFullName,
                visibility,
                "create".equals(repositoryMode),
                AuditActorType.USER,
                null,
                "mode=" + repositoryMode + ", visibility=" + visibility
        );
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

    /**
     * 트랜잭션을 걸지 않는다 — 저장소까지 지우는 모드가 GitHub 삭제를 기다리는 동안 커넥션을
     * 붙들고 있었다(#337). 로컬 정리(대화 삭제 + 프로젝트 삭제)는 갈라지면 안 되므로
     * {@link ProjectDeletionService} 의 짧은 트랜잭션 하나로 함께 커밋한다.
     *
     * <p>실패 시 동작은 그대로다: GitHub 삭제가 던지면 로컬 정리에 도달하지 않아 아무것도
     * 지워지지 않는다(예전 롤백과 같은 결과). 반대로 GitHub 삭제가 성공한 뒤 로컬 정리가
     * 실패하는 경우도 이전과 같다 — 되돌릴 수 없는 삭제라 롤백이 해결해 준 적이 없고, 그래서
     * 감사 기록을 그 직후에 남기는 순서(H3)도 그대로 두었다.</p>
     */
    public void deleteProject(Long ownerUserId, Long projectId, ProjectDeleteMode deleteMode) {
        if (deleteMode == ProjectDeleteMode.PROJECT_AND_REPOSITORY) {
            deleteRemoteRepository(ownerUserId, getProject(ownerUserId, projectId));
        }
        projectDeletionService.purge(ownerUserId, projectId, deleteMode);
    }

    private Project getProject(Long ownerUserId, Long projectId) {
        return projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, ownerUserId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId, ownerUserId));
    }

    /** 되돌릴 수 없는 GitHub 삭제만 담당한다 — 트랜잭션 밖에서, 로컬 정리보다 먼저 끝낸다. */
    private void deleteRemoteRepository(Long ownerUserId, Project project) {
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
