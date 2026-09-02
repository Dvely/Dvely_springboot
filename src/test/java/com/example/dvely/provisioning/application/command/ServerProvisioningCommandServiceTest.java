package com.example.dvely.provisioning.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.cloudconnection.domain.value.CloudProvider;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.project.domain.model.ProjectCloudConnectionSetting;
import com.example.dvely.project.domain.repository.ProjectCloudConnectionSettingRepository;
import com.example.dvely.provisioning.application.result.ServerProvisionSubmitResult;
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
class ServerProvisioningCommandServiceTest {

    @Mock private ProvisionedServerRepository serverRepository;
    @Mock private ProjectCloudConnectionSettingRepository cloudConnectionSettingRepository;
    @Mock private CloudConnectionRepository cloudConnectionRepository;
    @Mock private ApprovalRepository approvalRepository;

    @InjectMocks private ServerProvisioningCommandService service;

    private static final Long OWNER = 7L;
    private static final Long PROJECT = 10L;
    private static final Long CONN_ID = 11L;

    @Test
    void submitCreatesPendingServerAndApproval() {
        when(cloudConnectionSettingRepository.findByProjectId(PROJECT))
                .thenReturn(Optional.of(new ProjectCloudConnectionSetting(PROJECT, CONN_ID)));
        when(cloudConnectionRepository.findByIdAndOwnerUserId(CONN_ID, OWNER))
                .thenReturn(Optional.of(connection(CloudConnectionStatus.CONNECTED)));
        when(serverRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(approvalRepository.save(any())).thenReturn(approval(99L));

        ServerProvisionSubmitResult result = service.submit(OWNER, PROJECT, null);

        assertThat(result.requiresApproval()).isTrue();
        assertThat(result.approvalIds()).containsExactly(99L);

        ArgumentCaptor<ProvisionedServer> saved = ArgumentCaptor.forClass(ProvisionedServer.class);
        verify(serverRepository, times(2)).save(saved.capture());
        ProvisionedServer first = saved.getAllValues().get(0);
        assertThat(first.getStatus()).isEqualTo(ServerStatus.PENDING);
        assertThat(first.getInstanceType()).isEqualTo("t3.micro");   // 기본 티어
        assertThat(first.getPort()).isEqualTo(8080);
        assertThat(saved.getAllValues().get(1).getApprovalId()).isEqualTo(99L);
    }

    @Test
    void submitHonorsRequestedInstanceType() {
        when(cloudConnectionSettingRepository.findByProjectId(PROJECT))
                .thenReturn(Optional.of(new ProjectCloudConnectionSetting(PROJECT, CONN_ID)));
        when(cloudConnectionRepository.findByIdAndOwnerUserId(CONN_ID, OWNER))
                .thenReturn(Optional.of(connection(CloudConnectionStatus.CONNECTED)));
        when(serverRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(approvalRepository.save(any())).thenReturn(approval(99L));

        service.submit(OWNER, PROJECT, "t3.small");

        ArgumentCaptor<ProvisionedServer> saved = ArgumentCaptor.forClass(ProvisionedServer.class);
        verify(serverRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getInstanceType()).isEqualTo("t3.small");
    }

    @Test
    void submitThrowsWhenNoCloudConnection() {
        when(cloudConnectionSettingRepository.findByProjectId(PROJECT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(OWNER, PROJECT, null))
                .isInstanceOf(NotFoundException.class);
        verify(serverRepository, never()).save(any());
        verify(approvalRepository, never()).save(any());
    }

    @Test
    void submitThrowsWhenConnectionNotConnected() {
        when(cloudConnectionSettingRepository.findByProjectId(PROJECT))
                .thenReturn(Optional.of(new ProjectCloudConnectionSetting(PROJECT, CONN_ID)));
        when(cloudConnectionRepository.findByIdAndOwnerUserId(CONN_ID, OWNER))
                .thenReturn(Optional.of(connection(CloudConnectionStatus.BILLING_DISABLED)));

        assertThatThrownBy(() -> service.submit(OWNER, PROJECT, null))
                .isInstanceOf(IllegalStateException.class);
        verify(approvalRepository, never()).save(any());
    }

    private Approval approval(Long id) {
        return new Approval(id, OWNER, PROJECT, null, null, ApprovalType.SERVER_PROVISION,
                ApprovalStatus.PENDING, "EC2 백엔드 서버 생성 (t3.micro, 과금)", LocalDateTime.now(), LocalDateTime.now());
    }

    private CloudConnection connection(CloudConnectionStatus status) {
        return new CloudConnection(CONN_ID, OWNER, CloudProvider.AWS, "production", "123456789012",
                "ap-northeast-2", null, "ACCESS_KEY", "AKIA1234567890ABCDEF",
                "abcdefghijklmnopqrstuvwxyz1234567890ABCD", null, null, null, null, null,
                status, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
    }
}
