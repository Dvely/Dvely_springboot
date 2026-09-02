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
import com.example.dvely.provisioning.infrastructure.RdsProvisioner;
import com.example.dvely.provisioning.infrastructure.RdsProvisioner.RdsCreation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * DATABASE_PROVISION(RDS) 승인의 approve/reject 후속. {@code ApprovalCommandService.approve/reject}
 * 트랜잭션 안에서 돈다({@link StandaloneApprovalHandler} 계약) — 여기서 던지면 승인 상태 변경까지
 * 함께 롤백되므로, RDS 생성 시작이 실패하면 승인이 '승인됐는데 아무것도 안 만들어진' 상태로 남지 않는다.
 *
 * <p>onApproved 는 생성을 <b>시작만</b> 한다(RDS 는 수 분 비동기). 실제 available 확인과 접속정보
 * 채움은 상태 워커({@code RdsProvisionStatusWorker})가 {@link RdsProvisioner#describe}로 폴링해
 * markReady 로 마무리한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RdsProvisionApprovalHandler implements StandaloneApprovalHandler {

    private final ProvisionedDatabaseRepository databaseRepository;
    private final ProjectCloudConnectionSettingRepository cloudConnectionSettingRepository;
    private final CloudConnectionRepository cloudConnectionRepository;
    private final RdsProvisioner rdsProvisioner;

    @Override
    public boolean supports(ApprovalType type) {
        return type == ApprovalType.DATABASE_PROVISION;
    }

    @Override
    public void onApproved(Approval approval) {
        ProvisionedDatabase record = findPending(approval.getId());
        // 승인은 한동안 대기했을 수 있다 — submit 시점의 CONNECTED 가 지금도 유효하다고 믿지 않고
        // 다시 확인한다. 여기서 던지면(생성 시작 전) 승인 상태도 함께 롤백되어, 사용자는 연결을
        // 복구한 뒤 다시 승인만 하면 된다(전체 요청을 다시 하지 않아도 된다).
        CloudConnection connection = resolveConnectedCloud(approval.getOwnerUserId(), record.getProjectId());

        RdsCreation creation = rdsProvisioner.startCreation(
                connection, record.getEngine(), record.getProjectId());
        // host 는 아직 없다(생성 중). resourceId(인스턴스 ID)·port·접속계정만 저장하고 PROVISIONING
        // 으로 둔다 — 상태 워커가 available 이 되면 describe 로 host 를 얻어 markReady 한다.
        record.beginProvisioning(connection.getId(), creation.instanceId(), creation.port(),
                creation.database(), creation.username(), creation.password());
        databaseRepository.save(record);
        log.info("RDS 생성 시작(승인됨): databaseId={} instanceId={} projectId={} approvalId={}",
                record.getId(), creation.instanceId(), record.getProjectId(), approval.getId());
    }

    @Override
    public void onRejected(Approval approval) {
        ProvisionedDatabase record = findPending(approval.getId());
        // 아직 AWS 자원을 만들지 않았으므로 지울 것이 없다 — 대기 행만 거부로 닫는다.
        record.markRejected("사용자가 데이터베이스 생성을 거부했습니다.");
        databaseRepository.save(record);
        log.info("RDS 생성 거부: databaseId={} projectId={} approvalId={}",
                record.getId(), record.getProjectId(), approval.getId());
    }

    private ProvisionedDatabase findPending(Long approvalId) {
        return databaseRepository.findByApprovalId(approvalId)
                .filter(record -> record.getStatus() == ProvisionStatus.PENDING)
                .orElseThrow(() -> new IllegalStateException(
                        "승인에 연결된 대기 중 RDS 프로비저닝을 찾을 수 없습니다. approvalId=" + approvalId));
    }

    private CloudConnection resolveConnectedCloud(Long ownerUserId, Long projectId) {
        CloudConnection connection = cloudConnectionSettingRepository.findByProjectId(projectId)
                .flatMap(setting -> cloudConnectionRepository
                        .findByIdAndOwnerUserId(setting.getCloudConnectionId(), ownerUserId))
                .orElseThrow(() -> new IllegalStateException(
                        "클라우드 연결이 해제되어 RDS 를 만들 수 없습니다. 연결을 다시 선택한 뒤 승인해주세요."));
        if (connection.getStatus() != CloudConnectionStatus.CONNECTED) {
            throw new IllegalStateException(
                    "클라우드 연결이 CONNECTED 상태가 아닙니다. 연결을 확인한 뒤 다시 승인해주세요.");
        }
        return connection;
    }
}
