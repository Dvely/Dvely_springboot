package com.example.dvely.environment.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.environment.application.query.EnvironmentVariableQueryService;
import com.example.dvely.environment.application.result.EnvironmentVariableResult;
import com.example.dvely.environment.domain.model.EnvironmentVariable;
import com.example.dvely.environment.domain.model.EnvironmentVariableHistory;
import com.example.dvely.environment.domain.repository.EnvironmentVariableHistoryRepository;
import com.example.dvely.environment.domain.repository.EnvironmentVariableRepository;
import com.example.dvely.environment.domain.value.EnvironmentScope;
import com.example.dvely.environment.domain.value.EnvironmentVariableAction;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.project.domain.value.ProjectStatus;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Review follow-up: {@code queryService} is now the REAL {@link EnvironmentVariableQueryService}
 * (wrapped in a {@code spy} only so ownership-check invocations can still be verified) instead of
 * a mock whose {@code toResult(...)} the test used to re-implement by hand. That re-implementation
 * meant a bug in the production masking rule (D4: {@code secret ? null : value}) could never fail
 * this suite — it was testing a second, parallel copy of the logic, not the real one. Only
 * {@code ProjectRepository} is mocked now (the actual DB boundary), so every assertion here
 * exercises the production masking/lookup code.
 */
class EnvironmentVariableCommandServiceTest {

    private final EnvironmentVariableRepository repository = mock(EnvironmentVariableRepository.class);
    private final EnvironmentVariableHistoryRepository historyRepository = mock(EnvironmentVariableHistoryRepository.class);
    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final EnvironmentVariableQueryService queryService =
            spy(new EnvironmentVariableQueryService(repository, historyRepository, projectRepository));
    private final EnvironmentVariableCommandService service =
            new EnvironmentVariableCommandService(repository, historyRepository, queryService);

