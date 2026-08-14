package com.example.dvely.preview.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dvely.preview.application.result.ProjectPreviewSessionResult;
import com.example.dvely.preview.application.service.ProjectPreviewService;
import com.example.dvely.preview.application.service.ProjectPreviewService.ProvisionOutcome;
import com.example.dvely.preview.domain.value.PreviewSessionStatus;
import com.example.dvely.preview.presentation.dto.response.ProjectPreviewSessionResponse;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 상태 코드가 이 API 의 계약이다: FE 는 204(아직 없음 → 버튼), 200(바로 열 수 있음), 202(준비 중 →
 * 폴링)를 각각 다른 화면으로 처리한다.
 */
class ProjectPreviewControllerTest {

    @Test
    void noPreviewYetIsNoContentRatherThanAnError() {
        ProjectPreviewService service = mock(ProjectPreviewService.class);
        when(service.findCurrent(11L, 1L)).thenReturn(Optional.empty());

        ResponseEntity<ProjectPreviewSessionResponse> response =
                new ProjectPreviewController(service).getCurrent(1L, 11L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void anActivePreviewComesBackWithTheUrlToOpen() {
        ProjectPreviewService service = mock(ProjectPreviewService.class);
        when(service.findCurrent(11L, 1L)).thenReturn(Optional.of(result(
                PreviewSessionStatus.ACTIVE, "https://qeploy.test/api/v1/previews/s/t/", null)));

        ResponseEntity<ProjectPreviewSessionResponse> response =
                new ProjectPreviewController(service).getCurrent(1L, 11L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().previewUrl()).isEqualTo("https://qeploy.test/api/v1/previews/s/t/");
    }

    @Test
    void attachingToALiveSessionIsOkAndStartingOneIsAccepted() {
        ProjectPreviewService service = mock(ProjectPreviewService.class);
        ProjectPreviewController controller = new ProjectPreviewController(service);

        when(service.provision(11L, 1L)).thenReturn(new ProvisionOutcome(
                result(PreviewSessionStatus.ACTIVE, "https://qeploy.test/api/v1/previews/s/t/", null), false));
        assertThat(controller.provision(1L, 11L).getStatusCode()).isEqualTo(HttpStatus.OK);

        when(service.provision(11L, 1L)).thenReturn(new ProvisionOutcome(
                result(PreviewSessionStatus.PROVISIONING, null, null), true));
        ResponseEntity<ProjectPreviewSessionResponse> accepted = controller.provision(1L, 11L);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(accepted.getBody()).isNotNull();
        assertThat(accepted.getBody().previewUrl()).isNull();
    }

    @Test
    void aFailedPreviewCarriesItsReasonToTheClient() {
        ProjectPreviewService service = mock(ProjectPreviewService.class);
        when(service.findCurrent(11L, 1L)).thenReturn(Optional.of(result(
                PreviewSessionStatus.FAILED, null, "npm ERR! Missing script: \"build\"")));

        ResponseEntity<ProjectPreviewSessionResponse> response =
                new ProjectPreviewController(service).getCurrent(1L, 11L);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(PreviewSessionStatus.FAILED.name());
        assertThat(response.getBody().failureReason()).contains("Missing script");
    }

    private ProjectPreviewSessionResult result(PreviewSessionStatus status,
                                               String previewUrl,
                                               String failureReason) {
        return new ProjectPreviewSessionResult(
                "session-1", 11L, null, status.name(), previewUrl,
                LocalDateTime.now().plusMinutes(30), failureReason);
    }
}
