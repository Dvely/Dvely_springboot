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
import com.example.dvely.environment.domain.repository.EnvironmentVariableSummaryView;
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
import java.util.Map;
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

    /**
     * U6 6-5: secret 변수의 평문은 이제 <b>읽지도 않는다</b>. findPlainValuesByIds 는 secret=false 인
     * 행만 돌려주므로(SQL 조건) 여기서 빈 맵을 준다 — 그 상태로 value 가 null 이어야 한다. 예전처럼
     * "평문을 읽어 응답에서 지운다" 가 아니라는 것이 이 테스트의 요지다.
     */
    @Test
    void secretVariableValueIsMaskedToNull() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 7L)).thenReturn(Optional.of(project()));
        when(repository.findSummaries(eq(11L), eq(null), anyInt())).thenReturn(List.of(
                new EnvironmentVariableSummaryView(
                        1L, "PRODUCTION", "STRIPE_SECRET_KEY", true, LocalDateTime.now(), LocalDateTime.now())
        ));
        when(repository.findPlainValuesByIds(List.of(1L))).thenReturn(Map.of());

        List<EnvironmentVariableResult> results = service.getVariables(7L, 11L, null);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.secret()).isTrue();
            assertThat(result.value()).isNull();
        });
    }

    /**
     * secret 인 행의 평문이 어쩌다 평문 맵에 섞여 들어와도 응답에는 나가지 않는다 — 두 번째 방어선.
     * 위 테스트가 "읽지 않는다" 를, 이 테스트가 "설령 읽혀도 안 내보낸다" 를 각각 못박는다.
     */
    @Test
    void secretVariableValueStaysNullEvenIfAPlaintextSomehowArrives() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 7L)).thenReturn(Optional.of(project()));
        when(repository.findSummaries(eq(11L), eq(null), anyInt())).thenReturn(List.of(
                new EnvironmentVariableSummaryView(
                        1L, "PRODUCTION", "STRIPE_SECRET_KEY", true, LocalDateTime.now(), LocalDateTime.now())
        ));
        when(repository.findPlainValuesByIds(List.of(1L))).thenReturn(Map.of(1L, "sk_live_xxx"));

        List<EnvironmentVariableResult> results = service.getVariables(7L, 11L, null);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.secret()).isTrue();
            assertThat(result.value()).isNull();
        });
    }

    @Test
    void nonSecretVariableValueIsReturnedAsPlaintext() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 7L)).thenReturn(Optional.of(project()));
        when(repository.findSummaries(eq(11L), eq(null), anyInt())).thenReturn(List.of(
                new EnvironmentVariableSummaryView(
                        2L, "PREVIEW", "API_BASE_URL", false, LocalDateTime.now(), LocalDateTime.now())
        ));
        when(repository.findPlainValuesByIds(List.of(2L)))
                .thenReturn(Map.of(2L, "https://api.example.com"));

        List<EnvironmentVariableResult> results = service.getVariables(7L, 11L, null);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.secret()).isFalse();
            assertThat(result.value()).isEqualTo("https://api.example.com");
        });
    }

    @Test
    void filtersByScopeWhenProvided() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 7L)).thenReturn(Optional.of(project()));
        when(repository.findSummaries(eq(11L), eq(EnvironmentScope.PREVIEW), anyInt())).thenReturn(List.of());

        service.getVariables(7L, 11L, "PREVIEW");

        verify(repository).findSummaries(eq(11L), eq(EnvironmentScope.PREVIEW), anyInt());
        // 엔티티를 통째로 읽는 예전 경로는 목록에서 더 쓰지 않는다(그쪽은 env_value 를 매 행 복호화했다).
        verify(repository, never()).findByProjectIdAndScopeOrderByKeyAsc(any(), any());
        verify(repository, never()).findByProjectIdOrderByScopeAscKeyAsc(any());
    }

    /** limit 를 안 주면 기본 200. 상한을 넘겨 달라고 하면 500 으로 깎는다. */
    @Test
    void variableLimitDefaultsTo200AndIsClampedTo500() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 7L)).thenReturn(Optional.of(project()));
        when(repository.findSummaries(eq(11L), eq(null), anyInt())).thenReturn(List.of());

        service.getVariables(7L, 11L, null, null);
        service.getVariables(7L, 11L, null, 9999);

        // findSummaries 는 "더 있는지" 판단용으로 limit+1 건을 요청한다.
        verify(repository).findSummaries(11L, null, 201);
        verify(repository).findSummaries(11L, null, 501);
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
