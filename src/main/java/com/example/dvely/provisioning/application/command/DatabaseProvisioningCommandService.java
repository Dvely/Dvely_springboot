package com.example.dvely.provisioning.application.command;

import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.project.domain.repository.ProjectCloudConnectionSettingRepository;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.preview.application.port.out.PreviewDatabaseProvisioner;
import com.example.dvely.preview.application.result.PreviewDbConnection;
import com.example.dvely.preview.application.result.PreviewSessionInfo;
import com.example.dvely.preview.application.service.PreviewSessionService;
import com.example.dvely.provisioning.application.port.out.DatabaseProvisioner;
import com.example.dvely.provisioning.application.port.out.ProvisionResult;
import com.example.dvely.provisioning.application.port.out.ProvisionSpec;
import com.example.dvely.provisioning.application.result.ProvisionSubmitResult;
import com.example.dvely.provisioning.application.result.ProvisionSubmitResult.CreatedDatabase;
import com.example.dvely.provisioning.application.service.DatabaseProvisionerRegistry;
import com.example.dvely.provisioning.domain.model.ProvisionedDatabase;
import com.example.dvely.provisioning.domain.repository.ProvisionedDatabaseRepository;
import com.example.dvely.provisioning.domain.value.DatabaseEngine;
import com.example.dvely.provisioning.domain.value.ProvisionFailureCode;
import com.example.dvely.provisioning.domain.value.ProvisionMethod;
import com.example.dvely.provisioning.domain.value.ProvisionOrigin;
import com.example.dvely.provisioning.domain.value.ProvisionStatus;
import com.example.dvely.provisioning.infrastructure.RdsProvisioner;
import com.example.dvely.provisioning.infrastructure.config.ProvisioningProperties;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * DB 프로비저닝 요청을 받는다.
 *
 * LOCAL 은 프리뷰 세션의 컨테이너 옆에 DB 컨테이너를 형제로 띄우므로 ACTIVE 프리뷰 세션이 있어야
 * 하고, 과금이 없어 승인 없이 즉시 처리한다. RDS·DOCKER 는 과금이라 승인을 거치는데, 그 흐름은
 * 다음 단계라 지금은 레지스트리가 "아직 지원되지 않습니다"로 던진다.
 *
 * 서버형 프리뷰의 자동 프로비저닝은 {@link PreviewDatabaseProvisioner} 로 노출한다(preview 가
 * 정의한 포트를 여기서 구현 — provisioning→preview 방향 유지). 그 경로는 ACTIVE 게이트를 타지
 * 않고 컨테이너 ID 로 바로 띄운다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseProvisioningCommandService implements PreviewDatabaseProvisioner {

    /** 엔진 미지정 시 자동 프로비저닝 기본값. 2026-09-01 확정. 사용자가 런타임 설정에서 고르면 그게 우선. */
    private static final DatabaseEngine DEFAULT_PREVIEW_ENGINE = DatabaseEngine.MYSQL;

    private final ProvisionedDatabaseRepository databaseRepository;
    private final DatabaseProvisionerRegistry provisionerRegistry;
    private final PreviewSessionService previewSessionService;
    private final ProvisioningProperties properties;
    private final ProjectCloudConnectionSettingRepository cloudConnectionSettingRepository;
    private final CloudConnectionRepository cloudConnectionRepository;
    private final ApprovalRepository approvalRepository;
    private final RdsProvisioner rdsProvisioner;
    private final ProjectRepository projectRepository;

    // 일부러 메서드 전체를 한 트랜잭션으로 묶지 않는다. provisioner.provision() 이 수 분 걸리는
    // Docker I/O·이미지 pull 이라, 하나의 트랜잭션으로 감싸면 그동안 DB 커넥션을 물고 있어 풀이
    // 마른다. 또 실패 시 markFailed 를 저장하고 다시 던지는데, 트랜잭션으로 감싸면 그 rethrow 가
    // 전체를 롤백해 FAILED 감사 행마저 사라진다. 각 save 는 자체 트랜잭션으로 독립 커밋된다.
    public ProvisionSubmitResult provision(Long ownerUserId, Long projectId,
                                           ProvisionMethod method, DatabaseEngine engine) {
        return switch (method) {
            case LOCAL -> provisionLocal(ownerUserId, projectId, engine);
            case RDS -> submitRds(ownerUserId, projectId, engine);
            case DOCKER -> {
                provisionerRegistry.resolve(method);   // throws (DOCKER 미구현)
                throw new IllegalStateException(method + " 방식은 아직 지원되지 않습니다.");
            }
        };
    }

    /**
     * RDS 는 사용자 AWS 계정에 과금 자원을 만든다 — CONNECTED 클라우드 연결이 있어야 하고, 승인을
     * 거친다. 여기서는 pending 행과 승인을 만들어 requiresApproval 로 돌려주고, 실제 인스턴스 생성은
     * 승인 시 RdsProvisionApprovalHandler 가 시작한다(생성이 비동기라 즉시 만들 수 없다).
     */
    private ProvisionSubmitResult submitRds(Long ownerUserId, Long projectId, DatabaseEngine engine) {
        resolveConnectedCloud(ownerUserId, projectId);   // 검증만(없거나 미연결이면 던짐)

        ProvisionedDatabase record = databaseRepository.save(
                ProvisionedDatabase.pending(projectId, ProvisionMethod.RDS, engine, ProvisionOrigin.MANUAL));
        Approval approval = approvalRepository.save(Approval.standalone(
                ownerUserId, projectId, ApprovalType.DATABASE_PROVISION,
                "RDS " + engine + " 데이터베이스 생성 (과금)"));
        record.linkApproval(approval.getId());
        databaseRepository.save(record);

        log.info("RDS 프로비저닝 승인 대기: databaseId={} approvalId={} projectId={}",
                record.getId(), approval.getId(), projectId);
        return new ProvisionSubmitResult(true, null, null, java.util.List.of(approval.getId()));
    }

    /** 프로젝트에 선택된 CONNECTED 클라우드 연결을 돌려준다. 없거나 미연결이면 던진다. */
    /**
     * 프로비저닝된 DB 를 삭제한다 — 실제 자원 정리 후 EXPIRED. RDS 는 자격이 필요해 저장된
     * cloudConnectionId 의 연결로 deleteInstance 한다(생성에 쓴 그 계정). LOCAL 은 레지스트리
     * deprovision(컨테이너 정리). 잊힌 과금 자원을 지우는 경로 — 특히 RDS 는 자동 만료가 없어 이게
     * 유일한 정리 수단이다. 이미 EXPIRED 면 조용히 지나간다(멱등).
     */
    public void deleteDatabase(Long ownerUserId, Long databaseId) {
        ProvisionedDatabase record = databaseRepository.findById(databaseId)
                .orElseThrow(() -> new NotFoundException("데이터베이스를 찾을 수 없습니다. id=" + databaseId));
        // 소유권 확인(IDOR 방지) — 모든 method 공통. 이게 없으면 databaseId 만으로 남의 DB 를 지울 수
        // 있다(특히 LOCAL 은 아래에서 별도 소유권 검사가 없었다). 프로젝트 소유자 == 요청자여야 한다.
        projectRepository.findByIdAndOwnerUserId(record.getProjectId(), ownerUserId)
                .orElseThrow(() -> new NotFoundException(
                        "데이터베이스를 찾을 수 없거나 접근 권한이 없습니다. id=" + databaseId));
        if (record.getStatus() == ProvisionStatus.EXPIRED) {
            return;   // 이미 정리됨
        }
        if (record.getMethod() == ProvisionMethod.RDS) {
            // 소유권 확인 겸 자격 획득 — 생성에 쓴 연결로만 지운다.
            CloudConnection connection = record.getCloudConnectionId() == null ? null
                    : cloudConnectionRepository
                        .findByIdAndOwnerUserId(record.getCloudConnectionId(), ownerUserId).orElse(null);
            if (connection == null) {
                throw new NotFoundException(
                        "데이터베이스를 찾을 수 없거나 접근 권한이 없습니다(클라우드 연결 없음). id=" + databaseId);
            }
            if (record.getResourceId() != null) {
                rdsProvisioner.deleteInstance(connection, record.getResourceId());
            }
        } else if (record.getResourceId() != null) {
            // LOCAL/DOCKER: 자격이 필요 없는 정리(컨테이너 등).
            provisionerRegistry.resolve(record.getMethod()).deprovision(record.getResourceId());
        }
        record.markExpired();
        databaseRepository.save(record);
        log.info("DB 삭제: databaseId={} method={} projectId={} resourceId={}",
                databaseId, record.getMethod(), record.getProjectId(), record.getResourceId());
    }

    private CloudConnection resolveConnectedCloud(Long ownerUserId, Long projectId) {
        CloudConnection connection = cloudConnectionSettingRepository.findByProjectId(projectId)
                .flatMap(setting -> cloudConnectionRepository
                        .findByIdAndOwnerUserId(setting.getCloudConnectionId(), ownerUserId))
                .orElseThrow(() -> new NotFoundException(
                        "RDS 는 연결된 클라우드가 있어야 만들 수 있습니다. 인프라 탭에서 클라우드 연결을 먼저 선택해주세요."));
        if (connection.getStatus() != CloudConnectionStatus.CONNECTED) {
            throw new IllegalStateException("클라우드 연결이 CONNECTED 상태가 아닙니다. 연결을 확인한 뒤 다시 시도해주세요.");
        }
        return connection;
    }

    private ProvisionSubmitResult provisionLocal(Long ownerUserId, Long projectId, DatabaseEngine engine) {
        PreviewSessionInfo session = previewSessionService.findActiveByProject(projectId, ownerUserId)
                .orElseThrow(() -> new NotFoundException(
                        "테스트 DB 는 실행 중인 프리뷰가 있어야 만들 수 있습니다. 먼저 프리뷰를 띄워주세요."));

        ProvisionedDatabase record = provisionOnContainer(
                projectId, engine, session.containerId(), ProvisionOrigin.MANUAL);
        return ProvisionSubmitResult.immediate(toCreated(record, record.getPassword()));
    }

    /**
     * 서버형 프리뷰 부팅 시 자동 프로비저닝. ACTIVE 게이트를 타지 않고(아직 부팅 중이다) 넘겨받은
     * 컨테이너 ID 로 바로 형제 DB 를 띄운다. 엔진은 사용자가 런타임 설정에서 고른 값을 받는다
     * (없으면 기본 MySQL). origin=PREVIEW_AUTO 로 남겨 목록에서 수동 생성과 구분된다. 실패는
     * 던지고, 호출자(프리뷰 프로비저너)가 프리뷰를 FAILED 로 닫는다(혹은 best-effort 로 무시).
     */
    @Override
    public Optional<PreviewDbConnection> provisionForPreview(Long projectId, String containerId, String engine) {
        DatabaseEngine resolved = (engine == null || engine.isBlank())
                ? DEFAULT_PREVIEW_ENGINE : DatabaseEngine.valueOf(engine);
        ProvisionedDatabase record = provisionOnContainer(
                projectId, resolved, containerId, ProvisionOrigin.PREVIEW_AUTO);
        return Optional.of(new PreviewDbConnection(record.getEngine().name(), record.getHost(),
                record.getPort(), record.getDatabaseName(), record.getUsername(), record.getPassword()));
    }

    /**
     * pending → provisioning → provision → ready/failed 를 한 컨테이너에 대해 수행하고 그 행을
     * 돌려준다. 인프라 탭(ACTIVE 게이트, MANUAL)과 자동 프로비저닝(PREVIEW_AUTO)이 공유하는
     * 코어다. 트랜잭션으로 감싸지 않는 이유는 클래스 주석 참고 — 각 save 가 독립 커밋되고, 실패해도
     * FAILED 행이 남는다.
     */
    private ProvisionedDatabase provisionOnContainer(Long projectId, DatabaseEngine engine,
                                                     String containerId, ProvisionOrigin origin) {
        ProvisionedDatabase record = databaseRepository.save(
                ProvisionedDatabase.pending(projectId, ProvisionMethod.LOCAL, engine, origin));
        DatabaseProvisioner provisioner = provisionerRegistry.resolve(ProvisionMethod.LOCAL);

        record.markProvisioning();
        databaseRepository.save(record);
        try {
            ProvisionResult r = provisioner.provision(new ProvisionSpec(projectId, engine), containerId);
            LocalDateTime expiresAt = LocalDateTime.now().plus(properties.localTtl());
            record.markReady(r.resourceId(), r.host(), r.port(), r.database(),
                    r.username(), r.password(), expiresAt);
            databaseRepository.save(record);
            log.info("LOCAL DB 프로비저닝 완료: databaseId={} projectId={} engine={}",
                    record.getId(), projectId, engine);
            return record;
        } catch (RuntimeException e) {
            record.markFailed(ProvisionFailureCode.PROVIDER_ERROR, e.getMessage());
            databaseRepository.save(record);
            log.warn("LOCAL DB 프로비저닝 실패: databaseId={} 원인={}", record.getId(), e.toString());
            throw e;
        }
    }

    private CreatedDatabase toCreated(ProvisionedDatabase d, String password) {
        return new CreatedDatabase(d.getId(), d.getMethod().name(), d.getEngine().name(),
                d.getStatus().name(), d.getHost(), d.getPort(), d.getDatabaseName(),
                d.getUsername(), password, d.getExpiresAt());
    }
}
