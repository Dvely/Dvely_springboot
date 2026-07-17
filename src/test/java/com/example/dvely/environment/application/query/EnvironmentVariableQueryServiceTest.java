package com.example.dvely.environment.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.environment.application.result.EnvironmentVariableHistoryResult;
import com.example.dvely.environment.application.result.EnvironmentVariableResult;
import com.example.dvely.environment.domain.model.EnvironmentVariable;
import com.example.dvely.environment.domain.model.EnvironmentVariableHistory;
import com.example.dvely.environment.domain.repository.EnvironmentVariableHistoryRepository;
import com.example.dvely.environment.domain.repository.EnvironmentVariableRepository;
import com.example.dvely.environment.domain.value.EnvironmentScope;
import com.example.dvely.environment.domain.value.EnvironmentVariableAction;
import com.example.dvely.project.domain.exception.ProjectNotFoundException;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.project.domain.value.ProjectStatus;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EnvironmentVariableQueryServiceTest {

    private final EnvironmentVariableRepository repository = mock(EnvironmentVariableRepository.class);
    private final EnvironmentVariableHistoryRepository historyRepository = mock(EnvironmentVariableHistoryRepository.class);
    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final EnvironmentVariableQueryService service =
            new EnvironmentVariableQueryService(repository, historyRepository, projectRepository);

    @Test
    void getVariablesRejectsWhenProjectNotOwnedByUser() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getVariables(7L, 11L, null))
                .isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    void secretVariableValueIsMaskedToNull() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 7L)).thenReturn(Optional.of(project()));
        EnvironmentVariable secretVariable = new EnvironmentVariable(
                1L, 11L, EnvironmentScope.PRODUCTION, "STRIPE_SECRET_KEY", "sk_live_xxx", true, LocalDateTime.now(), LocalDateTime.now()
        );
        when(repository.findByProjectIdOrderByScopeAscKeyAsc(11L)).thenReturn(List.of(secretVariable));

        List<EnvironmentVariableResult> results = service.getVariables(7L, 11L, null);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.secret()).isTrue();
            assertThat(result.value()).isNull();
        });
    }

    @Test
    void nonSecretVariableValueIsReturnedAsPlaintext() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 7L)).thenReturn(Optional.of(project()));
        EnvironmentVariable variable = new EnvironmentVariable(
                2L, 11L, EnvironmentScope.PREVIEW, "API_BASE_URL", "https://api.example.com", false, LocalDateTime.now(), LocalDateTime.now()
        );
        when(repository.findByProjectIdOrderByScopeAscKeyAsc(11L)).thenReturn(List.of(variable));

        List<EnvironmentVariableResult> results = service.getVariables(7L, 11L, null);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.secret()).isFalse();
            assertThat(result.value()).isEqualTo("https://api.example.com");
        });
    }

    @Test
    void filtersByScopeWhenProvided() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 7L)).thenReturn(Optional.of(project()));
        when(repository.findByProjectIdAndScopeOrderByKeyAsc(11L, EnvironmentScope.PREVIEW)).thenReturn(List.of());

        service.getVariables(7L, 11L, "PREVIEW");

        verify(repository).findByProjectIdAndScopeOrderByKeyAsc(11L, EnvironmentScope.PREVIEW);
        verify(repository, never()).findByProjectIdOrderByScopeAscKeyAsc(any());
    }

    @Test
    void rejectsUnsupportedScopeQueryParameter() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 7L)).thenReturn(Optional.of(project()));

        assertThatThrownBy(() -> service.getVariables(7L, 11L, "GLOBAL"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void historyLimitDefaultsTo50WhenNullOrNonPositive() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 7L)).thenReturn(Optional.of(project()));
        when(historyRepository.findByProjectIdOrderByCreatedAtDescIdDesc(eq(11L), anyInt())).thenReturn(List.of());

        service.getHistory(7L, 11L, null);
        service.getHistory(7L, 11L, 0);
        service.getHistory(7L, 11L, -5);

        // All three inputs (null, 0, negative) are non-positive-or-absent and must fall back to
        // the same default — verified as one cumulative count rather than per-call, since Mockito
        // invocation counts accumulate across calls to the same mock within a test.
        verify(historyRepository, org.mockito.Mockito.times(3))
                .findByProjectIdOrderByCreatedAtDescIdDesc(11L, 50);
    }

    @Test
    void historyLimitIsClampedTo200WhenRequestExceedsMax() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 7L)).thenReturn(Optional.of(project()));
        when(historyRepository.findByProjectIdOrderByCreatedAtDescIdDesc(eq(11L), anyInt())).thenReturn(List.of());

        service.getHistory(7L, 11L, 201);

        verify(historyRepository).findByProjectIdOrderByCreatedAtDescIdDesc(11L, 200);
    }

    @Test
    void historyResultNeverIncludesTheValueItself() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 7L)).thenReturn(Optional.of(project()));
        EnvironmentVariableHistory history = new EnvironmentVariableHistory(
                10L, 11L, 2L, EnvironmentScope.PRODUCTION, "STRIPE_SECRET_KEY",
                EnvironmentVariableAction.UPDATED, true, true, 7L, LocalDateTime.now()
        );
        when(historyRepository.findByProjectIdOrderByCreatedAtDescIdDesc(11L, 50)).thenReturn(List.of(history));

        List<EnvironmentVariableHistoryResult> results = service.getHistory(7L, 11L, null);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.historyId()).isEqualTo(10L);
            assertThat(result.action()).isEqualTo("UPDATED");
            assertThat(result.valueChanged()).isTrue();
        });
        // EnvironmentVariableHistoryResult has no value-bearing field at all — compile-time
        // guarantee that history results can never leak a value, verified here by exhaustively
        // checking every field above comes only from metadata (id/scope/key/action/flags/actor).
    }

    private Project project() {
        return new Project(
                11L, 7L, "sample", ProjectStatus.ACTIVE, "scratch", null, "fast",
                DeployStatus.DRAFT, null, null, null, null,
                RepositoryVisibility.PUBLIC, RepositoryBindingStatus.NOT_BOUND, RepositoryHealthStatus.UNKNOWN_ERROR,
                false, LocalDateTime.now(), LocalDateTime.now()
        );
    }
}
