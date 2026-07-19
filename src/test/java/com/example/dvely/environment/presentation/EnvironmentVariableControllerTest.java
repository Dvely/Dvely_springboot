package com.example.dvely.environment.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.environment.application.facade.EnvironmentVariableFacade;
import com.example.dvely.environment.application.result.EnvironmentVariableHistoryResult;
import com.example.dvely.environment.application.result.EnvironmentVariableResult;
import com.example.dvely.environment.presentation.dto.request.CreateEnvironmentVariableRequest;
import com.example.dvely.environment.presentation.dto.request.UpdateEnvironmentVariableRequest;
import com.example.dvely.environment.presentation.dto.response.EnvironmentVariableHistoryResponse;
import com.example.dvely.environment.presentation.dto.response.EnvironmentVariableResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EnvironmentVariableControllerTest {

    @Mock
    private EnvironmentVariableFacade facade;

    @InjectMocks
    private EnvironmentVariableController controller;

    @Test
    void getVariablesDelegatesUsingAuthenticatedUserIdProjectIdAndScope() {
        EnvironmentVariableResult result = new EnvironmentVariableResult(
                1L, "PREVIEW", "API_BASE_URL", "https://api.example.com", false, LocalDateTime.now(), LocalDateTime.now()
        );
        when(facade.getVariables(1L, 11L, "PREVIEW")).thenReturn(List.of(result));

        List<EnvironmentVariableResponse> responses = controller.getVariables(1L, 11L, "PREVIEW");

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).key()).isEqualTo("API_BASE_URL");
        verify(facade).getVariables(1L, 11L, "PREVIEW");
    }

    @Test
    void getVariablesResponseKeepsSecretValueNull() {
        EnvironmentVariableResult result = new EnvironmentVariableResult(
                2L, "PRODUCTION", "STRIPE_SECRET_KEY", null, true, LocalDateTime.now(), LocalDateTime.now()
        );
        when(facade.getVariables(1L, 11L, null)).thenReturn(List.of(result));

        List<EnvironmentVariableResponse> responses = controller.getVariables(1L, 11L, null);

        assertThat(responses.get(0).secret()).isTrue();
        assertThat(responses.get(0).value()).isNull();
    }

    @Test
    void createDelegatesRequestFieldsToFacade() {
        CreateEnvironmentVariableRequest request = new CreateEnvironmentVariableRequest("API_KEY", "value", "PREVIEW", false);
        EnvironmentVariableResult result = new EnvironmentVariableResult(
                3L, "PREVIEW", "API_KEY", "value", false, LocalDateTime.now(), LocalDateTime.now()
        );
        when(facade.create(1L, 11L, "PREVIEW", "API_KEY", "value", false)).thenReturn(result);

        EnvironmentVariableResponse response = controller.create(1L, 11L, request);

        assertThat(response.environmentVariableId()).isEqualTo(3L);
        verify(facade).create(1L, 11L, "PREVIEW", "API_KEY", "value", false);
    }

    @Test
    void updateDelegatesRequestFieldsToFacade() {
        UpdateEnvironmentVariableRequest request = new UpdateEnvironmentVariableRequest("new-value", true);
        EnvironmentVariableResult result = new EnvironmentVariableResult(
                3L, "PREVIEW", "API_KEY", null, true, LocalDateTime.now(), LocalDateTime.now()
        );
        when(facade.update(1L, 11L, 3L, "new-value", true)).thenReturn(result);

        EnvironmentVariableResponse response = controller.update(1L, 11L, 3L, request);

        assertThat(response.secret()).isTrue();
        assertThat(response.value()).isNull();
        verify(facade).update(1L, 11L, 3L, "new-value", true);
    }

    @Test
    void deleteDelegatesUsingAuthenticatedUserIdProjectIdAndVariableId() {
        controller.delete(1L, 11L, 3L);

        verify(facade).delete(1L, 11L, 3L);
    }

    @Test
    void getHistoryDelegatesUsingAuthenticatedUserIdProjectIdAndLimit() {
        EnvironmentVariableHistoryResult result = new EnvironmentVariableHistoryResult(
                10L, 2L, "PRODUCTION", "STRIPE_SECRET_KEY", "UPDATED", true, true, 1L, LocalDateTime.now()
        );
        when(facade.getHistory(1L, 11L, 100)).thenReturn(List.of(result));

        List<EnvironmentVariableHistoryResponse> responses = controller.getHistory(1L, 11L, 100);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).historyId()).isEqualTo(10L);
        verify(facade).getHistory(1L, 11L, 100);
    }
}
