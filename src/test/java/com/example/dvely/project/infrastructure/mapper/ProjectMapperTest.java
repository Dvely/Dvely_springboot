package com.example.dvely.project.infrastructure.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.project.application.result.CommitResult;
import com.example.dvely.project.application.result.ProjectRepositorySettingsResult;
import com.example.dvely.project.presentation.dto.response.ProjectRepositorySettingsResponse;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class ProjectMapperTest {

    private final ProjectMapper mapper = new ProjectMapper();

    @Test
    void commitResponseIncludesRelativeTime() {
        var response = mapper.toCommitResponse(new CommitResult(
                "abc123",
                "feat: overview",
                "octo",
                OffsetDateTime.now().minusHours(2)
        ));

        assertThat(response.relativeTime()).isEqualTo("2시간 전");
    }

    @Test
    void repositorySettingsResponseMapsAllTenFieldsPositionally() {
        // Every field below is given a distinct value (including the two LocalDateTime
        // fields) so that a copy-paste transposition in toRepositorySettingsResponse — e.g.
        // swapping connectedAt/lastSyncedAt, or two adjacent String fields — fails this test
        // instead of silently passing because two fixture values happened to be equal.
        LocalDateTime connectedAt = LocalDateTime.of(2026, 1, 1, 10, 0);
        LocalDateTime lastSyncedAt = LocalDateTime.of(2026, 2, 2, 20, 30);
        ProjectRepositorySettingsResult result = new ProjectRepositorySettingsResult(
                11L,
                true,
                "octo/repo",
                "https://github.com/octo/repo",
                "main",
                "PUBLIC",
                "BOUND",
                "HEALTHY",
                connectedAt,
                lastSyncedAt
        );

        ProjectRepositorySettingsResponse response = mapper.toRepositorySettingsResponse(result);

        assertThat(response.projectId()).isEqualTo(11L);
        assertThat(response.connected()).isTrue();
        assertThat(response.repositoryFullName()).isEqualTo("octo/repo");
        assertThat(response.repositoryUrl()).isEqualTo("https://github.com/octo/repo");
        assertThat(response.defaultBranch()).isEqualTo("main");
        assertThat(response.repositoryVisibility()).isEqualTo("PUBLIC");
        assertThat(response.bindingStatus()).isEqualTo("BOUND");
        assertThat(response.repositoryHealth()).isEqualTo("HEALTHY");
        assertThat(response.connectedAt()).isEqualTo(connectedAt);
        assertThat(response.lastSyncedAt()).isEqualTo(lastSyncedAt);
    }
}
