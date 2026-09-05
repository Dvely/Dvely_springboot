package com.example.dvely.provisioning.infrastructure.worker;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.provisioning.infrastructure.AcmCertificateProvisioner;
import com.example.dvely.provisioning.infrastructure.CloudFrontDistributionProvisioner;
import com.example.dvely.provisioning.infrastructure.CloudFrontDistributionProvisioner.DistributionState;
import com.example.dvely.provisioning.infrastructure.persistence.entity.CdnDeletionEntity;
import com.example.dvely.provisioning.infrastructure.persistence.repository.SpringDataCdnDeletionRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudfront.model.NoSuchDistributionException;

/**
 * CloudFront 배포 정리(리프) 큐를 폴링해 배포·인증서를 지운다. 배포 삭제는 <b>disable → Deployed 대기 →
 * delete</b> 라 도메인 삭제 시점에 못 끝낸다({@code cdn_deletions} 에 넣어 둔다). 매 주기: 비활성 아니면
 * 비활성화하고, 비활성 + Deployed 면 배포를 지운 뒤 인증서를 지우고 큐 행을 제거한다. 이미 없어진 배포는
 * 인증서만 정리하고 행을 지운다(멱등). 고아 CloudFront 배포·ACM 인증서를 남기지 않기 위한 자기치유다.
 *
 * <p>원자적 claim 을 두지 않는다 — describe/disable/delete 는 멱등하거나 NoSuch 로 안전하게 no-op 이라
 * 겹친 폴링이 해가 없다. 연결이 사라져 자격을 못 얻으면 그 행은 이번 주기 건너뛰고 다음에 다시 시도한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CdnDeletionReaper {

    private static final int BATCH = 20;

    private final SpringDataCdnDeletionRepository deletionRepository;
    private final CloudConnectionRepository cloudConnectionRepository;
    private final CloudFrontDistributionProvisioner cloudFrontProvisioner;
    private final AcmCertificateProvisioner acmProvisioner;

    @Scheduled(fixedDelayString = "${qeploy.provisioning.cdn-reap-interval-ms:60000}")
    public void reap() {
        for (CdnDeletionEntity row : deletionRepository.findAll(PageRequest.of(0, BATCH))) {
            try {
                reapOne(row);
            } catch (RuntimeException e) {
                // 이 행만 건너뛰고 다음 주기에 다시 본다.
                row.recordError(e.toString());
                deletionRepository.save(row);
                log.warn("CloudFront 리프 실패(다음 주기 재시도): distributionId={} 원인={}",
                        row.getDistributionId(), e.toString());
            }
        }
    }

    private void reapOne(CdnDeletionEntity row) {
        Optional<CloudConnection> connection = cloudConnectionRepository.findById(row.getCloudConnectionId());
        if (connection.isEmpty()) {
            // 연결이 삭제됨 — 자격이 없어 정리 불가. 행을 남겨 가시성을 유지하고 다음 주기에 다시 시도한다.
            log.warn("CloudFront 리프 건너뜀(클라우드 연결 없음): distributionId={} cloudConnectionId={}",
                    row.getDistributionId(), row.getCloudConnectionId());
            return;
        }
        CloudConnection conn = connection.get();

        DistributionState state;
        try {
            state = cloudFrontProvisioner.getState(conn, row.getDistributionId());
        } catch (NoSuchDistributionException alreadyGone) {
            // 배포가 이미 없다 — 인증서만 정리하고 큐에서 뺀다.
            finishCertAndRemove(conn, row);
            return;
        }

        if (state.enabled()) {
            cloudFrontProvisioner.disable(conn, row.getDistributionId());
            return;   // 비활성 전파(Deployed)를 다음 주기에 기다린다
        }
        if (!state.deployed()) {
            return;   // 비활성화가 아직 전파 중 — 다음 주기에 다시 본다
        }
        // 비활성 + Deployed → 삭제 가능
        cloudFrontProvisioner.delete(conn, row.getDistributionId());
        finishCertAndRemove(conn, row);
    }

    private void finishCertAndRemove(CloudConnection conn, CdnDeletionEntity row) {
        if (row.getCertificateArn() != null && !row.getCertificateArn().isBlank()) {
            try {
                acmProvisioner.deleteCertificate(conn, row.getCertificateArn());
            } catch (RuntimeException e) {
                // 인증서 삭제 실패는 치명적이지 않다(ACM 인증서는 무료·소량). 배포는 지워졌으니 행은 뺀다.
                log.warn("ACM 인증서 삭제 실패(무시): certArn={} 원인={}", row.getCertificateArn(), e.toString());
            }
        }
        deletionRepository.delete(row);
        log.info("CloudFront 리프 완료: distributionId={} hostname={}",
                row.getDistributionId(), row.getHostname());
    }
}
