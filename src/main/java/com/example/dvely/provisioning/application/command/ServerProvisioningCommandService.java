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
import com.example.dvely.provisioning.domain.value.DatabaseEngine;
import com.example.dvely.provisioning.domain.value.ServerDeployMode;
import com.example.dvely.provisioning.domain.value.ServerStatus;
import com.example.dvely.provisioning.domain.value.WebFrontendSpec;
import com.example.dvely.provisioning.application.port.out.ProjectDomainCleanupPort;
import com.example.dvely.provisioning.infrastructure.Ec2Provisioner;
import com.example.dvely.provisioning.infrastructure.EcrImageRegistry;
import com.example.dvely.provisioning.infrastructure.S3ArtifactStore;
import com.example.dvely.provisioning.infrastructure.SsmParameterStore;
import com.example.dvely.provisioning.infrastructure.config.Ec2ProvisioningProperties;
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
    private final ProjectDomainCleanupPort projectDomainCleanupPort;
    private final SsmParameterStore ssm;
    private final S3ArtifactStore s3;
    private final EcrImageRegistry ecr;
    private final Ec2ProvisioningProperties ec2Properties;

    public ServerProvisionSubmitResult submit(Long ownerUserId, Long projectId, String instanceType,
                                              ServerDeployMode deployMode, DatabaseEngine bundledDbEngine,
                                              WebFrontendSpec web, boolean webOnly) {
        resolveConnectedCloud(ownerUserId, projectId);   // 검증만(없거나 미연결이면 던짐)

        String tier = (instanceType == null || instanceType.isBlank())
                ? DEFAULT_INSTANCE_TYPE : instanceType;
        ServerDeployMode mode = deployMode == null ? ServerDeployMode.NATIVE : deployMode;
        WebFrontendSpec webSpec = web == null ? new WebFrontendSpec(null, null, null) : web;
        // 웹 전용(독립 프론트 EC2): 백엔드 없이 프론트 nginx 만. 프론트 소스가 있어야 하고, 백엔드가 없어
        // 번들 DB 를 쓸 수 없다. DOCKER 강제는 아래 웹 컨테이너 가드가 겸한다(웹 전용 ⇒ hasWeb).
        if (webOnly && !webSpec.hasWeb()) {
            throw new IllegalStateException(
                    "웹 전용 서버는 프론트 소스(frontendRepo 또는 frontendDir)가 필요합니다.");
        }
        if (webOnly && bundledDbEngine != null) {
            throw new IllegalStateException("웹 전용 서버는 백엔드가 없어 번들 DB 를 쓸 수 없습니다.");
        }
        // 번들 DB·웹 컨테이너는 DOCKER 배포에서만 의미가 있다(같은 EC2 에 compose 로 컨테이너를 띄우므로).
        // NATIVE 인데 요청하면 조용히 무시하지 않고 명확히 거절한다.
        if (bundledDbEngine != null && mode != ServerDeployMode.DOCKER) {
            throw new IllegalStateException("번들 DB 는 DOCKER 배포 모드에서만 지원됩니다. deployMode=DOCKER 로 요청하세요.");
        }
        if (webSpec.hasWeb() && mode != ServerDeployMode.DOCKER) {
            throw new IllegalStateException("웹 컨테이너는 DOCKER 배포 모드에서만 지원됩니다. deployMode=DOCKER 로 요청하세요.");
        }
        ProvisionedServer server = ProvisionedServer.pending(projectId, tier, APP_PORT, mode, bundledDbEngine, webSpec);
        server.assignWebOnly(webOnly);
        // 재배포면(같은 프로젝트에 동일 webOnly 의 현재 RUNNING 서버가 있으면) 그 서버를 교체 대상으로 기록한다.
        // 새 서버가 RUNNING 되면 리플레이스 워커가 EIP 를 넘겨받고 옛 서버를 종료한다(블루그린, 고아 없음).
        // webOnly 로 갈라 백엔드 재배포는 백엔드만, 프론트 재배포는 프론트만 교체한다(둘은 정상 공존).
        serverRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(existing -> existing.getStatus() == ServerStatus.RUNNING && existing.isWebOnly() == webOnly)
                .findFirst()
                .ifPresent(existing -> server.assignSupersedes(existing.getId()));
        ProvisionedServer record = serverRepository.save(server);
        Approval approval = approvalRepository.save(Approval.standalone(
                ownerUserId, projectId, ApprovalType.SERVER_PROVISION,
                (webOnly ? "EC2 프론트 서버 생성 (" : "EC2 백엔드 서버 생성 (") + tier + ", 과금)"));
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
                // EIP 가 해제되면 그 IP 를 가리키던 도메인(백엔드·독립 프론트)은 dangling DNS(서브도메인
                // 탈취) 위험이 된다 — Cloudflare 레코드를 지운다. best-effort(실패해도 종료는 계속, 경고는 남는다).
                if (server.getPublicHost() != null) {
                    try {
                        projectDomainCleanupPort.releaseServerDomains(server.getProjectId(), server.getPublicHost());
                    } catch (RuntimeException e) {
                        log.warn("서버 종료 후 도메인 정리 실패(수동 확인 필요, dangling DNS 위험): projectId={} ip={} 원인={}",
                                server.getProjectId(), server.getPublicHost(), e.getMessage());
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
                // 이미지 전달 방식대로 아티팩트를 지운다: ECR 전달이면 ECR 저장소(이미지째), 아니면 S3 객체
                // (DOCKER=image.tar / NATIVE=app.jar). 전달 방식은 배포 당시 설정(useEcr)을 따른다 — 배포와
                // 종료 사이에 설정을 바꾸면 반대편 아티팩트가 남을 수 있다(실험 기능, 문서화된 한계).
                boolean useEcr = server.getDeployMode() == ServerDeployMode.DOCKER && ec2Properties.useEcr();
                try {
                    String bucket = s3.bucketNameFor(connection);
                    if (useEcr) {
                        ecr.deleteRepository(connection, server.getProjectId());
                    } else if (server.getDeployMode() == ServerDeployMode.DOCKER) {
                        s3.deleteJar(connection, bucket, s3.imageKeyFor(server.getProjectId()));
                    } else {
                        // NATIVE — Java=app.jar / Node=app-src.tar. 런타임을 안 저장하므로 둘 다 지운다(멱등).
                        s3.deleteJar(connection, bucket, s3.jarKeyFor(server.getProjectId()));
                        s3.deleteJar(connection, bucket, s3.nodeSourceKeyFor(server.getProjectId()));
                    }
                } catch (RuntimeException e) {
                    log.warn("서버 종료 후 이미지 아티팩트 정리 실패(수동 정리 필요): projectId={} useEcr={} 원인={}",
                            server.getProjectId(), useEcr, e.getMessage());
                }
                // 웹(프론트) 컨테이너를 썼으면 웹 이미지도 정리한다(전달방식대로).
                if (server.hasWebFrontend()) {
                    try {
                        if (useEcr) {
                            ecr.deleteWebRepository(connection, server.getProjectId());
                        } else {
                            s3.deleteJar(connection, s3.bucketNameFor(connection),
                                    s3.webImageKeyFor(server.getProjectId()));
                        }
                    } catch (RuntimeException e) {
                        log.warn("서버 종료 후 웹 이미지 정리 실패(수동 정리 필요): projectId={} 원인={}",
                                server.getProjectId(), e.getMessage());
                    }
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
