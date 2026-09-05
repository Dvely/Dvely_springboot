package com.example.dvely.provisioning.infrastructure.worker;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.domain.value.CloudProvider;
import com.example.dvely.provisioning.application.port.out.ActiveCdnDistributionPort;
import com.example.dvely.provisioning.infrastructure.CloudFrontDistributionProvisioner;
import com.example.dvely.provisioning.infrastructure.CloudFrontDistributionProvisioner.OwnedDistribution;
import com.example.dvely.provisioning.infrastructure.persistence.entity.CdnDeletionEntity;
import com.example.dvely.provisioning.infrastructure.persistence.repository.SpringDataCdnDeletionRepository;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 고아 CloudFront 배포 청소. 정상 삭제는 도메인 삭제 시 {@code cdn_deletions} 큐 + {@link CdnDeletionReaper}
 * 로 처리되지만, 그 큐 행이 유실되면(예: 큐잉 전 크래시) 배포가 어디에도 추적되지 않고 남아 과금된다. 이
 * 스위퍼가 매 주기 각 AWS 계정에서 <b>우리가 만든</b> 배포(comment 접두사)를 나열해, DB 가 참조하지도
 * 삭제 큐에 있지도 않은 것을 고아로 판정해 삭제 큐에 넣는다(실제 삭제는 리퍼가).
 *
 * <p><b>안전이 최우선</b>이다 — 활성 바인딩이 가리키는 배포를 지우면 라이브 사이트가 깨진다. 그래서 ①활성
 * 집합({@link ActiveCdnDistributionPort})에 있으면 절대 건드리지 않고 ②이미 삭제 큐에 있으면 건너뛰며
 * ③큐잉 <b>직전에 활성 집합을 다시 확인</b>해, 스냅샷 이후 방금 생성된 배포를 실수로 지우지 않는다. 태그를
 * 안 쓰므로(IAM 거부) comment 로 식별한다 — 남의 배포는 이 접두사가 없어 대상이 아니다.</p>
 *
 * <p>드문 사건이라 주기가 길다(기본 1시간). 계정 조회·삭제는 멱등이라 겹친 스윕이 해가 없다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrphanCloudFrontSweeper {

    private final CloudConnectionRepository cloudConnectionRepository;
    private final CloudFrontDistributionProvisioner cloudFrontProvisioner;
    private final ActiveCdnDistributionPort activeCdnDistributionPort;
    private final SpringDataCdnDeletionRepository deletionRepository;

    @Scheduled(fixedDelayString = "${qeploy.provisioning.cdn-orphan-sweep-interval-ms:3600000}")
    public void sweep() {
        for (CloudConnection connection : cloudConnectionRepository.findAllByProvider(CloudProvider.AWS)) {
            try {
                sweepConnection(connection);
            } catch (RuntimeException e) {
                // 한 계정 오류가 다른 계정 스윕을 막지 않게 — 다음 주기에 다시 본다.
                log.warn("고아 CloudFront 스윕 실패(다음 주기 재시도): connectionId={} 원인={}",
                        connection.getId(), e.toString());
            }
        }
    }

    private void sweepConnection(CloudConnection connection) {
        Set<String> tracked = activeCdnDistributionPort.trackedDistributionIds();
        for (OwnedDistribution dist : cloudFrontProvisioner.listOwnedDistributions(connection)) {
            if (tracked.contains(dist.distributionId())) {
                continue;   // 활성 바인딩이 참조 — 절대 지우지 않는다
            }
            if (deletionRepository.existsByDistributionId(dist.distributionId())) {
                continue;   // 이미 삭제 큐에 있음
            }
            // 안전 재확인: 스냅샷 이후 방금 생성된 배포(새 바인딩)를 실수로 큐잉하지 않게 활성 집합을 다시 본다.
            if (activeCdnDistributionPort.trackedDistributionIds().contains(dist.distributionId())) {
                continue;
            }
            deletionRepository.save(CdnDeletionEntity.of(
                    connection.getId(), dist.distributionId(), dist.certificateArn(), dist.hostname()));
            log.warn("고아 CloudFront 배포 발견 → 삭제 큐잉: distributionId={} hostname={} connectionId={}",
                    dist.distributionId(), dist.hostname(), connection.getId());
        }
    }
}
