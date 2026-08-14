package com.example.dvely.preview.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.preview.domain.value.PreviewSessionStatus;
import com.example.dvely.preview.infrastructure.config.PreviewProperties;
import com.example.dvely.preview.infrastructure.persistence.entity.PreviewSessionEntity;
import com.example.dvely.preview.infrastructure.persistence.repository.SpringDataPreviewSessionRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectPreviewProvisionerTest {

    private static final String SESSION_ID = "session-1";
    private static final String CONTAINER_ID = "container-1";

    @Mock private SpringDataPreviewSessionRepository repository;
    @Mock private PreviewWorkspaceService workspaceService;
    @Mock private DockerContainerService dockerService;

    private ProjectPreviewProvisioner provisioner;

    @BeforeEach
    void setUp() {
        PreviewProperties properties = new PreviewProperties();
        properties.setTtl(Duration.ofMinutes(30));
        provisioner = new ProjectPreviewProvisioner(repository, workspaceService, dockerService, properties);
        when(repository.save(any(PreviewSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void preparesBuildsAndServesBeforeMarkingTheSessionActive() {
        PreviewSessionEntity session = provisioningSession();
        when(repository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        provisioner.provision(SESSION_ID);

        verify(workspaceService).prepareProject(CONTAINER_ID, 1L, 11L);
        verify(workspaceService).buildIfConfigured(CONTAINER_ID);
        verify(workspaceService).startPreviewServer(CONTAINER_ID);
        assertThat(session.getStatus()).isEqualTo(PreviewSessionStatus.ACTIVE.name());
        // 만료는 준비가 끝난 시점부터 다시 센다 — install/build 에 쓴 시간을 사용자가 볼 수 있는
        // 시간에서 깎지 않기 위해서다.
        assertThat(session.getExpiresAt()).isAfter(LocalDateTime.now().plusMinutes(25));
    }

    /**
     * 실패 사유는 컨테이너를 지우기 전에 로그까지 함께 담아야 한다 — 지운 뒤에는
     * {@code /preview-sessions/{id}/logs} 가 빈 응답이라 사용자가 원인을 볼 방법이 없다.
     */
    @Test
    void failedProvisioningKeepsTheReasonWithBuildLogAndReleasesTheContainer() {
        PreviewSessionEntity session = provisioningSession();
        when(repository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        doThrow(new IllegalStateException("빌드 결과 디렉터리를 찾지 못했습니다."))
                .when(workspaceService).startPreviewServer(CONTAINER_ID);
        when(workspaceService.tailBuildLog(anyString(), anyInt()))
                .thenReturn("npm ERR! Missing script: \"build\"");

        provisioner.provision(SESSION_ID);

        assertThat(session.getStatus()).isEqualTo(PreviewSessionStatus.FAILED.name());
        assertThat(session.getFailureReason())
                .contains("빌드 결과 디렉터리를 찾지 못했습니다.")
                .contains("Missing script");
        verify(dockerService).removeContainer(CONTAINER_ID);
    }

    /** 요청이 겹쳐 취소됐거나 사용자가 닫은 세션 — 컨테이너까지 이미 정리된 상태라 할 일이 없다. */
    @Test
    void skipsASessionThatIsNoLongerProvisioning() {
        PreviewSessionEntity cancelled = provisioningSession();
        cancelled.close(PreviewSessionStatus.CLOSED);
        when(repository.findById(SESSION_ID)).thenReturn(Optional.of(cancelled));

        provisioner.provision(SESSION_ID);

        verify(workspaceService, never()).prepareProject(anyString(), anyLong(), anyLong());
        verify(dockerService, never()).removeContainer(anyString());
    }

    private PreviewSessionEntity provisioningSession() {
        return new PreviewSessionEntity(
                SESSION_ID,
                "token",
                1L,
                11L,
                null,
                null,
                CONTAINER_ID,
                32768,
                "https://preview.qeploy.test/api/v1/previews/session-1/token/",
                LocalDateTime.now().plusMinutes(30),
                PreviewSessionStatus.PROVISIONING
        );
    }
}
