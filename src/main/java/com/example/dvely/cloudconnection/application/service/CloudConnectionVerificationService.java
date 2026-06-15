package com.example.dvely.cloudconnection.application.service;

import com.example.dvely.cloudconnection.application.port.out.CloudConnectionVerificationPort;
import com.example.dvely.cloudconnection.application.port.out.CloudConnectionVerificationPort.VerificationResult;
import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.model.CloudConnectionVerificationJob;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionVerificationJobRepository;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionVerificationJobStatus;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CloudConnectionVerificationService {

    private final CloudConnectionRepository cloudConnectionRepository;
    private final CloudConnectionVerificationJobRepository verificationJobRepository;
    private final CloudConnectionVerificationPort verificationPort;

    @Async("cloudConnectionExecutor")
    public void executeQueued(String jobId) {
        CloudConnectionVerificationJob job = verificationJobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != CloudConnectionVerificationJobStatus.RUNNING) {
            return;
        }

        CloudConnection cloudConnection = cloudConnectionRepository.findByIdAndOwnerUserId(
                        job.getCloudConnectionId(),
                        job.getOwnerUserId()
                )
                .orElse(null);
        if (cloudConnection == null) {
            failJob(job, "검증할 클라우드 연결이 삭제되었습니다.");
            return;
        }

        try {
            cloudConnection.markVerifying();
            cloudConnectionRepository.save(cloudConnection);

            VerificationResult result = verificationPort.verify(cloudConnection);
            LocalDateTime checkedAt = LocalDateTime.now();
            cloudConnection.markHealth(result.status(), checkedAt);
            cloudConnectionRepository.save(cloudConnection);

            if (result.status() == CloudConnectionStatus.UNKNOWN_ERROR) {
                job.fail(result.message(), checkedAt);
            } else {
                job.complete(result.status(), result.message(), checkedAt);
            }
            verificationJobRepository.save(job);
        } catch (RuntimeException exception) {
            log.error("클라우드 연결 검증 실패: jobId={} cloudConnectionId={}",
                    jobId, job.getCloudConnectionId(), exception);
            cloudConnection.markHealth(CloudConnectionStatus.UNKNOWN_ERROR, LocalDateTime.now());
            cloudConnectionRepository.save(cloudConnection);
            failJob(job, "클라우드 권한 확인 중 내부 오류가 발생했습니다.");
        }
    }

    private void failJob(CloudConnectionVerificationJob job, String message) {
        job.fail(message, LocalDateTime.now());
        verificationJobRepository.save(job);
    }
}
