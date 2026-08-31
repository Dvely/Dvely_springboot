package com.example.dvely.provisioning.application.command;

import com.example.dvely.common.exception.NotFoundException;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DB 프로비저닝 요청을 받는다.
 *
 * LOCAL 은 프리뷰 세션의 컨테이너 옆에 DB 컨테이너를 형제로 띄우므로 ACTIVE 프리뷰 세션이 있어야
 * 하고, 과금이 없어 승인 없이 즉시 처리한다. RDS·DOCKER 는 과금이라 승인을 거치는데, 그 흐름은
 * 다음 단계라 지금은 레지스트리가 "아직 지원되지 않습니다"로 던진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseProvisioningCommandService {

    private final ProvisionedDatabaseRepository databaseRepository;
    private final DatabaseProvisionerRegistry provisionerRegistry;
    private final PreviewSessionService previewSessionService;
    private final ProvisioningProperties properties;

    @Transactional
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

        ProvisionedDatabase record = databaseRepository.save(
                ProvisionedDatabase.pending(projectId, ProvisionMethod.LOCAL, engine));
        DatabaseProvisioner provisioner = provisionerRegistry.resolve(ProvisionMethod.LOCAL);

        record.markProvisioning();
        databaseRepository.save(record);
        try {
            ProvisionResult r = provisioner.provision(
                    new ProvisionSpec(projectId, engine), session.containerId());
            LocalDateTime expiresAt = LocalDateTime.now().plus(properties.localTtl());
            record.markReady(r.resourceId(), r.host(), r.port(), r.database(),
                    r.username(), r.password(), expiresAt);
            databaseRepository.save(record);
            log.info("LOCAL DB 프로비저닝 완료: databaseId={} projectId={} engine={}",
                    record.getId(), projectId, engine);
            return ProvisionSubmitResult.immediate(toCreated(record, r.password()));
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
