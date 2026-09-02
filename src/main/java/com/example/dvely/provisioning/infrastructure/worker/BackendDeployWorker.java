package com.example.dvely.provisioning.infrastructure.worker;

import com.example.dvely.provisioning.application.service.BackendDeployRunner;
import com.example.dvely.provisioning.domain.model.ProvisionedServer;
import com.example.dvely.provisioning.domain.repository.ProvisionedServerRepository;
import com.example.dvely.provisioning.domain.value.ServerStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 승인·큐잉(QUEUED)된 EC2 서버 배포를 집어 실행한다. 무거운 빌드·다중 AWS 호출을 승인 트랜잭션 밖에서
 * 하기 위한 워커다. 각 행을 claimForBuild 로 원자적으로(QUEUED→BUILDING) 집은 뒤에만 러너를 부른다 —
 * 배포는 멱등이 아니라(인스턴스=과금), 여러 워커/인스턴스가 같은 행을 두 번 launch 하면 안 되기 때문이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BackendDeployWorker {

    private static final int BATCH = 5;

    private final ProvisionedServerRepository serverRepository;
    private final BackendDeployRunner deployRunner;

    @Scheduled(fixedDelayString = "${qeploy.provisioning.server-deploy-interval-ms:15000}")
    public void processQueued() {
        for (ProvisionedServer queued : serverRepository.findByStatus(ServerStatus.QUEUED, BATCH)) {
            if (!serverRepository.claimForBuild(queued.getId())) {
                continue;   // 다른 워커/인스턴스가 이미 집었다
            }
            ProvisionedServer building = serverRepository.findById(queued.getId()).orElse(null);
            if (building == null) {
                continue;
            }
            try {
                deployRunner.deploy(building);   // 러너가 성공/실패를 내부에서 상태로 확정한다
            } catch (RuntimeException e) {
                // 러너가 못 잡은 예기치 못한 오류 — 배치가 멈추지 않게 이 행만 건너뛴다. 상태는
                // BUILDING 에 남을 수 있으나(스윕/수동 정리 대상), 워커 자체는 계속 돈다.
                log.error("EC2 배포 실행 중 예기치 못한 오류: serverId={} 원인={}",
                        building.getId(), e.toString());
            }
        }
    }
}
