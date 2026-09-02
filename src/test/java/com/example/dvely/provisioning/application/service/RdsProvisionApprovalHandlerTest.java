package com.example.dvely.provisioning.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.example.dvely.provisioning.domain.model.ProvisionedDatabase;
import com.example.dvely.provisioning.domain.repository.ProvisionedDatabaseRepository;
import com.example.dvely.provisioning.domain.value.DatabaseEngine;
import com.example.dvely.provisioning.domain.value.ProvisionMethod;
import com.example.dvely.provisioning.domain.value.ProvisionOrigin;
import com.example.dvely.provisioning.domain.value.ProvisionStatus;
import com.example.dvely.provisioning.infrastructure.RdsProvisioner;
import com.example.dvely.provisioning.infrastructure.RdsProvisioner.RdsCreation;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RdsProvisionApprovalHandlerTest {

    @Mock private ProvisionedDatabaseRepository databaseRepository;
    @Mock private ProjectCloudConnectionSettingRepository cloudConnectionSettingRepository;
    @Mock private CloudConnectionRepository cloudConnectionRepository;
    @Mock private RdsProvisioner rdsProvisioner;

    @InjectMocks private RdsProvisionApprovalHandler handler;

    private static final Long OWNER = 7L;
    private static final Long PROJECT = 10L;
    private static final Long CONN_ID = 11L;
    private static final Long APPROVAL_ID = 99L;

    @Test
    void supportsOnlyDatabaseProvision() {
        assertThat(handler.supports(ApprovalType.DATABASE_PROVISION)).isTrue();
        assertThat(handler.supports(ApprovalType.INFRA_OPERATION)).isFalse();
    }

    @Test
    void onApprovedStartsCreationAndMovesToProvisioning() {
        ProvisionedDatabase pending = pendingRds();
        when(databaseRepository.findByApprovalId(APPROVAL_ID)).thenReturn(Optional.of(pending));
        when(cloudConnectionSettingRepository.findByProjectId(PROJECT))
                .thenReturn(Optional.of(new ProjectCloudConnectionSetting(PROJECT, CONN_ID)));
        when(cloudConnectionRepository.findByIdAndOwnerUserId(CONN_ID, OWNER))
                .thenReturn(Optional.of(connection(CloudConnectionStatus.CONNECTED)));
        when(rdsProvisioner.startCreation(any(), eq(DatabaseEngine.MYSQL), eq(PROJECT)))
                .thenReturn(new RdsCreation("qeploy-10-abcd1234", 3306, "app", "qeadmin", "secretpw"));

        handler.onApproved(approval(ApprovalStatus.APPROVED));

        ArgumentCaptor<ProvisionedDatabase> saved = ArgumentCaptor.forClass(ProvisionedDatabase.class);
        verify(databaseRepository).save(saved.capture());
        ProvisionedDatabase result = saved.getValue();
        assertThat(result.getStatus()).isEqualTo(ProvisionStatus.PROVISIONING);
        assertThat(result.getResourceId()).isEqualTo("qeploy-10-abcd1234");
        assertThat(result.getPort()).isEqualTo(3306);
        assertThat(result.getUsername()).isEqualTo("qeadmin");
        assertThat(result.getPassword()).isEqualTo("secretpw");
        assertThat(result.getHost()).isNull();   // host 는 available 이후에야 채워진다
        assertThat(result.getCloudConnectionId()).isEqualTo(CONN_ID);   // 생성에 쓴 연결을 기억한다
    }

    @Test
    void onApprovedThrowsWhenConnectionNoLongerConnected() {
        ProvisionedDatabase pending = pendingRds();
        when(databaseRepository.findByApprovalId(APPROVAL_ID)).thenReturn(Optional.of(pending));
        when(cloudConnectionSettingRepository.findByProjectId(PROJECT))
                .thenReturn(Optional.of(new ProjectCloudConnectionSetting(PROJECT, CONN_ID)));
        when(cloudConnectionRepository.findByIdAndOwnerUserId(CONN_ID, OWNER))
                .thenReturn(Optional.of(connection(CloudConnectionStatus.PERMISSION_MISSING)));

        assertThatThrownBy(() -> handler.onApproved(approval(ApprovalStatus.APPROVED)))
                .isInstanceOf(IllegalStateException.class);

        verify(rdsProvisioner, never()).startCreation(any(), any(), any());
        verify(databaseRepository, never()).save(any());
    }

    @Test
    void onApprovedThrowsWhenNoPendingRow() {
        when(databaseRepository.findByApprovalId(APPROVAL_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.onApproved(approval(ApprovalStatus.APPROVED)))
                .isInstanceOf(IllegalStateException.class);
        verify(rdsProvisioner, never()).startCreation(any(), any(), any());
    }

    @Test
    void onRejectedMarksPendingRowFailed() {
        ProvisionedDatabase pending = pendingRds();
        when(databaseRepository.findByApprovalId(APPROVAL_ID)).thenReturn(Optional.of(pending));

        handler.onRejected(approval(ApprovalStatus.REJECTED));

        ArgumentCaptor<ProvisionedDatabase> saved = ArgumentCaptor.forClass(ProvisionedDatabase.class);
        verify(databaseRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ProvisionStatus.FAILED);
        assertThat(saved.getValue().getFailureCode()).isNull();   // 거부는 프로바이더 오류가 아니다
        verify(rdsProvisioner, never()).startCreation(any(), any(), any());
    }

    private ProvisionedDatabase pendingRds() {
        return ProvisionedDatabase.pending(PROJECT, ProvisionMethod.RDS, DatabaseEngine.MYSQL, ProvisionOrigin.MANUAL);
    }

    private Approval approval(ApprovalStatus status) {
        return new Approval(APPROVAL_ID, OWNER, PROJECT, null, null, ApprovalType.DATABASE_PROVISION,
                status, "RDS MYSQL 데이터베이스 생성 (과금)", LocalDateTime.now(), LocalDateTime.now());
    }

    private CloudConnection connection(CloudConnectionStatus status) {
        return new CloudConnection(CONN_ID, OWNER, CloudProvider.AWS, "production", "123456789012",
                "ap-northeast-2", null, "ACCESS_KEY", "AKIA1234567890ABCDEF",
                "abcdefghijklmnopqrstuvwxyz1234567890ABCD", null, null, null, null, null,
                status, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
    }
}
