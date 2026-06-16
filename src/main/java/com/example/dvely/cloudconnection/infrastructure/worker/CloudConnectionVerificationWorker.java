package com.example.dvely.cloudconnection.infrastructure.worker;

import com.example.dvely.cloudconnection.application.service.CloudConnectionVerificationService;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionVerificationJobRepository;
import java.lang.management.ManagementFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CloudConnectionVerificationWorker {

    private static final int CLAIM_BATCH_SIZE = 2;

    private final CloudConnectionVerificationJobRepository verificationJobRepository;
    private final CloudConnectionVerificationService verificationService;
    private final String workerId = ManagementFactory.getRuntimeMXBean().getName() + "-cloud-connection";

    @Scheduled(fixedDelayString = "${qeploy.cloud-connection.worker.poll-interval-ms:1000}")
    public void dispatchPendingJobs() {
        verificationJobRepository.recoverExpiredLeases();
        for (String jobId : verificationJobRepository.claimPending(workerId, CLAIM_BATCH_SIZE)) {
            log.info("클라우드 연결 검증 위임: jobId={} workerId={}", jobId, workerId);
            verificationService.executeQueued(jobId);
        }
    }
}
