package com.example.dvely.cloudconnection.infrastructure.worker;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.cloudconnection.application.service.CloudConnectionVerificationService;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionVerificationJobRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

class CloudConnectionVerificationWorkerTest {

    @Test
    void dispatchPendingJobs_recoversClaimsAndDelegatesJobs() {
        CloudConnectionVerificationJobRepository repository =
                mock(CloudConnectionVerificationJobRepository.class);
        CloudConnectionVerificationService service = mock(CloudConnectionVerificationService.class);
        CloudConnectionVerificationWorker worker = new CloudConnectionVerificationWorker(repository, service);
        when(repository.claimPending(anyString(), eq(2))).thenReturn(List.of("job-1", "job-2"));

        worker.dispatchPendingJobs();

        verify(repository).recoverExpiredLeases();
        verify(service).executeQueued("job-1");
        verify(service).executeQueued("job-2");
    }

    /**
     * #340 5-2 재현: {@code cloudConnectionExecutor} 포화로 첫 job 의 위임이 거부되면, 예전에는 그
     * 예외가 루프를 끊어 같은 배치의 job-2 가 RUNNING 인 채 리스 만료(2분)까지 방치됐다. 그 동안
     * 사용자에게는 "권한을 확인하고 있습니다"만 떠 있고 실제로 확인하는 것은 아무것도 없었다.
     */
    @Test
    void executorRejectionDoesNotStrandTheRestOfTheClaimedBatch() {
        CloudConnectionVerificationJobRepository repository =
                mock(CloudConnectionVerificationJobRepository.class);
        CloudConnectionVerificationService service = mock(CloudConnectionVerificationService.class);
        CloudConnectionVerificationWorker worker = new CloudConnectionVerificationWorker(repository, service);
        when(repository.claimPending(anyString(), eq(2))).thenReturn(List.of("job-1", "job-2"));
        doThrow(new TaskRejectedException("cloudConnectionExecutor 포화"))
                .when(service).executeQueued("job-1");

        worker.dispatchPendingJobs();

        verify(service).executeQueued("job-2");
        verify(repository).releaseClaim(eq("job-1"), anyString());
        verify(repository, never()).releaseClaim(eq("job-2"), anyString());
    }
}
