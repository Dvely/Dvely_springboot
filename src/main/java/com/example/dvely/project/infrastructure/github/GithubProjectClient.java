package com.example.dvely.project.infrastructure.github;

import com.example.dvely.project.application.port.out.GithubRepositoryPort;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class GithubProjectClient implements GithubRepositoryPort {

    @Override
    public String createRepository(Long ownerUserId, String repositoryName, RepositoryVisibility visibility) {
        // TODO: auth/user 모듈 완료 후 사용자 GitHub 토큰 기반 실제 생성 API로 교체
        return "user" + ownerUserId + "/" + repositoryName;
    }

    @Override
    public List<GithubCommit> getRecentCommits(String repositoryFullName, int limit) {
        // TODO: GitHub REST API(/repos/{owner}/{repo}/commits) 연동으로 교체
        return List.of(
                new GithubCommit("a1b2c3d", "feat: initial project setup", "dvely-bot", OffsetDateTime.now().minusHours(2)),
                new GithubCommit("e4f5g6h", "fix: update project metadata", "dvely-bot", OffsetDateTime.now().minusDays(1)),
                new GithubCommit("i7j8k9l", "chore: prepare preview branch", "dvely-bot", OffsetDateTime.now().minusDays(2))
        ).stream().limit(limit).toList();
    }

    @Override
    public RepositoryHealthStatus checkRepositoryHealth(String repositoryFullName) {
        if (repositoryFullName == null || repositoryFullName.isBlank()) {
            return RepositoryHealthStatus.REPOSITORY_NOT_FOUND;
        }
        if (repositoryFullName.contains("forbidden")) {
            return RepositoryHealthStatus.ACCESS_DENIED;
        }
        if (repositoryFullName.contains("permission-mismatch")) {
            return RepositoryHealthStatus.PERMISSION_MISMATCH;
        }
        return RepositoryHealthStatus.HEALTHY;
    }

    @Override
    public void preparePreviewBranch(String repositoryFullName) {
        // TODO: preview 브랜치 존재 확인/생성 로직 추가
    }
}
