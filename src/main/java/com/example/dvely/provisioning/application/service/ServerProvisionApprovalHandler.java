package com.example.dvely.provisioning.application.service;

import com.example.dvely.approval.application.port.out.StandaloneApprovalHandler;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.project.domain.repository.ProjectCloudConnectionSettingRepository;
import com.example.dvely.provisioning.domain.model.ProvisionedServer;
import com.example.dvely.provisioning.domain.repository.ProvisionedServerRepository;
import com.example.dvely.provisioning.domain.value.ServerStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SERVER_PROVISION(EC2) 승인의 approve/reject 후속. {@code ApprovalCommandService.approve/reject}
 * 트랜잭션 안에서 돈다({@link StandaloneApprovalHandler} 계약).
 *
 * <p>RDS 와 다르게 onApproved 는 실제 생성을 <b>시작하지 않는다</b> — 빌드+다중 AWS 호출이 수 분이라
 * 승인 트랜잭션에 넣으면 커넥션을 오래 물고, 실패 시 승인까지 롤백돼버린다. 대신 CONNECTED 만
 * 재확인하고 QUEUED 로 넘겨, 배포 워커(C2c-B)가 트랜잭션 밖에서 빌드→launch 한다. 여기서 던지면
 * (연결 해제 등) 승인 상태도 함께 롤백되어 '승인됐는데 큐에도 안 올라간' 상태가 남지 않는다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServerProvisionApprovalHandler implements StandaloneApprovalHandler {

    private final ProvisionedServerRepository serverRepository;
    private final ProjectCloudConnectionSettingRepository cloudConnectionSettingRepository;
    private final CloudConnectionRepository cloudConnectionRepository;

    @Override
    public boolean supports(ApprovalType type) {
        return type == ApprovalType.SERVER_PROVISION;
    }

    @Override
    public void onApproved(Approval approval) {
        ProvisionedServer record = findPending(approval.getId());
        CloudConnection connection = resolveConnectedCloud(approval.getOwnerUserId(), record.getProjectId());
        record.markQueued(connection.getId());   // 생성에 쓸 연결을 기억하고 워커에 넘긴다
        serverRepository.save(record);
        log.info("EC2 서버 배포 큐잉(승인됨): serverId={} projectId={} approvalId={}",
                record.getId(), record.getProjectId(), approval.getId());
    }

    @Override
    public void onRejected(Approval approval) {
        ProvisionedServer record = findPending(approval.getId());
        // 아직 AWS 자원을 만들지 않았으므로 지울 것이 없다 — 대기 행만 거부로 닫는다.
        record.markRejected("사용자가 백엔드 서버 생성을 거부했습니다.");
        serverRepository.save(record);
        log.info("EC2 서버 배포 거부: serverId={} projectId={} approvalId={}",
                record.getId(), record.getProjectId(), approval.getId());
    }

    private ProvisionedServer findPending(Long approvalId) {
        return serverRepository.findByApprovalId(approvalId)
                .filter(record -> record.getStatus() == ServerStatus.PENDING)
                .orElseThrow(() -> new IllegalStateException(
                        "승인에 연결된 대기 중 서버 배포를 찾을 수 없습니다. approvalId=" + approvalId));
    }

    private CloudConnection resolveConnectedCloud(Long ownerUserId, Long projectId) {
        CloudConnection connection = cloudConnectionSettingRepository.findByProjectId(projectId)
                .flatMap(setting -> cloudConnectionRepository
                        .findByIdAndOwnerUserId(setting.getCloudConnectionId(), ownerUserId))
                .orElseThrow(() -> new IllegalStateException(
                        "클라우드 연결이 해제되어 서버를 만들 수 없습니다. 연결을 다시 선택한 뒤 승인해주세요."));
        if (connection.getStatus() != CloudConnectionStatus.CONNECTED) {
            throw new IllegalStateException(
                    "클라우드 연결이 CONNECTED 상태가 아닙니다. 연결을 확인한 뒤 다시 승인해주세요.");
        }
        return connection;
    }
}
