package com.example.dvely.cloudconnection.infrastructure.worker;

import com.example.dvely.cloudconnection.application.service.CloudConnectionVerificationService;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionVerificationJobRepository;
import java.lang.management.ManagementFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * PENDING 검증 job 을 집어 {@link CloudConnectionVerificationService#executeQueued}(비동기) 로 넘긴다.
 *
 * <p>#340 5-2: {@code DeploymentRunWorker} 와 같은 이유로 위임을 job 하나씩 격리한다 —
 * {@code cloudConnectionExecutor} 포화로 던져진 {@code TaskRejectedException} 하나가 루프를 끊으면,
 * 같은 배치에서 이미 claim 된 다음 job 이 RUNNING 인 채 리스 만료(2분)까지 방치된다. 그 동안
 * 사용자에게는 "권한을 확인하고 있습니다"만 떠 있고 실제로 확인하는 것은 아무것도 없다.</p>
 *
 * <p>배포와 달리 이 job 에는 {@code next_run_at} 이 없어 되돌린 job 은 다음 폴링에 곧바로 다시
 * 집힌다. 포화가 이어지는 동안 claim↔release 가 초당 반복되지만, 그것은 유계이고(배치 2건)
 * 자리가 나는 즉시 스스로 풀린다 — 행이 실행 없이 RUNNING 에 갇히는 쪽이 훨씬 나쁘다.</p>
 */
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
            dispatchOne(jobId);
        }
    }

    private void dispatchOne(String jobId) {
        try {
            log.info("클라우드 연결 검증 위임: jobId={} workerId={}", jobId, workerId);
            verificationService.executeQueued(jobId);
        } catch (RuntimeException exception) {
            boolean released = verificationJobRepository.releaseClaim(jobId, workerId);
            log.warn("클라우드 연결 검증 위임 실패 — 재대기열로 반환합니다. jobId={} workerId={} released={} 원인={}",
                    jobId, workerId, released, exception.toString());
        }
    }
}
