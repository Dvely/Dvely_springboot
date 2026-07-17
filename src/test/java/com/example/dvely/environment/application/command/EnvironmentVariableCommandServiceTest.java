package com.example.dvely.environment.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

class EnvironmentVariableCommandServiceTest {

    private final EnvironmentVariableRepository repository = mock(EnvironmentVariableRepository.class);
    private final EnvironmentVariableHistoryRepository historyRepository = mock(EnvironmentVariableHistoryRepository.class);
    private final EnvironmentVariableQueryService queryService = mock(EnvironmentVariableQueryService.class);
    private final EnvironmentVariableCommandService service =
            new EnvironmentVariableCommandService(repository, historyRepository, queryService);

    @Test
    void createsVariableAndRecordsCreatedHistory() {
        when(repository.findByProjectIdAndScopeAndKey(11L, EnvironmentScope.PREVIEW, "API_KEY"))
                .thenReturn(Optional.empty());
        when(repository.save(any(EnvironmentVariable.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(queryService.toResult(any())).thenAnswer(invocation ->
                toResult(invocation.getArgument(0)));

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
    void rejectsCreateWhenSameProjectScopeKeyAlreadyExists() {
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
        assertThatThrownBy(() -> service.create(7L, 11L, "GLOBAL", "API_KEY", "value", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updatesValueAndRecordsValueChangedTrueWhenValueDiffers() {
        EnvironmentVariable existing = new EnvironmentVariable(1L, 11L, EnvironmentScope.PREVIEW, "API_KEY", "old", false, null, null);
        when(queryService.findOwned(11L, 1L)).thenReturn(existing);
        when(repository.save(any(EnvironmentVariable.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(queryService.toResult(any())).thenAnswer(invocation -> toResult(invocation.getArgument(0)));

        EnvironmentVariableResult result = service.update(7L, 11L, 1L, "new", null);

        assertThat(result.value()).isEqualTo("new");
        ArgumentCaptor<EnvironmentVariableHistory> historyCaptor = ArgumentCaptor.forClass(EnvironmentVariableHistory.class);
        verify(historyRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().isValueChanged()).isTrue();
        assertThat(historyCaptor.getValue().getAction()).isEqualTo(EnvironmentVariableAction.UPDATED);
    }

    @Test
    void updatesRecordValueChangedFalseWhenNewValueEqualsCurrentValue() {
        EnvironmentVariable existing = new EnvironmentVariable(1L, 11L, EnvironmentScope.PREVIEW, "API_KEY", "same", false, null, null);
        when(queryService.findOwned(11L, 1L)).thenReturn(existing);
        when(repository.save(any(EnvironmentVariable.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(queryService.toResult(any())).thenAnswer(invocation -> toResult(invocation.getArgument(0)));

        service.update(7L, 11L, 1L, "same", null);

        ArgumentCaptor<EnvironmentVariableHistory> historyCaptor = ArgumentCaptor.forClass(EnvironmentVariableHistory.class);
        verify(historyRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().isValueChanged()).isFalse();
    }

    @Test
    void promotesToSecretOnlyWithoutTouchingValue() {
        EnvironmentVariable existing = new EnvironmentVariable(1L, 11L, EnvironmentScope.PREVIEW, "API_KEY", "value", false, null, null);
        when(queryService.findOwned(11L, 1L)).thenReturn(existing);
        when(repository.save(any(EnvironmentVariable.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(queryService.toResult(any())).thenAnswer(invocation -> toResult(invocation.getArgument(0)));

        EnvironmentVariableResult result = service.update(7L, 11L, 1L, null, true);

        assertThat(result.secret()).isTrue();
        ArgumentCaptor<EnvironmentVariableHistory> historyCaptor = ArgumentCaptor.forClass(EnvironmentVariableHistory.class);
        verify(historyRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().isValueChanged()).isFalse();
        assertThat(historyCaptor.getValue().isSecret()).isTrue();
    }

    @Test
    void rejectsDowngradingSecretToFalse() {
        EnvironmentVariable existing = new EnvironmentVariable(1L, 11L, EnvironmentScope.PREVIEW, "API_KEY", "value", true, null, null);
        when(queryService.findOwned(11L, 1L)).thenReturn(existing);

        assertThatThrownBy(() -> service.update(7L, 11L, 1L, null, false))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
        verify(historyRepository, never()).save(any());
    }

    @Test
    void allowsNoOpFalseRequestWhenAlreadyNonSecret() {
        EnvironmentVariable existing = new EnvironmentVariable(1L, 11L, EnvironmentScope.PREVIEW, "API_KEY", "value", false, null, null);
        when(queryService.findOwned(11L, 1L)).thenReturn(existing);
        when(repository.save(any(EnvironmentVariable.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(queryService.toResult(any())).thenAnswer(invocation -> toResult(invocation.getArgument(0)));

        EnvironmentVariableResult result = service.update(7L, 11L, 1L, null, false);

        assertThat(result.secret()).isFalse();
    }

    @Test
    void rejectsUpdateWithNoChangedFields() {
        assertThatThrownBy(() -> service.update(7L, 11L, 1L, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(queryService, never()).findOwned(any(), any());
    }

    @Test
    void deletesVariableAndRecordsDeletedHistoryWithoutValueChanged() {
        EnvironmentVariable existing = new EnvironmentVariable(1L, 11L, EnvironmentScope.PREVIEW, "API_KEY", "value", false, null, null);
        when(queryService.findOwned(11L, 1L)).thenReturn(existing);

        service.delete(7L, 11L, 1L);

        verify(repository).deleteById(1L);
        ArgumentCaptor<EnvironmentVariableHistory> historyCaptor = ArgumentCaptor.forClass(EnvironmentVariableHistory.class);
        verify(historyRepository, times(1)).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getAction()).isEqualTo(EnvironmentVariableAction.DELETED);
        assertThat(historyCaptor.getValue().isValueChanged()).isFalse();
        assertThat(historyCaptor.getValue().getEnvironmentVariableId()).isEqualTo(1L);
    }

    private static EnvironmentVariableResult toResult(EnvironmentVariable variable) {
        return new EnvironmentVariableResult(
                variable.getId(),
                variable.getScope().name(),
                variable.getKey(),
                variable.isSecret() ? null : variable.getValue(),
                variable.isSecret(),
                variable.getCreatedAt(),
                variable.getUpdatedAt()
        );
    }
}
