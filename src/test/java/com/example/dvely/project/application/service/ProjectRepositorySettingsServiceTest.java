package com.example.dvely.project.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dvely.project.application.port.out.GithubRepositoryPort;
import com.example.dvely.project.application.result.ProjectRepositorySettingsResult;
import com.example.dvely.project.domain.exception.ProjectNotFoundException;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.project.domain.value.ProjectStatus;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectRepositorySettingsServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private GithubRepositoryPort githubRepositoryPort;

    private ProjectRepositorySettingsService service;
    private LocalDateTime now;
    private LocalDateTime connectedAt;
    private LocalDateTime lastSyncedAt;

    @BeforeEach
    void setUp() {
        service = new ProjectRepositorySettingsService(projectRepository, githubRepositoryPort);
        now = LocalDateTime.now();
        // Deliberately distinct from each other (and from `now`): the service copies
        // project.getRepositoryConnectedAt() and project.getRepositoryHeadSyncedAt() into the
        // result's last two constructor args. If those two calls were ever swapped, a fixture
        // where both timestamps were equal would let the bug pass silently.
        connectedAt = now.minusDays(10);
        lastSyncedAt = now.minusHours(2);
    }

    @Test
    void get_returnsAllFieldsAndDerivesRepositoryUrlWhenConnected() {
        Project project = boundProject();
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.of(project));
        when(githubRepositoryPort.getRepository(1L, "octo/repo"))
                .thenReturn(Optional.of(new GithubRepositoryPort.GithubRepository(
                        "octo/repo",
                        "repo",
                        "octo",
                        "test repo",
                        false,
                        "main",
                        OffsetDateTime.now()
                )));

        ProjectRepositorySettingsResult result = service.get(1L, 11L);

        assertThat(result.projectId()).isEqualTo(11L);
        assertThat(result.connected()).isTrue();
        assertThat(result.repositoryFullName()).isEqualTo("octo/repo");
        assertThat(result.repositoryUrl()).isEqualTo("https://github.com/octo/repo");
        assertThat(result.defaultBranch()).isEqualTo("main");
        assertThat(result.repositoryVisibility()).isEqualTo("PUBLIC");
        assertThat(result.bindingStatus()).isEqualTo("BOUND");
        assertThat(result.repositoryHealth()).isEqualTo("HEALTHY");
        assertThat(result.connectedAt()).isEqualTo(connectedAt);
        assertThat(result.lastSyncedAt()).isEqualTo(lastSyncedAt);
    }

    @Test
    void get_defaultBranchDegradesToNullWhenGithubRepositoryIsAbsent() {
        Project project = boundProject();
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.of(project));
        when(githubRepositoryPort.getRepository(1L, "octo/repo")).thenReturn(Optional.empty());

        ProjectRepositorySettingsResult result = service.get(1L, 11L);

        assertThat(result.connected()).isTrue();
        assertThat(result.defaultBranch()).isNull();
    }

    @Test
    void get_defaultBranchDegradesToNullWhenGithubCallThrows() {
        Project project = boundProject();
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.of(project));
        when(githubRepositoryPort.getRepository(1L, "octo/repo"))
                .thenThrow(new RuntimeException("github unreachable"));

        ProjectRepositorySettingsResult result = service.get(1L, 11L);

        assertThat(result.connected()).isTrue();
        assertThat(result.defaultBranch()).isNull();
        // The rest of the response must still reflect the persisted state even though the
        // live branch lookup failed (design: GitHub reachability must not fail the request).
        assertThat(result.repositoryFullName()).isEqualTo("octo/repo");
        assertThat(result.repositoryHealth()).isEqualTo("HEALTHY");
    }

    @Test
    void get_returnsNotConnectedWithNullRepositoryFieldsAndSkipsGithubCall() {
        Project project = emptyProject();
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.of(project));

        ProjectRepositorySettingsResult result = service.get(1L, 11L);

        assertThat(result.connected()).isFalse();
        assertThat(result.repositoryFullName()).isNull();
        assertThat(result.repositoryUrl()).isNull();
        assertThat(result.defaultBranch()).isNull();
        assertThat(result.connectedAt()).isNull();
        assertThat(result.bindingStatus()).isEqualTo("NOT_BOUND");
        assertThat(result.repositoryVisibility()).isEqualTo("PRIVATE");
        assertThat(result.repositoryHealth()).isEqualTo("UNKNOWN_ERROR");

        verifyNoInteractions(githubRepositoryPort);
    }

    @Test
    void get_rejectsOtherUsersProject() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(1L, 11L))
                .isInstanceOf(ProjectNotFoundException.class);

        verifyNoInteractions(githubRepositoryPort);
    }

    private Project emptyProject() {
        return new Project(
                11L,
                1L,
                "my-project",
                ProjectStatus.DRAFT,
                "blank",
                null,
                "fast",
                DeployStatus.DRAFT,
                null,
                null,
                null,
                null,
                RepositoryVisibility.PRIVATE,
                RepositoryBindingStatus.NOT_BOUND,
                RepositoryHealthStatus.UNKNOWN_ERROR,
                false,
                now,
                now
        );
    }

    // Uses the persistence-shaped full constructor (rather than domain mutation methods like
    // bindRepository()) so connectedAt/lastSyncedAt are pinned to deterministic, distinct
    // values instead of two separate LocalDateTime.now() calls that would be flaky to compare
    // (bindRepository()/synchronizeRepositoryHead() each stamp their own "now").
    private Project boundProject() {
        return new Project(
                11L,
                1L,
                "my-project",
                ProjectStatus.ACTIVE,
                "blank",
                null,
                "fast",
                DeployStatus.DRAFT,
                null,
                null,
                "octo/repo",
                "octo/repo",
                RepositoryVisibility.PUBLIC,
                RepositoryBindingStatus.BOUND,
                RepositoryHealthStatus.HEALTHY,
                "abc123",
                "feat: init",
                "octo",
                now,
                lastSyncedAt,
                "v1",
                now,
                connectedAt,
                false,
                now,
                now
        );
    }
}
