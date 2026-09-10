package com.example.dvely.deployment.infrastructure.worker;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.deployment.application.command.DeploymentCommandService;
import com.example.dvely.deployment.domain.repository.DeploymentHistoryRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

class DeploymentRunWorkerTest {

    private static final long BACKOFF_MS = 5000L;

    @Test
    void dispatchPendingDeployments_recoversClaimsAndDelegatesJobs() {
        DeploymentHistoryRepository repository = mock(DeploymentHistoryRepository.class);
        DeploymentCommandService commandService = mock(DeploymentCommandService.class);
        DeploymentRunWorker worker = new DeploymentRunWorker(repository, commandService, BACKOFF_MS);
        when(repository.claimPending(anyString(), eq(2))).thenReturn(List.of(51L, 52L));

        worker.dispatchPendingDeployments();

        verify(repository).recoverExpiredLeases();
        verify(commandService).executeQueued(51L);
        verify(commandService).executeQueued(52L);
    }

    /**
     * #340 5-2 재현: {@code deploymentExecutor} 포화로 첫 이력의 위임이 거부되면, 예전에는 그
     * 예외가 dispatch 루프를 통째로 끊어 <b>같은 배치에서 이미 claim 된</b> 52 번이 영영 실행되지
     * 않았다. 그 행은 IN_PROGRESS 인 채 리스 만료(2분)까지 남고 사용자 화면에는 "배포 중"이 계속
     * 떠 있었다. 두 가지를 함께 못박는다 — 뒤 이력은 그대로 위임되고, 거부된 이력은 claim 을
     * 붙든 채 방치되지 않는다.
     */
    @Test
    void executorRejectionDoesNotStrandTheRestOfTheClaimedBatch() {
        DeploymentHistoryRepository repository = mock(DeploymentHistoryRepository.class);
        DeploymentCommandService commandService = mock(DeploymentCommandService.class);
        DeploymentRunWorker worker = new DeploymentRunWorker(repository, commandService, BACKOFF_MS);
        when(repository.claimPending(anyString(), eq(2))).thenReturn(List.of(51L, 52L));
        doThrow(new TaskRejectedException("deploymentExecutor 포화"))
                .when(commandService).executeQueued(51L);

        worker.dispatchPendingDeployments();

        verify(commandService).executeQueued(52L);
        verify(repository).releaseClaim(eq(51L), anyString(), eq(BACKOFF_MS));
        verify(repository, never()).releaseClaim(eq(52L), anyString(), anyLong());
    }

    /** 제출 전 어떤 예외든 결론은 같다 — 이 이력은 돌지 않으므로 claim 을 붙들고 있으면 안 된다. */
    @Test
    void anyPreSubmissionFailureReleasesTheClaimToo() {
        DeploymentHistoryRepository repository = mock(DeploymentHistoryRepository.class);
        DeploymentCommandService commandService = mock(DeploymentCommandService.class);
        DeploymentRunWorker worker = new DeploymentRunWorker(repository, commandService, BACKOFF_MS);
        when(repository.claimPending(anyString(), eq(2))).thenReturn(List.of(51L));
        doThrow(new IllegalStateException("제출 전 예외")).when(commandService).executeQueued(51L);

        worker.dispatchPendingDeployments();

        verify(repository).releaseClaim(eq(51L), anyString(), eq(BACKOFF_MS));
    }
}
