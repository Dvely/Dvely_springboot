package com.example.dvely.provisioning.infrastructure.worker;

import com.example.dvely.provisioning.domain.model.ProvisionedDatabase;
import com.example.dvely.provisioning.domain.repository.ProvisionedDatabaseRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 만료된 LOCAL DB 를 회수한다. expiresAt 이 지났는데 아직 READY 인 자원을 찾아 실제 리소스를
 * 정리하고 상태를 EXPIRED 로 넘긴다.
 *
 * 이게 없으면 프리뷰 세션이 만료돼 DB 컨테이너가 사라진 뒤에도 화면에는 READY 로 남는다 —
 * "화면엔 있는데 실제로는 없는" 상태다. FE 와 계약할 때 짚은 그 함정을 이 워커가 막는다.
 * 주기를 1분으로 두면 "READY 인데 만료된" 구간이 최대 1분이라 사용자 눈에 거의 안 띈다.
 *
 * 스윕 자체는 트랜잭션으로 감싸지 않는다. 대신 각 행을 claimForExpiry 로 원자적으로(READY→EXPIRED)
 * 집은 뒤에만 실제 리소스 정리(Docker I/O)를 트랜잭션 밖에서 한다. 그래서 (1) 여러 인스턴스나
 * 겹친 스윕이 같은 DB 를 두 번 deprovision 하지 않고, (2) 느린 Docker 호출이 DB 커넥션을 물지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProvisionedDatabaseExpiryWorker {

    private static final int BATCH = 20;

    private final ProvisionedDatabaseRepository databaseRepository;
    private final com.example.dvely.provisioning.application.service.DatabaseProvisionerRegistry provisionerRegistry;

    @Scheduled(fixedDelayString = "${qeploy.provisioning.expiry-sweep-interval-ms:60000}")
    public void sweepExpired() {
        for (ProvisionedDatabase db : databaseRepository.findExpirable(LocalDateTime.now(), BATCH)) {
            // 원자적 클레임: READY 인 행만 EXPIRED 로 넘긴다. 진 워커만 통과한다.
            if (!databaseRepository.claimForExpiry(db.getId(), LocalDateTime.now())) {
                continue;   // 다른 스윕/인스턴스가 이미 집었다
            }
            try {
                if (db.getResourceId() != null) {
                    provisionerRegistry.resolve(db.getMethod()).deprovision(db.getResourceId());
                }
                log.info("만료 DB 회수: databaseId={} projectId={}", db.getId(), db.getProjectId());
            } catch (RuntimeException e) {
                // 상태는 이미 EXPIRED 로 확정됐다 — 이게 핵심 불변식이라, FE 는 죽은 DB 를 살아 있는
                // 것으로 보지 않는다. 컨테이너가 남을 수 있으나 임시 자원이라 손해가 작다.
                log.warn("만료 DB 리소스 정리 실패(상태는 EXPIRED 확정): databaseId={} 원인={}",
                        db.getId(), e.toString());
            }
        }
    }
}
