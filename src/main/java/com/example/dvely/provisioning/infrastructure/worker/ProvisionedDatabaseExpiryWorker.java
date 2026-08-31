package com.example.dvely.provisioning.infrastructure.worker;

import com.example.dvely.provisioning.application.port.out.DatabaseProvisioner;
import com.example.dvely.provisioning.application.service.DatabaseProvisionerRegistry;
import com.example.dvely.provisioning.domain.model.ProvisionedDatabase;
import com.example.dvely.provisioning.domain.repository.ProvisionedDatabaseRepository;
import com.example.dvely.provisioning.infrastructure.config.ProvisioningProperties;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 만료된 LOCAL DB 를 회수한다. expiresAt 이 지났는데 아직 READY 인 자원을 찾아 실제 리소스를
 * 정리하고 상태를 EXPIRED 로 넘긴다.
 *
 * 이게 없으면 프리뷰 세션이 만료돼 DB 컨테이너가 사라진 뒤에도 화면에는 READY 로 남는다 —
 * "화면엔 있는데 실제로는 없는" 상태다. FE 와 계약할 때 짚은 그 함정을 이 워커가 막는다.
 * 주기를 1분으로 두면 "READY 인데 만료된" 구간이 최대 1분이라 사용자 눈에 거의 안 띈다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProvisionedDatabaseExpiryWorker {

    private static final int BATCH = 20;

    private final ProvisionedDatabaseRepository databaseRepository;
    private final DatabaseProvisionerRegistry provisionerRegistry;
    private final ProvisioningProperties properties;

    @Scheduled(fixedDelayString = "${qeploy.provisioning.expiry-sweep-interval-ms:60000}")
    @Transactional
    public void sweepExpired() {
        for (ProvisionedDatabase db : databaseRepository.findExpirable(LocalDateTime.now(), BATCH)) {
            try {
                if (db.getResourceId() != null) {
                    DatabaseProvisioner provisioner = provisionerRegistry.resolve(db.getMethod());
                    provisioner.deprovision(db.getResourceId());
                }
                db.markExpired();
                databaseRepository.save(db);
                log.info("만료 DB 회수: databaseId={} projectId={}", db.getId(), db.getProjectId());
            } catch (RuntimeException e) {
                // 한 자원의 정리 실패가 나머지를 막지 않는다. 다음 주기에 다시 시도한다.
                log.warn("만료 DB 회수 실패 — 다음 주기 재시도: databaseId={} 원인={}",
                        db.getId(), e.toString());
            }
        }
    }
}
