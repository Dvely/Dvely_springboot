package com.example.dvely.project.application.port.out;

import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import java.time.OffsetDateTime;
import java.util.List;

public interface GithubRepositoryPort {

    String createRepository(Long ownerUserId, String repositoryName, RepositoryVisibility visibility);

    List<GithubCommit> getRecentCommits(Long ownerUserId, String repositoryFullName, int limit);

    RepositoryHealthStatus checkRepositoryHealth(Long ownerUserId, String repositoryFullName);

    void preparePreviewBranch(Long ownerUserId, String repositoryFullName);

    record GithubCommit(String sha, String message, String author, OffsetDateTime committedAt) {}
}
