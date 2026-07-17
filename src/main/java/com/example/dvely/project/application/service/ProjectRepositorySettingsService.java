package com.example.dvely.project.application.service;

import com.example.dvely.project.application.port.out.GithubRepositoryPort;
import com.example.dvely.project.application.result.ProjectRepositorySettingsResult;
import com.example.dvely.project.domain.exception.ProjectNotFoundException;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read model for the project's "Repository Settings" screen (BI-162). Kept as its own
 * dedicated service — mirroring the {@code ProjectChatSettingsService}/
 * {@code ProjectInfrastructureSettingsService} precedent — rather than folding into
 * {@code ProjectQueryService}, since this is a single settings-screen read with its own
 * live-lookup concern.
 * <p>
 * Unlike {@code repositoryHealth} (returned as-is from the stored value, see D5/D6 in the
 * design), {@code defaultBranch} cannot be persisted meaningfully — it can change on GitHub's
 * side at any time — so it is always fetched live when a repository is connected. A failed or
 * empty lookup degrades to {@code null} instead of failing the whole request, following the
 * same catch-fallback shape as {@code ProjectQueryService#getRepositoryHealth}.
 */
@Service
@RequiredArgsConstructor
public class ProjectRepositorySettingsService {

    private static final String GITHUB_BASE_URL = "https://github.com/";

    private final ProjectRepository projectRepository;
    private final GithubRepositoryPort githubRepositoryPort;

    @Transactional(readOnly = true)
    public ProjectRepositorySettingsResult get(Long ownerUserId, Long projectId) {
        Project project = findProject(ownerUserId, projectId);
        boolean connected = project.hasSourceRepository();

        // Unconnected projects still return 200 (design D6) so the settings screen can render
        // a "connect repository" prompt instead of treating the project itself as missing.
        String repositoryFullName = connected ? project.getSourceRepository() : null;
        String repositoryUrl = connected ? GITHUB_BASE_URL + repositoryFullName : null;
        String defaultBranch = connected ? resolveDefaultBranch(ownerUserId, repositoryFullName) : null;

        return new ProjectRepositorySettingsResult(
                projectId,
                connected,
                repositoryFullName,
                repositoryUrl,
                defaultBranch,
                project.getRepositoryVisibility().name(),
                project.getRepositoryBindingStatus().name(),
                project.getRepositoryHealthStatus().name(),
                project.getRepositoryConnectedAt(),
                project.getRepositoryHeadSyncedAt()
        );
    }

    private String resolveDefaultBranch(Long ownerUserId, String repositoryFullName) {
        try {
            return githubRepositoryPort.getRepository(ownerUserId, repositoryFullName)
                    .map(GithubRepositoryPort.GithubRepository::defaultBranch)
                    .orElse(null);
        } catch (RuntimeException exception) {
            // GitHub reachability issues must not fail the settings screen — the rest of the
            // response (persisted fields) is still valid and useful without the live branch.
            return null;
        }
    }

    private Project findProject(Long ownerUserId, Long projectId) {
        return projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, ownerUserId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId, ownerUserId));
    }
}
