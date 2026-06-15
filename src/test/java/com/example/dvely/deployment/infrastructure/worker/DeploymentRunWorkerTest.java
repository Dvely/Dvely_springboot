package com.example.dvely.deployment.infrastructure.worker;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.deployment.application.command.DeploymentCommandService;
import com.example.dvely.deployment.domain.repository.DeploymentHistoryRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeploymentRunWorkerTest {

    @Test
    void dispatchPendingDeployments_recoversClaimsAndDelegatesJobs() {
        DeploymentHistoryRepository repository = mock(DeploymentHistoryRepository.class);
        DeploymentCommandService commandService = mock(DeploymentCommandService.class);
        DeploymentRunWorker worker = new DeploymentRunWorker(repository, commandService);
        when(repository.claimPending(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(2)))
                .thenReturn(List.of(51L, 52L));

        worker.dispatchPendingDeployments();

        verify(repository).recoverExpiredLeases();
        verify(commandService).executeQueued(51L);
        verify(commandService).executeQueued(52L);
    }
}
