package com.example.dvely.provisioning.application.command;

import com.example.dvely.common.exception.NotFoundException;
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

    /** 서버형 프리뷰 자동 프로비저닝의 기본 엔진. 2026-09-01 확정. */
    private static final DatabaseEngine PREVIEW_AUTO_ENGINE = DatabaseEngine.MYSQL;

    private final ProvisionedDatabaseRepository databaseRepository;
    private final DatabaseProvisionerRegistry provisionerRegistry;
    private final PreviewSessionService previewSessionService;
    private final ProvisioningProperties properties;

    // 일부러 메서드 전체를 한 트랜잭션으로 묶지 않는다. provisioner.provision() 이 수 분 걸리는
    // Docker I/O·이미지 pull 이라, 하나의 트랜잭션으로 감싸면 그동안 DB 커넥션을 물고 있어 풀이
    // 마른다. 또 실패 시 markFailed 를 저장하고 다시 던지는데, 트랜잭션으로 감싸면 그 rethrow 가
    // 전체를 롤백해 FAILED 감사 행마저 사라진다. 각 save 는 자체 트랜잭션으로 독립 커밋된다.
    public ProvisionSubmitResult provision(Long ownerUserId, Long projectId,
                                           ProvisionMethod method, DatabaseEngine engine) {
        if (method == ProvisionMethod.LOCAL) {
            return provisionLocal(ownerUserId, projectId, engine);
        }
        // RDS·DOCKER 는 승인 흐름(다음 단계). 지금은 레지스트리가 명확히 던지게 한다.
        provisionerRegistry.resolve(method);   // throws IllegalArgumentException
        throw new IllegalStateException(method + " 방식은 아직 지원되지 않습니다.");
    }

    private ProvisionSubmitResult provisionLocal(Long ownerUserId, Long projectId, DatabaseEngine engine) {
        PreviewSessionInfo session = previewSessionService.findActiveByProject(projectId, ownerUserId)
                .orElseThrow(() -> new NotFoundException(
                        "테스트 DB 는 실행 중인 프리뷰가 있어야 만들 수 있습니다. 먼저 프리뷰를 띄워주세요."));

        ProvisionedDatabase record = provisionOnContainer(projectId, engine, session.containerId());
        return ProvisionSubmitResult.immediate(toCreated(record, record.getPassword()));
    }

    /**
     * 서버형 프리뷰 부팅 시 자동 프로비저닝. ACTIVE 게이트를 타지 않고(아직 부팅 중이다) 넘겨받은
     * 컨테이너 ID 로 바로 형제 DB 를 띄운다. 실패는 던지고, 호출자(프리뷰 프로비저너)가 프리뷰를
     * FAILED 로 닫는다.
     */
    @Override
    public Optional<PreviewDbConnection> provisionForPreview(Long projectId, String containerId) {
        ProvisionedDatabase record = provisionOnContainer(projectId, PREVIEW_AUTO_ENGINE, containerId);
        return Optional.of(new PreviewDbConnection(record.getEngine().name(), record.getHost(),
                record.getPort(), record.getDatabaseName(), record.getUsername(), record.getPassword()));
    }

    /**
     * pending → provisioning → provision → ready/failed 를 한 컨테이너에 대해 수행하고 그 행을
     * 돌려준다. 인프라 탭(ACTIVE 게이트)과 자동 프로비저닝이 공유하는 코어다. 트랜잭션으로 감싸지
     * 않는 이유는 클래스 주석 참고 — 각 save 가 독립 커밋되고, 실패해도 FAILED 행이 남는다.
     */
    private ProvisionedDatabase provisionOnContainer(Long projectId, DatabaseEngine engine, String containerId) {
        ProvisionedDatabase record = databaseRepository.save(
                ProvisionedDatabase.pending(projectId, ProvisionMethod.LOCAL, engine));
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
