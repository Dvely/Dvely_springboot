package com.example.dvely.provisioning.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.cloudconnection.domain.value.CloudProvider;
import com.example.dvely.project.domain.model.ProjectCloudConnectionSetting;
import com.example.dvely.project.domain.repository.ProjectCloudConnectionSettingRepository;
import com.example.dvely.provisioning.domain.model.ProvisionedServer;
import com.example.dvely.provisioning.domain.repository.ProvisionedServerRepository;
import com.example.dvely.provisioning.domain.value.ServerStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ServerProvisionApprovalHandlerTest {

    @Mock private ProvisionedServerRepository serverRepository;
    @Mock private ProjectCloudConnectionSettingRepository cloudConnectionSettingRepository;
    @Mock private CloudConnectionRepository cloudConnectionRepository;

    @InjectMocks private ServerProvisionApprovalHandler handler;

    private static final Long OWNER = 7L;
    private static final Long PROJECT = 10L;
    private static final Long CONN_ID = 11L;
    private static final Long APPROVAL_ID = 99L;

    @Test
    void supportsOnlyServerProvision() {
        assertThat(handler.supports(ApprovalType.SERVER_PROVISION)).isTrue();
        assertThat(handler.supports(ApprovalType.DATABASE_PROVISION)).isFalse();
    }

    @Test
    void onApprovedQueuesAndRemembersConnection() {
        ProvisionedServer pending = pending();
        when(serverRepository.findByApprovalId(APPROVAL_ID)).thenReturn(Optional.of(pending));
        when(cloudConnectionSettingRepository.findByProjectId(PROJECT))
                .thenReturn(Optional.of(new ProjectCloudConnectionSetting(PROJECT, CONN_ID)));
        when(cloudConnectionRepository.findByIdAndOwnerUserId(CONN_ID, OWNER))
                .thenReturn(Optional.of(connection(CloudConnectionStatus.CONNECTED)));

        handler.onApproved(approval(ApprovalStatus.APPROVED));

        ArgumentCaptor<ProvisionedServer> saved = ArgumentCaptor.forClass(ProvisionedServer.class);
        verify(serverRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ServerStatus.QUEUED);
        assertThat(saved.getValue().getCloudConnectionId()).isEqualTo(CONN_ID);
        assertThat(saved.getValue().getInstanceId()).isNull();   // 아직 안 만듦 — 워커가 launch
    }

    @Test
    void onApprovedThrowsWhenConnectionNoLongerConnected() {
        when(serverRepository.findByApprovalId(APPROVAL_ID)).thenReturn(Optional.of(pending()));
        when(cloudConnectionSettingRepository.findByProjectId(PROJECT))
                .thenReturn(Optional.of(new ProjectCloudConnectionSetting(PROJECT, CONN_ID)));
        when(cloudConnectionRepository.findByIdAndOwnerUserId(CONN_ID, OWNER))
                .thenReturn(Optional.of(connection(CloudConnectionStatus.PERMISSION_MISSING)));

        assertThatThrownBy(() -> handler.onApproved(approval(ApprovalStatus.APPROVED)))
                .isInstanceOf(IllegalStateException.class);
        verify(serverRepository, never()).save(any());
    }

    @Test
    void onRejectedMarksPendingFailed() {
        when(serverRepository.findByApprovalId(APPROVAL_ID)).thenReturn(Optional.of(pending()));

        handler.onRejected(approval(ApprovalStatus.REJECTED));

        ArgumentCaptor<ProvisionedServer> saved = ArgumentCaptor.forClass(ProvisionedServer.class);
        verify(serverRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ServerStatus.FAILED);
        assertThat(saved.getValue().getFailureCode()).isNull();   // 거부는 프로바이더 오류가 아니다
    }

    private ProvisionedServer pending() {
        return ProvisionedServer.pending(PROJECT, "t3.micro", 8080);
    }

    private Approval approval(ApprovalStatus status) {
        return new Approval(APPROVAL_ID, OWNER, PROJECT, null, null, ApprovalType.SERVER_PROVISION,
                status, "EC2 백엔드 서버 생성 (t3.micro, 과금)", LocalDateTime.now(), LocalDateTime.now());
    }

    private CloudConnection connection(CloudConnectionStatus status) {
        return new CloudConnection(CONN_ID, OWNER, CloudProvider.AWS, "production", "123456789012",
                "ap-northeast-2", null, "ACCESS_KEY", "AKIA1234567890ABCDEF",
                "abcdefghijklmnopqrstuvwxyz1234567890ABCD", null, null, null, null, null,
                status, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
    }
}
