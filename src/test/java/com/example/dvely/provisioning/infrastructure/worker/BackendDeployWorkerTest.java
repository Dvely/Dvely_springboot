package com.example.dvely.provisioning.infrastructure.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.provisioning.application.service.BackendDeployRunner;
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
    @Mock private BackendDeployRunner deployRunner;

    @InjectMocks private BackendDeployWorker worker;

    private ProvisionedServer queued() {
        return new ProvisionedServer(1L, 10L, "t3.micro", ServerStatus.QUEUED,
                11L, null, null, 8080, 99L, null, null, LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    void claimedServerIsDeployed() {
        when(serverRepository.findByStatus(ServerStatus.QUEUED, 5)).thenReturn(List.of(queued()));
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
}
