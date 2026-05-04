package com.example.dvely.project.application.port.out;

import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface GithubRepositoryPort {

    List<GithubRepository> listRepositories(Long ownerUserId);

    Optional<GithubRepository> getRepository(Long ownerUserId, String repositoryFullName);

    String createRepository(Long ownerUserId, String repositoryName, RepositoryVisibility visibility);

    boolean repositoryExists(Long ownerUserId, String repositoryFullName);

    List<GithubCommit> getRecentCommits(Long ownerUserId, String repositoryFullName, int limit);

    RepositoryHealthStatus checkRepositoryHealth(Long ownerUserId, String repositoryFullName);

    void deleteRepository(Long ownerUserId, String repositoryFullName);

    void preparePreviewBranch(Long ownerUserId, String repositoryFullName);

    record GithubRepository(
            String fullName,
            String name,
            String owner,
            String description,
            boolean privateRepository,
            String defaultBranch,
            OffsetDateTime updatedAt
    ) {}

    record GithubCommit(String sha, String message, String author, OffsetDateTime committedAt) {}
}
