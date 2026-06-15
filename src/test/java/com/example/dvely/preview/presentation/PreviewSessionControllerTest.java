package com.example.dvely.preview.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.preview.application.service.PreviewSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PreviewSessionControllerTest {

    @Test
    void ownerCanClosePreviewSession() {
        PreviewSessionService service = mock(PreviewSessionService.class);
        PreviewSessionController controller = new PreviewSessionController(service);
        when(service.closeOwned("session-1", 1L)).thenReturn(true);

        assertThat(controller.close(1L, "session-1").getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void foreignOwnerGetsCommonNotFoundError() {
        PreviewSessionService service = mock(PreviewSessionService.class);
        PreviewSessionController controller = new PreviewSessionController(service);

        assertThatThrownBy(() -> controller.close(2L, "session-1"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("session-1");
    }
}
