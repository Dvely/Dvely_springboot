package com.example.dvely.project.application.service;

import com.example.dvely.approval.application.port.out.StandaloneApprovalHandler;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.project.domain.model.ProjectInfrastructureSetting;
import com.example.dvely.project.domain.model.ProjectInfrastructureSettingChange;
import com.example.dvely.project.domain.repository.ProjectCloudConnectionSettingRepository;
import com.example.dvely.project.domain.repository.ProjectInfrastructureSettingChangeRepository;
import com.example.dvely.project.domain.repository.ProjectInfrastructureSettingRepository;
import com.example.dvely.project.domain.value.InfrastructureChangeStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Standalone approval follow-up for INFRA_OPERATION (design §4.2, BI-097). Runs inside
 * {@code ApprovalCommandService.approve/reject}'s transaction — no {@code @Transactional} here,
 * so setting-apply, history-decide, and the approval's own status change all commit or roll back
 * together as one unit.
 * <p>
 * Ownership is intentionally not re-checked here: by the time this handler runs,
 * {@code ApprovalCommandService.findOwned(ownerUserId, approvalId)} has already proven the
 * caller owns the approval, and the change row this handler touches is reached only through
 * that same approval's id — so re-deriving ownership from the change/project would be redundant
 * (same contract shape as the U3 environment value resolver).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InfrastructureChangeApprovalHandler implements StandaloneApprovalHandler {

    private final ProjectInfrastructureSettingRepository settingRepository;
    private final ProjectInfrastructureSettingChangeRepository changeRepository;
    private final ProjectCloudConnectionSettingRepository cloudConnectionSettingRepository;
    private final CloudConnectionRepository cloudConnectionRepository;

    @Override
    public boolean supports(ApprovalType type) {
        return type == ApprovalType.INFRA_OPERATION;
    }

    @Override
    public void onApproved(Approval approval) {
        ProjectInfrastructureSettingChange change = findPending(approval.getId());
        // Review F2 / design D7: the CONNECTED precondition was only true at PUT time — the
        // approval can sit pending for a while, during which the connection may have been
        // unselected or lost its CONNECTED status. Re-check it now rather than trusting the
        // state captured when the change was requested. Throwing here (before markApplied())
        // leaves the change row at PENDING_APPROVAL and — because this method runs inside
        // ApprovalCommandService.approve()'s transaction — rolls the approval's status back to
        // PENDING too, so the user can simply restore the connection and re-approve instead of
        // redoing the whole PUT.
        if (!isConnectedCloudSelected(approval.getOwnerUserId(), change.getProjectId())) {
            throw new IllegalStateException(
                    "클라우드 연결이 해제되어 적용할 수 없습니다. 연결을 다시 선택한 뒤 승인해주세요. changeId=" + change.getId());
        }
        ProjectInfrastructureSetting setting = settingRepository.findByProjectId(change.getProjectId())
                .orElseGet(() -> new ProjectInfrastructureSetting(change.getProjectId(), change.getConfiguration()));
        setting.apply(change.getConfiguration());
        settingRepository.save(setting);
        change.markApplied();
        changeRepository.save(change);
        log.info("Infrastructure configuration applied via approval. projectId={}, changeId={}, approvalId={}",
                change.getProjectId(), change.getId(), approval.getId());
    }

    @Override
    public void onRejected(Approval approval) {
        ProjectInfrastructureSettingChange change = findPending(approval.getId());
        change.markRejected();
        changeRepository.save(change);
        log.info("Infrastructure configuration change rejected. projectId={}, changeId={}, approvalId={}",
                change.getProjectId(), change.getId(), approval.getId());
    }

    private ProjectInfrastructureSettingChange findPending(Long approvalId) {
        return changeRepository.findByApprovalId(approvalId)
                .filter(change -> change.getStatus() == InfrastructureChangeStatus.PENDING_APPROVAL)
                .orElseThrow(() -> new IllegalStateException(
                        "승인에 연결된 대기 중 인프라 설정 변경을 찾을 수 없습니다. approvalId=" + approvalId));
    }

    // Deliberately self-contained (design §6 precedent) rather than delegating to
    // ProjectInfrastructureConfigurationService's private equivalent — keeps this handler's only
    // dependencies scoped to what it actually touches, instead of pulling in the whole
    // configuration service (and its own approval-repository dependency) just for a 4-line check.
    private boolean isConnectedCloudSelected(Long ownerUserId, Long projectId) {
        return cloudConnectionSettingRepository.findByProjectId(projectId)
                .flatMap(setting -> cloudConnectionRepository
                        .findByIdAndOwnerUserId(setting.getCloudConnectionId(), ownerUserId))
                .map(connection -> connection.getStatus() == CloudConnectionStatus.CONNECTED)
                .orElse(false);
    }
}
