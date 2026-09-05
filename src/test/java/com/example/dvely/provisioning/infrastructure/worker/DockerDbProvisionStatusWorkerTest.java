package com.example.dvely.provisioning.infrastructure.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.provisioning.domain.model.ProvisionedDatabase;
import com.example.dvely.provisioning.domain.repository.ProvisionedDatabaseRepository;
import com.example.dvely.provisioning.domain.value.DatabaseEngine;
import com.example.dvely.provisioning.domain.value.ProvisionMethod;
import com.example.dvely.provisioning.domain.value.ProvisionOrigin;
import com.example.dvely.provisioning.domain.value.ProvisionStatus;
import com.example.dvely.provisioning.infrastructure.DockerDbProvisioner;
import com.example.dvely.provisioning.infrastructure.DockerDbProvisioner.DockerDbStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DockerDbProvisionStatusWorkerTest {

    @Mock private ProvisionedDatabaseRepository databaseRepository;
    @Mock private CloudConnectionRepository cloudConnectionRepository;
    @Mock private DockerDbProvisioner dockerDbProvisioner;
    @InjectMocks private DockerDbProvisionStatusWorker worker;

    private ProvisionedDatabase timedOutDocker() {
        ProvisionedDatabase db = new ProvisionedDatabase(1L, 10L, ProvisionMethod.DOCKER,
                DatabaseEngine.MYSQL, ProvisionOrigin.MANUAL, ProvisionStatus.PROVISIONING, "i-db",
                null, 3306, "app", "user", "pw", null, null, null,
                LocalDateTime.now(), LocalDateTime.now().minusMinutes(21));   // 부트 타임아웃
        db.assignCloudConnection(11L);
        return db;
    }

    private void givenBatch(ProvisionedDatabase db) {
        when(databaseRepository.findByStatus(ProvisionStatus.PROVISIONING, 20)).thenReturn(List.of(db));
        when(cloudConnectionRepository.findById(11L)).thenReturn(Optional.of(mock(CloudConnection.class)));
        // host=null·ec2State 비종말 → 아직 준비 안 됨 → 타임아웃 분기로.
        when(dockerDbProvisioner.resolveStatus(any(), eq(10L), eq("i-db")))
                .thenReturn(new DockerDbStatus("running", null));
    }

    @Test
    void bootTimeout_claimWon_teardownsAndFails() {
        ProvisionedDatabase db = timedOutDocker();
        givenBatch(db);
        when(databaseRepository.claimBootTimeout(1L)).thenReturn(true);   // 이 인스턴스가 처리 권한 획득

        worker.pollProvisioning();

        verify(dockerDbProvisioner).teardown(any(), eq(10L), eq("i-db"));   // 과금 인스턴스 정리
        verify(databaseRepository).save(any());
        assertThat(db.getStatus()).isEqualTo(ProvisionStatus.FAILED);
    }

    @Test
    void bootTimeout_claimLost_doesNotTeardown() {
        ProvisionedDatabase db = timedOutDocker();
        givenBatch(db);
        when(databaseRepository.claimBootTimeout(1L)).thenReturn(false);   // 다른 인스턴스가 이김

        worker.pollProvisioning();

        verify(dockerDbProvisioner, never()).teardown(any(), any(), any());   // 중복 정리 안 함
        verify(databaseRepository, never()).save(any());
    }
}
