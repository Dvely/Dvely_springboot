package com.example.dvely.provisioning.infrastructure.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.provisioning.application.service.BackendDeployRunner;
import com.example.dvely.provisioning.domain.model.ProvisionedDatabase;
import com.example.dvely.provisioning.domain.repository.ProvisionedDatabaseRepository;
import com.example.dvely.provisioning.domain.value.DatabaseEngine;
import com.example.dvely.provisioning.domain.value.ProvisionMethod;
import com.example.dvely.provisioning.domain.value.ProvisionOrigin;
import com.example.dvely.provisioning.domain.model.ProvisionedServer;
import com.example.dvely.provisioning.domain.repository.ProvisionedServerRepository;
import com.example.dvely.provisioning.domain.value.ServerStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BackendDeployWorkerTest {

    @Mock private ProvisionedServerRepository serverRepository;
    @Mock private ProvisionedDatabaseRepository databaseRepository;
    @Mock private BackendDeployRunner deployRunner;

    @InjectMocks private BackendDeployWorker worker;

    private ProvisionedServer queued() {
        return new ProvisionedServer(1L, 10L, "t3.micro", ServerStatus.QUEUED,
                11L, null, null, 8080, 99L, null, null, LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    void claimedServerIsDeployed() {
        when(serverRepository.findByStatus(ServerStatus.QUEUED, 5)).thenReturn(List.of(queued()));
        when(databaseRepository.findByProjectIdOrderByCreatedAtDesc(10L)).thenReturn(List.of());
        when(serverRepository.claimForBuild(1L)).thenReturn(true);
        ProvisionedServer building = new ProvisionedServer(1L, 10L, "t3.micro", ServerStatus.BUILDING,
                11L, null, null, 8080, 99L, null, null, LocalDateTime.now(), LocalDateTime.now());
        when(serverRepository.findById(1L)).thenReturn(Optional.of(building));

        worker.processQueued();

        verify(deployRunner).deploy(building);
    }

    @Test
    void unclaimedServerIsSkipped() {
        when(serverRepository.findByStatus(ServerStatus.QUEUED, 5)).thenReturn(List.of(queued()));
        when(serverRepository.claimForBuild(1L)).thenReturn(false);

        worker.processQueued();

        verify(deployRunner, never()).deploy(any());
    }

    @Test
    void waitsWhileDatabaseIsStillProvisioning() {
        when(serverRepository.findByStatus(ServerStatus.QUEUED, 5)).thenReturn(List.of(queued()));
        // 같은 프로젝트의 DB 가 아직 생성 중 → 서버는 이번 주기에 배포하지 않고 기다린다.
        when(databaseRepository.findByProjectIdOrderByCreatedAtDesc(10L)).thenReturn(List.of(
                ProvisionedDatabase.pending(10L, ProvisionMethod.RDS, DatabaseEngine.MYSQL, ProvisionOrigin.MANUAL)));

        worker.processQueued();

        verify(serverRepository, never()).claimForBuild(any());
        verify(deployRunner, never()).deploy(any());
    }
}
