package com.example.dvely.project.application.query;

import com.example.dvely.project.application.port.out.GithubRepositoryPort;
import com.example.dvely.project.application.result.ActivityLogResult;
import com.example.dvely.project.application.result.CommitResult;
import com.example.dvely.project.application.result.ProjectDetailResult;
import com.example.dvely.project.application.result.ProjectOverviewResult;
import com.example.dvely.project.application.result.ProjectSummaryResult;
import com.example.dvely.project.application.result.RepositoryBindingResult;
import com.example.dvely.project.application.result.RepositoryHealthResult;
import com.example.dvely.project.domain.exception.ProjectNotFoundException;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectQueryService {

    private static final int DEFAULT_COMMIT_LIMIT = 20;

    private final ProjectRepository projectRepository;
    private final GithubRepositoryPort githubRepositoryPort;

    public List<ProjectSummaryResult> getProjects(Long ownerUserId) {
        return projectRepository.findAllByOwnerUserIdAndDeletedFalseOrderByUpdatedAtDesc(ownerUserId).stream()
                .map(this::toSummaryResult)
                .toList();
    }

    public ProjectDetailResult getProject(Long ownerUserId, Long projectId) {
        Project project = findProject(ownerUserId, projectId);
        return toDetailResult(project);
    }

    public ProjectOverviewResult getOverview(Long ownerUserId, Long projectId) {
        Project project = findProject(ownerUserId, projectId);

        List<CommitResult> commits = getCommits(ownerUserId, projectId);
        CommitResult latestCommit = commits.isEmpty() ? null : commits.get(0);

        RepositoryHealthResult repositoryHealth = getRepositoryHealth(ownerUserId, projectId);
        List<String> recentChanges = new ArrayList<>();
        recentChanges.add("deploy status: " + project.getDeployStatus().name());
        recentChanges.add("repository binding: " + project.getRepositoryBindingStatus().name());
        if (latestCommit != null) {
            recentChanges.add("latest commit: " + latestCommit.message());
        }

        return new ProjectOverviewResult(
                project.getCurrentUrl(),
                project.getDeployStatus().name(),
                project.getCurrentVersion(),
                recentChanges,
                latestCommit,
                "traffic metric is not connected yet",
                repositoryHealth,
                "managed domain is not connected yet"
        );
    }

    public List<ActivityLogResult> getActivityLogs(Long ownerUserId, Long projectId) {
        Project project = findProject(ownerUserId, projectId);
        List<ActivityLogResult> logs = new ArrayList<>();

        logs.add(new ActivityLogResult(
                "PROJECT_CREATED",
                "프로젝트가 생성되었습니다: " + project.getName(),
                project.getCreatedAt() == null ? OffsetDateTime.now() : project.getCreatedAt().atOffset(OffsetDateTime.now().getOffset())
        ));

        if (project.getSourceRepository() != null) {
            logs.add(new ActivityLogResult(
                    "REPOSITORY_BOUND",
                    "GitHub 저장소가 연결되었습니다: " + project.getSourceRepository(),
                    project.getUpdatedAt() == null ? OffsetDateTime.now() : project.getUpdatedAt().atOffset(OffsetDateTime.now().getOffset())
            ));
        }

        logs.add(new ActivityLogResult(
                "STATUS_CHANGED",
                "프로젝트 상태: " + project.getStatus().name(),
                project.getUpdatedAt() == null ? OffsetDateTime.now() : project.getUpdatedAt().atOffset(OffsetDateTime.now().getOffset())
        ));

        return logs.stream()
                .sorted(Comparator.comparing(ActivityLogResult::occurredAt).reversed())
                .toList();
    }

    public List<CommitResult> getCommits(Long ownerUserId, Long projectId) {
        Project project = findProject(ownerUserId, projectId);
        if (!project.hasSourceRepository()) {
            return List.of();
        }

        return githubRepositoryPort.getRecentCommits(ownerUserId, project.getSourceRepository(), DEFAULT_COMMIT_LIMIT).stream()
                .map(commit -> new CommitResult(commit.sha(), commit.message(), commit.author(), commit.committedAt()))
                .toList();
    }

    public RepositoryBindingResult getRepositoryBinding(Long ownerUserId, Long projectId) {
        Project project = findProject(ownerUserId, projectId);
        return new RepositoryBindingResult(
                project.getSourceRepository(),
                project.getDeploymentRepository(),
                project.getRepositoryVisibility().name(),
                project.getRepositoryBindingStatus().name(),
                project.getRepositoryHealthStatus().name()
        );
    }

    public RepositoryHealthResult getRepositoryHealth(Long ownerUserId, Long projectId) {
        Project project = findProject(ownerUserId, projectId);
        if (!project.hasSourceRepository()) {
            return new RepositoryHealthResult(RepositoryHealthStatus.REPOSITORY_NOT_FOUND.name());
        }

        RepositoryHealthStatus health = githubRepositoryPort.checkRepositoryHealth(ownerUserId, project.getSourceRepository());
        return new RepositoryHealthResult(health.name());
    }

    private Project findProject(Long ownerUserId, Long projectId) {
        return projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, ownerUserId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId, ownerUserId));
    }

    private ProjectSummaryResult toSummaryResult(Project project) {
        return new ProjectSummaryResult(
                project.getId(),
                project.getName(),
                project.getDeployStatus().name(),
                project.getCurrentUrl(),
                project.getUpdatedAt()
        );
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
}
