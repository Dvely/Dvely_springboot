package com.example.dvely.provisioning.application.command;

import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.project.domain.repository.ProjectCloudConnectionSettingRepository;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.provisioning.domain.model.ProvisionedServer;
import com.example.dvely.provisioning.domain.value.ServerStatus;
import com.example.dvely.provisioning.infrastructure.Ec2Provisioner;
import com.example.dvely.provisioning.infrastructure.S3ArtifactStore;
import com.example.dvely.provisioning.infrastructure.SsmParameterStore;
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
    private final ProjectRepository projectRepository;
    private final Ec2Provisioner ec2;
    private final SsmParameterStore ssm;
    private final S3ArtifactStore s3;

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

    /**
     * 서버를 종료한다 — 인스턴스 terminate + SSM 파라미터·S3 아티팩트 정리 후 TERMINATED. 잊힌 서버가
     * 무한정 과금되지 않게 하는 비용 가드레일. 실제 과금 자원은 인스턴스뿐이라 그것부터 확실히 끄고,
     * 부수 자원(SSM·S3)도 정리한다. 이미 종료된 것은 조용히 지나간다(멱등).
     */
    public void terminate(Long ownerUserId, Long serverId) {
        ProvisionedServer server = serverRepository.findById(serverId)
                .orElseThrow(() -> new NotFoundException("서버를 찾을 수 없습니다. serverId=" + serverId));
        // 소유권 확인 — 이 프로젝트가 요청자 것인지.
        projectRepository.findByIdAndOwnerUserId(server.getProjectId(), ownerUserId)
                .orElseThrow(() -> new NotFoundException("서버를 찾을 수 없거나 접근 권한이 없습니다. serverId=" + serverId));

        if (server.getStatus() == ServerStatus.TERMINATED) {
            return;   // 이미 종료됨
        }
        if (server.getCloudConnectionId() != null) {
            cloudConnectionRepository.findById(server.getCloudConnectionId()).ifPresent(connection -> {
                if (server.getInstanceId() != null) {
                    ec2.terminate(connection, server.getInstanceId());   // 과금 자원 — 실패 시 예외 전파(아직 안 꺼진 것)
                }
                // EIP 는 인스턴스가 종료돼도 연결만 풀리고 할당은 남아 계속 과금된다 → 반드시 release.
                // best-effort 지만 유휴 EIP 는 실제 과금이라 경고를 남긴다(수동 정리 유도).
                if (server.getElasticIpAllocationId() != null) {
                    try {
                        ec2.releaseElasticIp(connection, server.getElasticIpAllocationId());
                    } catch (RuntimeException e) {
                        log.warn("서버 종료 후 EIP release 실패(수동 정리 필요, 유휴 EIP 과금 주의): allocationId={} 원인={}",
                                server.getElasticIpAllocationId(), e.getMessage());
                    }
                }
                // 부수 자원(SSM 파라미터·S3 아티팩트) 정리는 best-effort 다. 과금이 멈추는 시점은 인스턴스
                // 종료이지 이 정리가 아니다. 여기서 실패했다고 예외를 올려 markTerminated 를 건너뛰면 서버가
                // RUNNING 으로 남아 "껐는데 안 꺼졌다"로 보인다(폴링도 멈추고 죽은 url 이 산 것처럼 남는다) —
                // 과금 자원이라 그 혼동이 특히 나쁘다. 정리 실패는 로그로 남기고 종료는 계속 진행한다.
                try {
                    ssm.deleteAllForProject(connection, server.getProjectId());
                } catch (RuntimeException e) {
                    log.warn("서버 종료 후 SSM 파라미터 정리 실패(수동 정리 필요): projectId={} 원인={}",
                            server.getProjectId(), e.getMessage());
                }
                try {
                    s3.deleteJar(connection, s3.bucketNameFor(connection), s3.jarKeyFor(server.getProjectId()));
                } catch (RuntimeException e) {
                    log.warn("서버 종료 후 S3 아티팩트 정리 실패(수동 정리 필요): projectId={} 원인={}",
                            server.getProjectId(), e.getMessage());
                }
            });
        }
        server.markTerminated();
        serverRepository.save(server);
        log.info("EC2 서버 종료: serverId={} projectId={} instanceId={}",
                serverId, server.getProjectId(), server.getInstanceId());
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
