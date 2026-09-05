package com.example.dvely.provisioning.application.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.project.domain.model.ProjectCloudConnectionSetting;
import com.example.dvely.project.domain.repository.ProjectCloudConnectionSettingRepository;
import com.example.dvely.provisioning.infrastructure.S3StaticSiteStore;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectCloudCleanupAdapterTest {

    @Mock private ProjectCloudConnectionSettingRepository cloudConnectionSettingRepository;
    @Mock private CloudConnectionRepository cloudConnectionRepository;
    @Mock private S3StaticSiteStore siteStore;

    @InjectMocks private ProjectCloudCleanupAdapter adapter;

    @Test
    void deletesTheProjectsS3SiteBucketWhenAConnectionExists() {
        ProjectCloudConnectionSetting setting = mock(ProjectCloudConnectionSetting.class);
        when(setting.getCloudConnectionId()).thenReturn(1L);
        CloudConnection connection = mock(CloudConnection.class);
        when(connection.getAccountId()).thenReturn("123456789012");
        when(cloudConnectionSettingRepository.findByProjectId(4L)).thenReturn(Optional.of(setting));
        when(cloudConnectionRepository.findByIdAndOwnerUserId(1L, 7L)).thenReturn(Optional.of(connection));
        when(siteStore.bucketNameFor(connection, 4L)).thenReturn("qeploy-site-123456789012-ap-northeast-2-4");

        adapter.cleanupFrontendS3(4L, 7L);

        verify(siteStore).deleteSite(connection, "qeploy-site-123456789012-ap-northeast-2-4");
    }

    @Test
    void noOpsWhenTheProjectHasNoCloudConnection() {
        when(cloudConnectionSettingRepository.findByProjectId(4L)).thenReturn(Optional.empty());

        adapter.cleanupFrontendS3(4L, 7L);

        verify(siteStore, never()).deleteSite(any(), anyString());
    }

    @Test
    void swallowsExceptionsSoCleanupNeverBreaksDeletion() {
        // best-effort: 정리 실패가 이미 끝난 프로젝트 삭제를 되돌리면 안 된다 — 절대 던지지 않는다.
        ProjectCloudConnectionSetting setting = mock(ProjectCloudConnectionSetting.class);
        when(setting.getCloudConnectionId()).thenReturn(1L);
        CloudConnection connection = mock(CloudConnection.class);
        when(connection.getAccountId()).thenReturn("123456789012");
        when(cloudConnectionSettingRepository.findByProjectId(4L)).thenReturn(Optional.of(setting));
        when(cloudConnectionRepository.findByIdAndOwnerUserId(1L, 7L)).thenReturn(Optional.of(connection));
        when(siteStore.bucketNameFor(connection, 4L)).thenReturn("qeploy-site-x-4");
        doThrow(new RuntimeException("S3 down")).when(siteStore).deleteSite(connection, "qeploy-site-x-4");

        assertThatCode(() -> adapter.cleanupFrontendS3(4L, 7L)).doesNotThrowAnyException();
    }
}
