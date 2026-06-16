package com.example.dvely.project.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.cloudconnection.domain.value.CloudProvider;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectCloudConnectionSettingRepository;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectInfrastructureSettingsServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private CloudConnectionRepository cloudConnectionRepository;

    @Mock
    private ProjectCloudConnectionSettingRepository settingRepository;

    private ProjectInfrastructureSettingsService service;

    @BeforeEach
    void setUp() {
        service = new ProjectInfrastructureSettingsService(
                projectRepository,
                cloudConnectionRepository,
                settingRepository
        );
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(20L, 1L))
                .thenReturn(Optional.of(mock(Project.class)));
    }

    @Test
    void selectsOnlyVerifiedOwnedConnection() {
        CloudConnection connection = connection(CloudConnectionStatus.CONNECTED);
        when(cloudConnectionRepository.findByIdAndOwnerUserId(10L, 1L))
                .thenReturn(Optional.of(connection));
        when(settingRepository.findByProjectId(20L)).thenReturn(Optional.empty());
        when(settingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.select(1L, 20L, 10L);

        assertThat(result.cloudConnectionId()).isEqualTo(10L);
        assertThat(result.status()).isEqualTo("CONNECTED");
        assertThat(result.lastCheckedAt()).isNotNull();
    }

    @Test
    void rejectsConnectionThatHasOnlyPassedFormatValidation() {
        when(cloudConnectionRepository.findByIdAndOwnerUserId(10L, 1L))
                .thenReturn(Optional.of(connection(CloudConnectionStatus.VALIDATED)));

        assertThatThrownBy(() -> service.select(1L, 20L, 10L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONNECTED");
        verify(settingRepository, never()).save(any());
    }

    private CloudConnection connection(CloudConnectionStatus status) {
        return new CloudConnection(
                10L,
                1L,
                CloudProvider.AWS,
                "production",
                "123456789012",
                "ap-northeast-2",
                null,
                "ACCESS_KEY",
                "AKIA1234567890ABCDEF",
                "abcdefghijklmnopqrstuvwxyz1234567890ABCD",
                null,
                null,
                null,
                null,
                null,
                status,
                LocalDateTime.now(),
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }
}
