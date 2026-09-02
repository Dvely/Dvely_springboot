package com.example.dvely.provisioning.application.command;

import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.project.domain.repository.ProjectCloudConnectionSettingRepository;
import com.example.dvely.provisioning.application.result.ServerProvisionSubmitResult;
import com.example.dvely.provisioning.domain.model.ProvisionedServer;
import com.example.dvely.provisioning.domain.repository.ProvisionedServerRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * EC2 백엔드 서버 배포 요청을 받는다. RDS 와 동형 — CONNECTED 클라우드 연결이 있어야 하고, 과금이라
 * 승인을 거친다. 여기서는 pending 행과 승인을 만들어 requiresApproval 로 돌려주고, 실제 빌드·인스턴스
 * 생성은 승인 후 워커가 한다(무거워서 승인 트랜잭션에 넣지 않는다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServerProvisioningCommandService {

    /** 기본 인스턴스 티어. 프리티어 대상이라 micro 기본. 사용자가 고르면 그게 우선. */
    private static final String DEFAULT_INSTANCE_TYPE = "t3.micro";
    /** 앱 포트 고정(내부 규약). 백엔드 앱은 8080 에서 서빙한다. */
    private static final int APP_PORT = 8080;

    private final ProvisionedServerRepository serverRepository;
    private final ProjectCloudConnectionSettingRepository cloudConnectionSettingRepository;
    private final CloudConnectionRepository cloudConnectionRepository;
    private final ApprovalRepository approvalRepository;

    public ServerProvisionSubmitResult submit(Long ownerUserId, Long projectId, String instanceType) {
        resolveConnectedCloud(ownerUserId, projectId);   // 검증만(없거나 미연결이면 던짐)

        String tier = (instanceType == null || instanceType.isBlank())
                ? DEFAULT_INSTANCE_TYPE : instanceType;
        ProvisionedServer record = serverRepository.save(
                ProvisionedServer.pending(projectId, tier, APP_PORT));
        Approval approval = approvalRepository.save(Approval.standalone(
                ownerUserId, projectId, ApprovalType.SERVER_PROVISION,
                "EC2 백엔드 서버 생성 (" + tier + ", 과금)"));
        record.linkApproval(approval.getId());
        serverRepository.save(record);

        log.info("EC2 서버 프로비저닝 승인 대기: serverId={} approvalId={} projectId={} type={}",
                record.getId(), approval.getId(), projectId, tier);
        return new ServerProvisionSubmitResult(true, record.getId(), List.of(approval.getId()));
    }

    private CloudConnection resolveConnectedCloud(Long ownerUserId, Long projectId) {
        CloudConnection connection = cloudConnectionSettingRepository.findByProjectId(projectId)
                .flatMap(setting -> cloudConnectionRepository
                        .findByIdAndOwnerUserId(setting.getCloudConnectionId(), ownerUserId))
                .orElseThrow(() -> new NotFoundException(
                        "백엔드 서버는 연결된 클라우드가 있어야 만들 수 있습니다. 인프라 탭에서 클라우드 연결을 먼저 선택해주세요."));
        if (connection.getStatus() != CloudConnectionStatus.CONNECTED) {
            throw new IllegalStateException("클라우드 연결이 CONNECTED 상태가 아닙니다. 연결을 확인한 뒤 다시 시도해주세요.");
        }
        return connection;
    }
}
