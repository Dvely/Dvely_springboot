package com.example.dvely.provisioning.infrastructure.worker;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.provisioning.domain.model.ProvisionedDatabase;
import com.example.dvely.provisioning.domain.repository.ProvisionedDatabaseRepository;
import com.example.dvely.provisioning.domain.value.ProvisionFailureCode;
import com.example.dvely.provisioning.domain.value.ProvisionMethod;
import com.example.dvely.provisioning.domain.value.ProvisionStatus;
import com.example.dvely.provisioning.infrastructure.RdsProvisioner;
import com.example.dvely.provisioning.infrastructure.RdsProvisioner.RdsStatus;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 승인 후 생성이 시작된 RDS 인스턴스를 폴링해 마무리한다. 승인 핸들러는 생성을 시작만 하고
 * PROVISIONING(host 없음)으로 두므로(RDS 는 수 분 비동기), 이 워커가 available 이 될 때까지
 * {@link RdsProvisioner#describe}로 확인하고 endpoint 가 나오면 markReady 로 접속정보를 채운다.
 *
 * <p>LOCAL 은 동기라 PROVISIONING 에 머물지 않는다 — method==RDS 만 처리한다. 원자적 claim 을
 * 두지 않는 이유: describe 는 읽기이고 markReady/markFailed 는 멱등이라, 겹친 폴링이 같은 행을
 * 두 번 봐도 해가 없다(중복 생성이 아니다). 연결이 사라져 describe 할 자격을 못 얻으면 그 주기엔
 * 건너뛰고 다음 주기에 다시 시도한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RdsProvisionStatusWorker {

    private static final int BATCH = 20;

    // RDS dbInstanceStatus 중 더는 진행하지 않는 실패/소멸 상태. 이 외("creating","backing-up",
    // "configuring-*","modifying" 등)는 아직 생성 중이라 다음 주기에 다시 본다.
    private static final Set<String> TERMINAL_FAILURES = Set.of(
            "failed", "incompatible-parameters", "incompatible-restore",
            "incompatible-network", "deleting", "deleted");

    private final ProvisionedDatabaseRepository databaseRepository;
    private final CloudConnectionRepository cloudConnectionRepository;
    private final RdsProvisioner rdsProvisioner;

    @Scheduled(fixedDelayString = "${qeploy.provisioning.rds-poll-interval-ms:30000}")
    public void pollProvisioning() {
        for (ProvisionedDatabase db : databaseRepository.findByStatus(ProvisionStatus.PROVISIONING, BATCH)) {
            if (db.getMethod() != ProvisionMethod.RDS) {
                continue;   // LOCAL 은 동기 — 여기 머물면 이상 상태이므로 건드리지 않는다
            }
            try {
                pollOne(db);
            } catch (RuntimeException e) {
                // 이 행만 건너뛰고 다음 주기에 다시 본다 — 한 인스턴스의 일시 오류가 배치를 멈추지 않게.
                log.warn("RDS 상태 폴링 실패(다음 주기 재시도): databaseId={} instanceId={} 원인={}",
                        db.getId(), db.getResourceId(), e.toString());
            }
        }
    }

    private void pollOne(ProvisionedDatabase db) {
        // 프로젝트의 '현재' 선택이 아니라, 생성에 쓴 그 연결로 조회한다(V37) — 도중에 연결이 바뀌어도
        // 엉뚱한 계정으로 봐서 살아있는 인스턴스를 실패로 오판하지 않게.
        Optional<CloudConnection> connection = db.getCloudConnectionId() == null
                ? Optional.empty()
                : cloudConnectionRepository.findById(db.getCloudConnectionId());
        if (connection.isEmpty()) {
            // 연결이 삭제됨 — 자격이 없어 조회 불가. 이번 주기엔 건너뛰고 다음에 다시 시도한다.
            log.warn("RDS 상태 폴링 건너뜀(클라우드 연결 없음): databaseId={} cloudConnectionId={}",
                    db.getId(), db.getCloudConnectionId());
            return;
        }
        RdsStatus status = rdsProvisioner.describe(connection.get(), db.getResourceId());
        if ("available".equals(status.status()) && status.host() != null) {
            // 접속계정은 생성 시작 때 저장해 둔 값을 그대로 쓴다 — host 만 이번에 얻었다.
            db.markReady(db.getResourceId(), status.host(), db.getPort(), db.getDatabaseName(),
                    db.getUsername(), db.getPassword(), null);   // RDS 는 만료 없음(expiresAt=null)
            databaseRepository.save(db);
            log.info("RDS 프로비저닝 완료: databaseId={} instanceId={} host={} projectId={}",
                    db.getId(), db.getResourceId(), status.host(), db.getProjectId());
        } else if (TERMINAL_FAILURES.contains(status.status())) {
            db.markFailed(ProvisionFailureCode.PROVIDER_ERROR,
                    "RDS 인스턴스 상태가 " + status.status() + " 입니다.");
            databaseRepository.save(db);
            log.warn("RDS 프로비저닝 실패: databaseId={} instanceId={} status={}",
                    db.getId(), db.getResourceId(), status.status());
        }
        // 그 외: 아직 생성 중 — 상태 저장 없이 다음 주기에 다시 본다.
    }

}
