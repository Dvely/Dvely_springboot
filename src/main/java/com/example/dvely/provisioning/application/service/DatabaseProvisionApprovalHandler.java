package com.example.dvely.provisioning.application.service;

import com.example.dvely.approval.application.port.out.StandaloneApprovalHandler;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.project.domain.repository.ProjectCloudConnectionSettingRepository;
import com.example.dvely.provisioning.domain.model.ProvisionedDatabase;
import com.example.dvely.provisioning.domain.repository.ProvisionedDatabaseRepository;
import com.example.dvely.provisioning.domain.value.ProvisionStatus;
import com.example.dvely.provisioning.infrastructure.DockerDbProvisioner;
import com.example.dvely.provisioning.infrastructure.DockerDbProvisioner.DockerDbCreation;
import com.example.dvely.provisioning.infrastructure.RdsProvisioner;
import com.example.dvely.provisioning.infrastructure.RdsProvisioner.RdsCreation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * DATABASE_PROVISION 승인의 approve/reject 후속. RDS 와 DOCKER(EC2 위 DB 컨테이너) 둘 다 처리하며,
 * 대기 행의 {@code method} 로 갈린다 — 둘 다 과금이라 승인을 거치고 생성이 비동기라, 여기서는 생성을
 * <b>시작만</b> 하고 PROVISIONING 으로 둔다. 실제 available 확인·host 채움은 상태 워커가 한다.
 *
 * <p>{@code ApprovalCommandService.resolveStandaloneHandler} 는 {@code supports()} 가 참인 <b>첫</b>
 * 핸들러를 고르므로, DATABASE_PROVISION 을 처리하는 핸들러는 이 하나뿐이어야 한다(둘이면 비결정적으로
 * 충돌한다) — 그래서 RDS·DOCKER 를 별도 핸들러로 두지 않고 method 로 분기한다.</p>
 *
 * <p>이 핸들러는 승인 결정 트랜잭션 안에서 돈다({@link StandaloneApprovalHandler} 계약) — 여기서 던지면
 * 승인 상태도 함께 롤백돼, 생성 시작 실패가 '승인됐는데 아무것도 안 만들어진' 상태로 남지 않는다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseProvisionApprovalHandler implements StandaloneApprovalHandler {

    private final ProvisionedDatabaseRepository databaseRepository;
    private final ProjectCloudConnectionSettingRepository cloudConnectionSettingRepository;
    private final CloudConnectionRepository cloudConnectionRepository;
    private final RdsProvisioner rdsProvisioner;
    private final DockerDbProvisioner dockerDbProvisioner;

    @Override
    public boolean supports(ApprovalType type) {
        return type == ApprovalType.DATABASE_PROVISION;
    }

    @Override
    public void onApproved(Approval approval) {
        ProvisionedDatabase record = findPending(approval.getId());
        CloudConnection connection = resolveConnectedCloud(approval.getOwnerUserId(), record.getProjectId());

        switch (record.getMethod()) {
            case RDS -> {
                RdsCreation c = rdsProvisioner.startCreation(connection, record.getEngine(), record.getProjectId());
                record.beginProvisioning(connection.getId(), c.instanceId(), c.port(),
                        c.database(), c.username(), c.password());
            }
            case DOCKER -> {
                DockerDbCreation c = dockerDbProvisioner.startCreation(
                        connection, record.getEngine(), record.getProjectId());
                record.beginProvisioning(connection.getId(), c.instanceId(), c.port(),
                        c.database(), c.username(), c.password());
            }
            case LOCAL -> throw new IllegalStateException(
                    "LOCAL DB 는 승인 대상이 아닙니다. approvalId=" + approval.getId());
        }
        databaseRepository.save(record);
        log.info("DB 생성 시작(승인됨): databaseId={} method={} instanceId={} projectId={} approvalId={}",
                record.getId(), record.getMethod(), record.getResourceId(), record.getProjectId(), approval.getId());
    }

    @Override
    public void onRejected(Approval approval) {
        ProvisionedDatabase record = findPending(approval.getId());
        // 아직 AWS 자원을 만들지 않았으므로 지울 것이 없다 — 대기 행만 거부로 닫는다(RDS·DOCKER 공통).
        record.markRejected("사용자가 데이터베이스 생성을 거부했습니다.");
        databaseRepository.save(record);
        log.info("DB 생성 거부: databaseId={} method={} projectId={} approvalId={}",
                record.getId(), record.getMethod(), record.getProjectId(), approval.getId());
    }

    private ProvisionedDatabase findPending(Long approvalId) {
        return databaseRepository.findByApprovalId(approvalId)
                .filter(record -> record.getStatus() == ProvisionStatus.PENDING)
                .orElseThrow(() -> new IllegalStateException(
                        "승인에 연결된 대기 중 DB 프로비저닝을 찾을 수 없습니다. approvalId=" + approvalId));
    }

    private CloudConnection resolveConnectedCloud(Long ownerUserId, Long projectId) {
        CloudConnection connection = cloudConnectionSettingRepository.findByProjectId(projectId)
                .flatMap(setting -> cloudConnectionRepository
                        .findByIdAndOwnerUserId(setting.getCloudConnectionId(), ownerUserId))
                .orElseThrow(() -> new IllegalStateException(
                        "클라우드 연결이 해제되어 DB 를 만들 수 없습니다. 연결을 다시 선택한 뒤 승인해주세요."));
        if (connection.getStatus() != CloudConnectionStatus.CONNECTED) {
            throw new IllegalStateException(
                    "클라우드 연결이 CONNECTED 상태가 아닙니다. 연결을 확인한 뒤 다시 승인해주세요.");
        }
        return connection;
    }
}
