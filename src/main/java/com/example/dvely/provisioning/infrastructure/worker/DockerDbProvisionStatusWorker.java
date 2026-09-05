package com.example.dvely.provisioning.infrastructure.worker;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.provisioning.domain.model.ProvisionedDatabase;
import com.example.dvely.provisioning.domain.repository.ProvisionedDatabaseRepository;
import com.example.dvely.provisioning.domain.value.ProvisionFailureCode;
import com.example.dvely.provisioning.domain.value.ProvisionMethod;
import com.example.dvely.provisioning.domain.value.ProvisionStatus;
import com.example.dvely.provisioning.infrastructure.DockerDbProvisioner;
import com.example.dvely.provisioning.infrastructure.DockerDbProvisioner.DockerDbStatus;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 승인 후 생성이 시작된 DOCKER DB(EC2 위 DB 컨테이너)를 폴링해 마무리한다. 사설 VPC 안이라 직접
 * 헬스체크가 불가하므로, DB EC2 가 준비되면 자기 사설 IP 를 SSM 에 self-report 하고 이 워커가 그 값을
 * 폴링해 host 로 채운다(markReady). host 를 얻으면 그 self-report 파라미터는 지운다 — 같은 프로젝트
 * 경로라 백엔드 SSM 풀에 섞이지 않게.
 *
 * <p>method==DOCKER 만 처리한다(RDS 워커는 RDS 만). 부트 타임아웃(20분) 안에 준비되지 않거나 인스턴스가
 * 종료 상태면 FAILED 로 닫고 과금 인스턴스를 정리한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DockerDbProvisionStatusWorker {

    private static final int BATCH = 20;
    private static final Duration BOOT_TIMEOUT = Duration.ofMinutes(20);
    private static final Set<String> TERMINAL_STATES = Set.of(
            "terminated", "shutting-down", "stopping", "stopped");

    private final ProvisionedDatabaseRepository databaseRepository;
    private final CloudConnectionRepository cloudConnectionRepository;
    private final DockerDbProvisioner dockerDbProvisioner;

    @Scheduled(fixedDelayString = "${qeploy.provisioning.docker-db-poll-interval-ms:30000}")
    public void pollProvisioning() {
        for (ProvisionedDatabase db : databaseRepository.findByStatus(ProvisionStatus.PROVISIONING, BATCH)) {
            if (db.getMethod() != ProvisionMethod.DOCKER) {
                continue;   // RDS·LOCAL 은 이 워커 대상 아님
            }
            try {
                pollOne(db);
            } catch (RuntimeException e) {
                log.warn("DOCKER DB 상태 폴링 실패(다음 주기 재시도): databaseId={} instanceId={} 원인={}",
                        db.getId(), db.getResourceId(), e.toString());
            }
        }
    }

    private void pollOne(ProvisionedDatabase db) {
        // 생성에 쓴 그 연결로 조회한다(도중에 프로젝트 연결이 바뀌어도 엉뚱한 계정으로 오판하지 않게).
        Optional<CloudConnection> connection = db.getCloudConnectionId() == null
                ? Optional.empty()
                : cloudConnectionRepository.findById(db.getCloudConnectionId());
        if (connection.isEmpty()) {
            log.warn("DOCKER DB 상태 폴링 건너뜀(클라우드 연결 없음): databaseId={} cloudConnectionId={}",
                    db.getId(), db.getCloudConnectionId());
            return;
        }
        CloudConnection conn = connection.get();
        DockerDbStatus status = dockerDbProvisioner.resolveStatus(conn, db.getProjectId(), db.getResourceId());

        if (status.host() != null) {
            // 준비 완료 — self-report 된 사설 IP 를 host 로. 접속계정·비번은 생성 시작 때 저장한 값 그대로.
            db.markReady(db.getResourceId(), status.host(), db.getPort(), db.getDatabaseName(),
                    db.getUsername(), db.getPassword(), null);   // DOCKER DB 는 만료 없음(RDS 와 동일)
            databaseRepository.save(db);
            dockerDbProvisioner.clearReadySignal(conn, db.getProjectId(), db.getResourceId());
            log.info("DOCKER DB 프로비저닝 완료: databaseId={} instanceId={} host={} projectId={}",
                    db.getId(), db.getResourceId(), status.host(), db.getProjectId());
        } else if (TERMINAL_STATES.contains(status.ec2State())) {
            db.markFailed(ProvisionFailureCode.PROVIDER_ERROR,
                    "DB EC2 인스턴스 상태가 " + status.ec2State() + " 입니다.");
            databaseRepository.save(db);
            log.warn("DOCKER DB 프로비저닝 실패: databaseId={} instanceId={} state={}",
                    db.getId(), db.getResourceId(), status.ec2State());
        } else if (db.getUpdatedAt().plus(BOOT_TIMEOUT).isBefore(LocalDateTime.now())) {
            // 다중 인스턴스: 부트 타임아웃 처리 권한을 CAS 로 claim(PROVISIONING→FAILED). 진 인스턴스
            // 하나만 teardown 한다 — 두 곳이 같은 DB 인스턴스를 정리하지 않게.
            if (!databaseRepository.claimBootTimeout(db.getId())) {
                return;
            }
            db.markFailed(ProvisionFailureCode.PROVIDER_ERROR,
                    "제한 시간 안에 DB 가 준비되지 않았습니다(사설 IP self-report 없음).");
            databaseRepository.save(db);
            dockerDbProvisioner.teardown(conn, db.getProjectId(), db.getResourceId());   // 과금 인스턴스 정리
            log.warn("DOCKER DB 부트 타임아웃 — 인스턴스 정리: databaseId={} instanceId={}",
                    db.getId(), db.getResourceId());
        }
        // 그 외: 아직 부팅/초기화 중 — 저장 없이 다음 주기에 다시 본다.
    }
}
