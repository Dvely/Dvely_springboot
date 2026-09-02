package com.example.dvely.provisioning.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
import com.example.dvely.preview.application.service.PreviewSessionService;
import com.example.dvely.project.domain.model.ProjectCloudConnectionSetting;
import com.example.dvely.project.domain.repository.ProjectCloudConnectionSettingRepository;
import com.example.dvely.provisioning.application.result.ProvisionSubmitResult;
import com.example.dvely.provisioning.application.service.DatabaseProvisionerRegistry;
import com.example.dvely.provisioning.domain.model.ProvisionedDatabase;
import com.example.dvely.provisioning.domain.repository.ProvisionedDatabaseRepository;
import com.example.dvely.provisioning.domain.value.DatabaseEngine;
import com.example.dvely.provisioning.domain.value.ProvisionMethod;
import com.example.dvely.provisioning.domain.value.ProvisionOrigin;
import com.example.dvely.provisioning.domain.value.ProvisionStatus;
import com.example.dvely.provisioning.infrastructure.config.ProvisioningProperties;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DatabaseProvisioningCommandServiceRdsTest {

    @Mock private ProvisionedDatabaseRepository databaseRepository;
    @Mock private DatabaseProvisionerRegistry provisionerRegistry;
    @Mock private PreviewSessionService previewSessionService;
    @Mock private ProvisioningProperties properties;
    @Mock private ProjectCloudConnectionSettingRepository cloudConnectionSettingRepository;
    @Mock private CloudConnectionRepository cloudConnectionRepository;
    @Mock private ApprovalRepository approvalRepository;

    @InjectMocks private DatabaseProvisioningCommandService service;

    private static final Long OWNER = 7L;
    private static final Long PROJECT = 10L;
    private static final Long CONN_ID = 11L;

    @Test
    void rdsCreatesPendingRowAndApprovalAndRequiresApproval() {
        when(cloudConnectionSettingRepository.findByProjectId(PROJECT))
                .thenReturn(Optional.of(new ProjectCloudConnectionSetting(PROJECT, CONN_ID)));
        when(cloudConnectionRepository.findByIdAndOwnerUserId(CONN_ID, OWNER))
                .thenReturn(Optional.of(connection(CloudConnectionStatus.CONNECTED)));
        when(databaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(approvalRepository.save(any())).thenReturn(approval(99L));

        ProvisionSubmitResult result = service.provision(
                OWNER, PROJECT, ProvisionMethod.RDS, DatabaseEngine.MYSQL);

        assertThat(result.requiresApproval()).isTrue();
        assertThat(result.database()).isNull();
        assertThat(result.approvalIds()).containsExactly(99L);

        // pending 저장 → linkApproval 후 재저장, 두 번 저장된다. 첫 저장이 RDS/PENDING/MANUAL 인지 확인.
        ArgumentCaptor<ProvisionedDatabase> saved = ArgumentCaptor.forClass(ProvisionedDatabase.class);
        verify(databaseRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        ProvisionedDatabase first = saved.getAllValues().get(0);
        assertThat(first.getMethod()).isEqualTo(ProvisionMethod.RDS);
        assertThat(first.getStatus()).isEqualTo(ProvisionStatus.PENDING);
        assertThat(first.getOrigin()).isEqualTo(ProvisionOrigin.MANUAL);
        assertThat(saved.getAllValues().get(1).getApprovalId()).isEqualTo(99L);
    }

    @Test
    void rdsThrowsWhenNoCloudConnectionSelected() {
        when(cloudConnectionSettingRepository.findByProjectId(PROJECT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.provision(OWNER, PROJECT, ProvisionMethod.RDS, DatabaseEngine.MYSQL))
                .isInstanceOf(NotFoundException.class);

        verify(databaseRepository, never()).save(any());
        verify(approvalRepository, never()).save(any());
    }

    @Test
    void rdsThrowsWhenConnectionNotConnected() {
        when(cloudConnectionSettingRepository.findByProjectId(PROJECT))
                .thenReturn(Optional.of(new ProjectCloudConnectionSetting(PROJECT, CONN_ID)));
        when(cloudConnectionRepository.findByIdAndOwnerUserId(CONN_ID, OWNER))
                .thenReturn(Optional.of(connection(CloudConnectionStatus.PERMISSION_MISSING)));

        assertThatThrownBy(() -> service.provision(OWNER, PROJECT, ProvisionMethod.RDS, DatabaseEngine.MYSQL))
                .isInstanceOf(IllegalStateException.class);

        verify(approvalRepository, never()).save(any());
    }

    private Approval approval(Long id) {
        return new Approval(id, OWNER, PROJECT, null, null, ApprovalType.DATABASE_PROVISION,
                ApprovalStatus.PENDING, "RDS MYSQL 데이터베이스 생성 (과금)", LocalDateTime.now(), LocalDateTime.now());
    }

    private CloudConnection connection(CloudConnectionStatus status) {
        return new CloudConnection(CONN_ID, OWNER, CloudProvider.AWS, "production", "123456789012",
                "ap-northeast-2", null, "ACCESS_KEY", "AKIA1234567890ABCDEF",
                "abcdefghijklmnopqrstuvwxyz1234567890ABCD", null, null, null, null, null,
                status, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
    }
}