    private void stubProjectOwnership() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 7L)).thenReturn(Optional.of(project()));
    }

    @Test
    void createsVariableAndRecordsCreatedHistory() {
        stubProjectOwnership();
        when(repository.findByProjectIdAndScopeAndKey(11L, EnvironmentScope.PREVIEW, "API_KEY"))
                .thenReturn(Optional.empty());
        when(repository.save(any(EnvironmentVariable.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EnvironmentVariableResult result = service.create(7L, 11L, "PREVIEW", "API_KEY", "value", false);

        assertThat(result.key()).isEqualTo("API_KEY");
        assertThat(result.value()).isEqualTo("value");

        ArgumentCaptor<EnvironmentVariableHistory> historyCaptor = ArgumentCaptor.forClass(EnvironmentVariableHistory.class);
        verify(historyRepository).save(historyCaptor.capture());
        EnvironmentVariableHistory history = historyCaptor.getValue();
        assertThat(history.getAction()).isEqualTo(EnvironmentVariableAction.CREATED);
        assertThat(history.isValueChanged()).isTrue();
        assertThat(history.getActorUserId()).isEqualTo(7L);
        verify(queryService).assertProjectOwner(7L, 11L);
    }

    @Test
    void creatingASecretVariableMasksValueToNullInTheResponse() {
        // Exercises the real EnvironmentVariableQueryService#toResult masking rule (D4) end to
        // end through the command path — this is the exact gap the review flagged.
        stubProjectOwnership();
        when(repository.findByProjectIdAndScopeAndKey(11L, EnvironmentScope.PRODUCTION, "STRIPE_SECRET_KEY"))
                .thenReturn(Optional.empty());
        when(repository.save(any(EnvironmentVariable.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EnvironmentVariableResult result =
                service.create(7L, 11L, "PRODUCTION", "STRIPE_SECRET_KEY", "sk_live_xxx", true);

        assertThat(result.secret()).isTrue();
        assertThat(result.value()).isNull();
    }

    @Test
    void rejectsCreateWhenSameProjectScopeKeyAlreadyExists() {
        stubProjectOwnership();
        EnvironmentVariable existing = new EnvironmentVariable(11L, EnvironmentScope.PREVIEW, "API_KEY", "old", false);
        when(repository.findByProjectIdAndScopeAndKey(11L, EnvironmentScope.PREVIEW, "API_KEY"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(7L, 11L, "PREVIEW", "API_KEY", "value", false))
                .isInstanceOf(IllegalStateException.class);
        verify(repository, never()).save(any());
        verify(historyRepository, never()).save(any());
    }

    @Test
    void translatesRaceConditionDuplicateIntoTheSameConflictException() {
        // Pre-check passes (no concurrent row visible yet), but the DB unique constraint catches
        // a genuinely concurrent insert at flush time — this must surface as the same
        // IllegalStateException shape as the pre-check, not a raw 500.
        stubProjectOwnership();
        when(repository.findByProjectIdAndScopeAndKey(11L, EnvironmentScope.PREVIEW, "API_KEY"))
                .thenReturn(Optional.empty());
        when(repository.save(any(EnvironmentVariable.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.create(7L, 11L, "PREVIEW", "API_KEY", "value", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API_KEY");
        verify(historyRepository, never()).save(any());
    }

    @Test
    void rejectsUnsupportedScopeString() {
        stubProjectOwnership();

        assertThatThrownBy(() -> service.create(7L, 11L, "GLOBAL", "API_KEY", "value", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updatesValueAndRecordsValueChangedTrueWhenValueDiffers() {
        stubProjectOwnership();
        EnvironmentVariable existing = new EnvironmentVariable(1L, 11L, EnvironmentScope.PREVIEW, "API_KEY", "old", false, null, null);
        doReturn(existing).when(queryService).findOwned(11L, 1L);
        when(repository.save(any(EnvironmentVariable.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EnvironmentVariableResult result = service.update(7L, 11L, 1L, "new", null);

        assertThat(result.value()).isEqualTo("new");
        ArgumentCaptor<EnvironmentVariableHistory> historyCaptor = ArgumentCaptor.forClass(EnvironmentVariableHistory.class);
        verify(historyRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().isValueChanged()).isTrue();
        assertThat(historyCaptor.getValue().getAction()).isEqualTo(EnvironmentVariableAction.UPDATED);
    }

    @Test
    void updatesRecordValueChangedFalseWhenNewValueEqualsCurrentValue() {
        stubProjectOwnership();
        EnvironmentVariable existing = new EnvironmentVariable(1L, 11L, EnvironmentScope.PREVIEW, "API_KEY", "same", false, null, null);
        doReturn(existing).when(queryService).findOwned(11L, 1L);
        when(repository.save(any(EnvironmentVariable.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.update(7L, 11L, 1L, "same", null);

        ArgumentCaptor<EnvironmentVariableHistory> historyCaptor = ArgumentCaptor.forClass(EnvironmentVariableHistory.class);
        verify(historyRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().isValueChanged()).isFalse();
    }

    @Test
    void updatingASecretVariableAlwaysRecordsValueChangedTrueWithoutComparingPlaintext() {
        // Review follow-up: comparing plaintext for an already-secret variable would leak a
        // 1-bit oracle (does the submitted value exactly match the stored one?) through the
        // history log. Even when the submitted value happens to equal the stored one, this must
        // still record valueChanged=true — the comparison itself must never run.
        stubProjectOwnership();
        EnvironmentVariable existing = new EnvironmentVariable(1L, 11L, EnvironmentScope.PRODUCTION, "STRIPE_SECRET_KEY", "sk_live_same", true, null, null);
        doReturn(existing).when(queryService).findOwned(11L, 1L);
        when(repository.save(any(EnvironmentVariable.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.update(7L, 11L, 1L, "sk_live_same", null);

        ArgumentCaptor<EnvironmentVariableHistory> historyCaptor = ArgumentCaptor.forClass(EnvironmentVariableHistory.class);
        verify(historyRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().isValueChanged()).isTrue();
    }

    @Test
    void promotingToSecretWhileChangingValueAlsoRecordsValueChangedTrueUnconditionally() {
        // Same oracle-closing rule applies the moment a variable *becomes* secret in this same
        // request, not only when it already was secret.
        stubProjectOwnership();
        EnvironmentVariable existing = new EnvironmentVariable(1L, 11L, EnvironmentScope.PREVIEW, "API_KEY", "same-value", false, null, null);
        doReturn(existing).when(queryService).findOwned(11L, 1L);
        when(repository.save(any(EnvironmentVariable.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.update(7L, 11L, 1L, "same-value", true);

        ArgumentCaptor<EnvironmentVariableHistory> historyCaptor = ArgumentCaptor.forClass(EnvironmentVariableHistory.class);
        verify(historyRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().isValueChanged()).isTrue();
    }

    @Test
    void promotesToSecretOnlyAndMasksValueInTheResponse() {
        stubProjectOwnership();
        EnvironmentVariable existing = new EnvironmentVariable(1L, 11L, EnvironmentScope.PREVIEW, "API_KEY", "value", false, null, null);
        doReturn(existing).when(queryService).findOwned(11L, 1L);
        when(repository.save(any(EnvironmentVariable.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EnvironmentVariableResult result = service.update(7L, 11L, 1L, null, true);

        assertThat(result.secret()).isTrue();
        assertThat(result.value()).isNull();
        ArgumentCaptor<EnvironmentVariableHistory> historyCaptor = ArgumentCaptor.forClass(EnvironmentVariableHistory.class);
        verify(historyRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().isValueChanged()).isFalse();
        assertThat(historyCaptor.getValue().isSecret()).isTrue();
    }

    @Test
    void rejectsDowngradingSecretToFalse() {
        stubProjectOwnership();
        EnvironmentVariable existing = new EnvironmentVariable(1L, 11L, EnvironmentScope.PREVIEW, "API_KEY", "value", true, null, null);
        doReturn(existing).when(queryService).findOwned(11L, 1L);

        assertThatThrownBy(() -> service.update(7L, 11L, 1L, null, false))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
        verify(historyRepository, never()).save(any());
    }

    @Test
    void allowsNoOpFalseRequestWhenAlreadyNonSecret() {
        stubProjectOwnership();
        EnvironmentVariable existing = new EnvironmentVariable(1L, 11L, EnvironmentScope.PREVIEW, "API_KEY", "value", false, null, null);
        doReturn(existing).when(queryService).findOwned(11L, 1L);
        when(repository.save(any(EnvironmentVariable.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EnvironmentVariableResult result = service.update(7L, 11L, 1L, null, false);

        assertThat(result.secret()).isFalse();
    }

    @Test
    void rejectsUpdateWithNoChangedFields() {
        stubProjectOwnership();

        assertThatThrownBy(() -> service.update(7L, 11L, 1L, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(queryService, never()).findOwned(any(), any());
    }

    @Test
    void deletesVariableAndRecordsDeletedHistoryWithoutValueChanged() {
        stubProjectOwnership();
        EnvironmentVariable existing = new EnvironmentVariable(1L, 11L, EnvironmentScope.PREVIEW, "API_KEY", "value", false, null, null);
        doReturn(existing).when(queryService).findOwned(11L, 1L);

        service.delete(7L, 11L, 1L);

        verify(repository).deleteById(1L);
        ArgumentCaptor<EnvironmentVariableHistory> historyCaptor = ArgumentCaptor.forClass(EnvironmentVariableHistory.class);
        verify(historyRepository, times(1)).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getAction()).isEqualTo(EnvironmentVariableAction.DELETED);
        assertThat(historyCaptor.getValue().isValueChanged()).isFalse();
        assertThat(historyCaptor.getValue().getEnvironmentVariableId()).isEqualTo(1L);
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
