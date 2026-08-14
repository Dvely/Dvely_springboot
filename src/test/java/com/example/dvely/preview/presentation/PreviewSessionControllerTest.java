package com.example.dvely.preview.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.preview.application.result.PreviewContainerLogsResult;
import com.example.dvely.preview.application.result.PreviewContainerStatusResult;
import com.example.dvely.preview.application.service.PreviewContainerOpsService;
import com.example.dvely.config.CorsProperties;
import com.example.dvely.preview.application.service.PreviewSessionService;
import com.example.dvely.preview.infrastructure.config.PreviewGatewayUrlResolver;
import com.example.dvely.preview.infrastructure.config.PreviewProperties;
import com.example.dvely.preview.presentation.dto.response.PreviewContainerLogsResponse;
import com.example.dvely.preview.presentation.dto.response.PreviewContainerStatusResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PreviewSessionControllerTest {

    @Test
    void ownerCanClosePreviewSession() {
        PreviewSessionService service = mock(PreviewSessionService.class);
        PreviewContainerOpsService opsService = mock(PreviewContainerOpsService.class);
        PreviewSessionController controller = new PreviewSessionController(service, opsService, previewProperties(), gatewayUrlResolver());
        when(service.closeOwned("session-1", 1L)).thenReturn(true);

        assertThat(controller.close(1L, "session-1").getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void foreignOwnerGetsCommonNotFoundError() {
        PreviewSessionService service = mock(PreviewSessionService.class);
        PreviewContainerOpsService opsService = mock(PreviewContainerOpsService.class);
        PreviewSessionController controller = new PreviewSessionController(service, opsService, previewProperties(), gatewayUrlResolver());

        assertThatThrownBy(() -> controller.close(2L, "session-1"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("session-1");
    }

    @Test
    void getStatusDelegatesToOpsServiceAndMapsResourcesWhenRunning() {
        PreviewSessionService service = mock(PreviewSessionService.class);
        PreviewContainerOpsService opsService = mock(PreviewContainerOpsService.class);
        PreviewSessionController controller = new PreviewSessionController(service, opsService, previewProperties(), gatewayUrlResolver());
        PreviewContainerStatusResult result = new PreviewContainerStatusResult(
                "session-1",
                11L,
                "task-1",
                "ACTIVE",
                true,
                false,
                null,
                LocalDateTime.of(2026, 7, 17, 0, 0),
                LocalDateTime.of(2026, 7, 17, 0, 30),
                new PreviewContainerStatusResult.ResourceUsageResult(1024L, 1_073_741_824L, 0.1, 5.0)
        );
        when(opsService.getStatus(1L, "session-1")).thenReturn(result);

        PreviewContainerStatusResponse response = controller.getStatus(1L, "session-1");

        assertThat(response.sessionId()).isEqualTo("session-1");
        assertThat(response.containerRunning()).isTrue();
        assertThat(response.resources()).isNotNull();
        assertThat(response.resources().memoryUsageBytes()).isEqualTo(1024L);
        assertThat(response.resources().cpuPercent()).isEqualTo(5.0);
        verify(opsService).getStatus(1L, "session-1");
    }

    @Test
    void getStatusMapsNullResourcesForClosedSession() {
        PreviewSessionService service = mock(PreviewSessionService.class);
        PreviewContainerOpsService opsService = mock(PreviewContainerOpsService.class);
        PreviewSessionController controller = new PreviewSessionController(service, opsService, previewProperties(), gatewayUrlResolver());
        PreviewContainerStatusResult result = new PreviewContainerStatusResult(
                "session-1",
                11L,
                "task-1",
                "CLOSED",
                false,
                null,
                null,
                null,
                LocalDateTime.of(2026, 7, 17, 0, 0),
                null
        );
        when(opsService.getStatus(1L, "session-1")).thenReturn(result);

        PreviewContainerStatusResponse response = controller.getStatus(1L, "session-1");

        assertThat(response.containerRunning()).isFalse();
        assertThat(response.resources()).isNull();
    }

    @Test
    void getLogsDelegatesTailAndSinceSecondsToOpsService() {
        PreviewSessionService service = mock(PreviewSessionService.class);
        PreviewContainerOpsService opsService = mock(PreviewContainerOpsService.class);
        PreviewSessionController controller = new PreviewSessionController(service, opsService, previewProperties(), gatewayUrlResolver());
        when(opsService.getLogs(1L, "session-1", 500, 60))
                .thenReturn(new PreviewContainerLogsResult("session-1", true, "2026-07-17T00:00:00Z log line\n"));

        PreviewContainerLogsResponse response = controller.getLogs(1L, "session-1", 500, 60);

        assertThat(response.sessionId()).isEqualTo("session-1");
        assertThat(response.containerRunning()).isTrue();
        assertThat(response.logText()).contains("log line");
        verify(opsService).getLogs(1L, "session-1", 500, 60);
    }

    private PreviewProperties previewProperties() {
        return new PreviewProperties();
    }

    private PreviewGatewayUrlResolver gatewayUrlResolver() {
        return new PreviewGatewayUrlResolver(previewProperties(), new CorsProperties(List.of(), List.of()));
    }
}
