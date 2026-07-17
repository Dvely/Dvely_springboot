package com.example.dvely.project.application.service;

import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.project.application.result.ProjectInfrastructureChangeResult;
import com.example.dvely.project.application.result.ProjectInfrastructureConfigurationResult;
import com.example.dvely.project.domain.exception.ProjectNotFoundException;
import com.example.dvely.project.domain.model.ProjectApprovalPolicy;
import com.example.dvely.project.domain.model.ProjectInfrastructureSetting;
import com.example.dvely.project.domain.model.ProjectInfrastructureSettingChange;
import com.example.dvely.project.domain.repository.ProjectApprovalPolicyRepository;
import com.example.dvely.project.domain.repository.ProjectCloudConnectionSettingRepository;
import com.example.dvely.project.domain.repository.ProjectInfrastructureSettingChangeRepository;
import com.example.dvely.project.domain.repository.ProjectInfrastructureSettingRepository;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.InfrastructureChangeAction;
import com.example.dvely.project.domain.value.InfrastructureChangeStatus;
import com.example.dvely.project.domain.value.InfrastructureConfiguration;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GET/PUT/history for {@code .../settings/infrastructure/configuration} (design §6, BI-121~124,
 * BI-129, BI-097). Deliberately does not depend on {@code ProjectInfrastructureSettingsService}
 * (the existing cloud-connection-selection service) even though both need "is a CONNECTED
 * connection selected" — U6 may call that service concurrently in the parallel deployment unit,
 * and this unit's own CONNECTED check is a read-only, side-effect-free duplicate of a few lines
 * rather than a shared dependency that would couple two independently-evolving units.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectInfrastructureConfigurationService {

    private static final int DEFAULT_HISTORY_LIMIT = 50;
    private static final int MAX_HISTORY_LIMIT = 200;

    private final ProjectRepository projectRepository;
    private final ProjectCloudConnectionSettingRepository cloudConnectionSettingRepository;
    private final CloudConnectionRepository cloudConnectionRepository;
    private final ProjectInfrastructureSettingRepository settingRepository;
    private final ProjectInfrastructureSettingChangeRepository changeRepository;
    private final ProjectApprovalPolicyRepository policyRepository;
    private final ApprovalRepository approvalRepository;

    @Transactional(readOnly = true)
    public ProjectInfrastructureConfigurationResult get(Long ownerUserId, Long projectId) {
        assertProjectOwner(ownerUserId, projectId);
        return toResult(projectId, isConnectedCloudSelected(ownerUserId, projectId));
    }

    @Transactional
    public ProjectInfrastructureConfigurationResult update(Long ownerUserId,
                                                            Long projectId,
                                                            String deploymentArchitecture,
                                                            String computeTier,
                                                            String storageType,
                                                            String networkAccess) {
        // Order matters (design §6): ownership before validation before state guards, so a
        // request against someone else's project 404s even if its enum values are garbage, and
        // a well-formed request against your own project still 409s if the project isn't ready
        // for it — never leaking "is this a valid enum" info to a non-owner via a 400 vs 404
        // distinction.
        assertProjectOwner(ownerUserId, projectId);
        InfrastructureConfiguration requested =
                InfrastructureConfiguration.parse(deploymentArchitecture, computeTier, storageType, networkAccess);
        if (!isConnectedCloudSelected(ownerUserId, projectId)) {
            throw new IllegalStateException(
                    "프로젝트 Infrastructure 설정에서 실제 권한 확인이 완료된 클라우드 연결을 먼저 선택해야 합니다.");
        }
        assertNoPendingChange(projectId);

        Optional<ProjectInfrastructureSetting> existing = settingRepository.findByProjectId(projectId);
        if (existing.isPresent() && existing.get().getConfiguration().equals(requested)) {
            // D10: identical PUT is a no-op — no history row, no approval, current state as-is.
            log.info("Infrastructure configuration PUT is a no-op. projectId={}", projectId);
            return toResult(projectId, true);
        }

        InfrastructureChangeAction action = existing.isPresent()
                ? InfrastructureChangeAction.UPDATED
                : InfrastructureChangeAction.CREATED;
        ProjectApprovalPolicy policy = policyRepository.findByProjectId(projectId)
                .orElseGet(() -> new ProjectApprovalPolicy(projectId));
        boolean approvalRequired = policy.requires(ApprovalType.INFRA_OPERATION);
        log.info("Infrastructure configuration change requested. projectId={}, action={}, approvalRequired={}",
                projectId, action, approvalRequired);

        if (!approvalRequired) {
            ProjectInfrastructureSetting setting = existing
                    .orElseGet(() -> new ProjectInfrastructureSetting(projectId, requested));
            setting.apply(requested);
            settingRepository.save(setting);
            changeRepository.save(
                    ProjectInfrastructureSettingChange.applied(projectId, action, requested, ownerUserId));
            log.info("Infrastructure configuration applied immediately. projectId={}, action={}", projectId, action);
            return toResult(projectId, true);
        }

        // Approval saved first so its generated id can be linked into the change row — the
        // change is the payload the approval is "about", and it must always be resolvable from
        // the approval (InfrastructureChangeApprovalHandler looks it up by approvalId).
        Approval approval = approvalRepository.save(Approval.standalone(
                ownerUserId, projectId, ApprovalType.INFRA_OPERATION, summaryFor(action, requested)
        ));
        changeRepository.save(ProjectInfrastructureSettingChange.pendingApproval(
                projectId, action, requested, approval.getId(), ownerUserId
        ));
        log.info("Standalone infrastructure approval created. projectId={}, approvalId={}, action={}",
                projectId, approval.getId(), action);
        return toResult(projectId, true);
    }

    @Transactional(readOnly = true)
    public List<ProjectInfrastructureChangeResult> getHistory(Long ownerUserId, Long projectId, Integer limit) {
        assertProjectOwner(ownerUserId, projectId);
        return changeRepository.findByProjectIdOrderByCreatedAtDescIdDesc(projectId, clampLimit(limit)).stream()
                .map(this::toChangeResult)
                .toList();
    }

    private void assertNoPendingChange(Long projectId) {
        List<ProjectInfrastructureSettingChange> pending = changeRepository
                .findByProjectIdAndStatusOrderByIdAsc(projectId, InfrastructureChangeStatus.PENDING_APPROVAL);
        if (!pending.isEmpty()) {
            throw new IllegalStateException(
                    "승인 대기 중인 인프라 설정 변경이 이미 있습니다. 해당 승인을 처리한 뒤 다시 시도해주세요. approvalId="
                            + pending.get(0).getApprovalId());
        }
    }

    /**
     * "configurable" (design §3.1) and the PUT precondition (design D7) share this exact
     * computation — GET reads it as an informational flag, PUT turns a {@code false} into a 409.
     * Never throws: any missing link in the chain (no selection, connection deleted, not
     * CONNECTED) resolves to {@code false} rather than propagating an exception, because GET
     * must always return 200.
     */
    private boolean isConnectedCloudSelected(Long ownerUserId, Long projectId) {
        return cloudConnectionSettingRepository.findByProjectId(projectId)
                .flatMap(setting -> cloudConnectionRepository
                        .findByIdAndOwnerUserId(setting.getCloudConnectionId(), ownerUserId))
                .map(connection -> connection.getStatus() == CloudConnectionStatus.CONNECTED)
                .orElse(false);
    }

    private void assertProjectOwner(Long ownerUserId, Long projectId) {
        projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, ownerUserId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId, ownerUserId));
    }

    // Re-reads settings/pending-change from the repositories rather than being handed the
    // just-mutated objects — this makes GET and every PUT branch (no-op, immediate, pending)
    // converge on one code path that always reflects the true post-mutation DB state, instead
    // of three call sites each hand-assembling what they *think* the result should look like.
    private ProjectInfrastructureConfigurationResult toResult(Long projectId, boolean configurable) {
        ProjectInfrastructureConfigurationResult.Settings settings = settingRepository.findByProjectId(projectId)
                .map(this::toSettingsView)
                .orElse(null);
        ProjectInfrastructureConfigurationResult.PendingChange pendingChange = changeRepository
                .findByProjectIdAndStatusOrderByIdAsc(projectId, InfrastructureChangeStatus.PENDING_APPROVAL)
                .stream()
                .findFirst()
                .map(this::toPendingChangeView)
                .orElse(null);
        return new ProjectInfrastructureConfigurationResult(projectId, configurable, settings, pendingChange);
    }

    private ProjectInfrastructureConfigurationResult.Settings toSettingsView(ProjectInfrastructureSetting setting) {
        InfrastructureConfiguration configuration = setting.getConfiguration();
        return new ProjectInfrastructureConfigurationResult.Settings(
                configuration.deploymentArchitecture().name(),
                configuration.computeTier().name(),
                configuration.storageType().name(),
                configuration.networkAccess().name(),
                setting.getUpdatedAt()
        );
    }

    private ProjectInfrastructureConfigurationResult.PendingChange toPendingChangeView(
            ProjectInfrastructureSettingChange change
    ) {
        InfrastructureConfiguration configuration = change.getConfiguration();
        return new ProjectInfrastructureConfigurationResult.PendingChange(
                change.getId(),
                change.getApprovalId(),
                change.getAction().name(),
                configuration.deploymentArchitecture().name(),
                configuration.computeTier().name(),
                configuration.storageType().name(),
                configuration.networkAccess().name(),
                change.getCreatedAt()
        );
    }

    private ProjectInfrastructureChangeResult toChangeResult(ProjectInfrastructureSettingChange change) {
        InfrastructureConfiguration configuration = change.getConfiguration();
        return new ProjectInfrastructureChangeResult(
                change.getId(),
                change.getAction().name(),
                change.getStatus().name(),
                configuration.deploymentArchitecture().name(),
                configuration.computeTier().name(),
                configuration.storageType().name(),
                configuration.networkAccess().name(),
                change.getApprovalId(),
                change.getActorUserId(),
                change.getCreatedAt(),
                change.getDecidedAt()
        );
    }

    // Design §3.2's exact summary format: "인프라 설정 저장 요청: ..." for a first-ever save,
    // "인프라 설정 변경 요청: ..." for a change to an already-applied configuration — this is the
    // only human-readable trace of the request an approver sees before deciding.
    private String summaryFor(InfrastructureChangeAction action, InfrastructureConfiguration configuration) {
        String verb = action == InfrastructureChangeAction.CREATED ? "저장" : "변경";
        return "인프라 설정 " + verb + " 요청: " + configuration.summaryText();
    }

    private static int clampLimit(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_HISTORY_LIMIT;
        }
        return Math.min(requested, MAX_HISTORY_LIMIT);
    }
}
