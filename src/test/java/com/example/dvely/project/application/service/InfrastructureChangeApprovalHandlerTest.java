package com.example.dvely.project.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.project.domain.model.ProjectInfrastructureSetting;
import com.example.dvely.project.domain.model.ProjectInfrastructureSettingChange;
import com.example.dvely.project.domain.repository.ProjectInfrastructureSettingChangeRepository;
import com.example.dvely.project.domain.repository.ProjectInfrastructureSettingRepository;
import com.example.dvely.project.domain.value.ComputeTier;
import com.example.dvely.project.domain.value.DeploymentArchitecture;
import com.example.dvely.project.domain.value.InfrastructureChangeAction;
import com.example.dvely.project.domain.value.InfrastructureConfiguration;
import com.example.dvely.project.domain.value.NetworkAccess;
import com.example.dvely.project.domain.value.StorageType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InfrastructureChangeApprovalHandlerTest {

    @Mock
    private ProjectInfrastructureSettingRepository settingRepository;

    @Mock
    private ProjectInfrastructureSettingChangeRepository changeRepository;

    private InfrastructureChangeApprovalHandler handler;

    private final InfrastructureConfiguration configuration = new InfrastructureConfiguration(
            DeploymentArchitecture.CONTAINER, ComputeTier.SMALL, StorageType.OBJECT_STORAGE, NetworkAccess.PUBLIC
    );

    @BeforeEach
    void setUp() {
        handler = new InfrastructureChangeApprovalHandler(settingRepository, changeRepository);
    }

    @Test
    void supportsOnlyInfraOperation() {
        assertThat(handler.supports(ApprovalType.INFRA_OPERATION)).isTrue();
        assertThat(handler.supports(ApprovalType.CHANGE)).isFalse();
        assertThat(handler.supports(ApprovalType.DEPLOYMENT)).isFalse();
        assertThat(handler.supports(ApprovalType.DOMAIN_BINDING)).isFalse();
    }

    @Test
    void onApproved_createsNewSettingWhenNoneExistsYetAndMarksChangeApplied() {
        Approval approval = approvedApproval(34L);
        ProjectInfrastructureSettingChange pending = ProjectInfrastructureSettingChange.pendingApproval(
                11L, InfrastructureChangeAction.CREATED, configuration, 34L, 7L
        );
        when(changeRepository.findByApprovalId(34L)).thenReturn(Optional.of(pending));
        when(settingRepository.findByProjectId(11L)).thenReturn(Optional.empty());
        when(settingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(changeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        handler.onApproved(approval);

        ArgumentCaptor<ProjectInfrastructureSetting> settingCaptor =
                ArgumentCaptor.forClass(ProjectInfrastructureSetting.class);
        verify(settingRepository).save(settingCaptor.capture());
        assertThat(settingCaptor.getValue().getProjectId()).isEqualTo(11L);
        assertThat(settingCaptor.getValue().getConfiguration()).isEqualTo(configuration);
        assertThat(pending.getStatus().name()).isEqualTo("APPLIED");
        verify(changeRepository).save(pending);
    }

    @Test
    void onApproved_overwritesExistingSettingInPlace() {
        Approval approval = approvedApproval(34L);
        ProjectInfrastructureSettingChange pending = ProjectInfrastructureSettingChange.pendingApproval(
                11L, InfrastructureChangeAction.UPDATED, configuration, 34L, 7L
        );
        ProjectInfrastructureSetting existing = new ProjectInfrastructureSetting(
                11L,
                new InfrastructureConfiguration(
                        DeploymentArchitecture.SERVER, ComputeTier.MICRO, StorageType.NONE, NetworkAccess.PRIVATE
                )
        );
        when(changeRepository.findByApprovalId(34L)).thenReturn(Optional.of(pending));
        when(settingRepository.findByProjectId(11L)).thenReturn(Optional.of(existing));
        when(settingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(changeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        handler.onApproved(approval);

        ArgumentCaptor<ProjectInfrastructureSetting> settingCaptor =
                ArgumentCaptor.forClass(ProjectInfrastructureSetting.class);
        verify(settingRepository).save(settingCaptor.capture());
        // Same instance as the pre-existing row (mutated via apply()), not a freshly built one —
        // proves the "upsert onto the existing row" path, not "always create new".
        assertThat(settingCaptor.getValue()).isSameAs(existing);
        assertThat(settingCaptor.getValue().getConfiguration()).isEqualTo(configuration);
    }

    @Test
    void onRejected_marksChangeRejectedWithoutTouchingSetting() {
        Approval approval = rejectedApproval(34L);
        ProjectInfrastructureSettingChange pending = ProjectInfrastructureSettingChange.pendingApproval(
                11L, InfrastructureChangeAction.CREATED, configuration, 34L, 7L
        );
        when(changeRepository.findByApprovalId(34L)).thenReturn(Optional.of(pending));
        when(changeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        handler.onRejected(approval);

        assertThat(pending.getStatus().name()).isEqualTo("REJECTED");
        verify(changeRepository).save(pending);
        verify(settingRepository, never()).findByProjectId(any());
        verify(settingRepository, never()).save(any());
    }

    @Test
    void onApproved_noPendingChangeForApproval_throwsIllegalState() {
        Approval approval = approvedApproval(999L);
        when(changeRepository.findByApprovalId(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.onApproved(approval))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("승인에 연결된 대기 중 인프라 설정 변경을 찾을 수 없습니다")
                .hasMessageContaining("approvalId=999");
    }

    @Test
    void onApproved_changeAlreadyDecided_throwsIllegalState() {
        Approval approval = approvedApproval(34L);
        ProjectInfrastructureSettingChange alreadyApplied =
                ProjectInfrastructureSettingChange.applied(11L, InfrastructureChangeAction.CREATED, configuration, 7L);
        // findByApprovalId only returns rows still awaiting a decision in production (design
        // §1.5 lists it without a status filter, but the handler defensively re-checks status
        // itself) — simulate a stale/racing lookup returning an already-decided row to prove
        // that defense actually fires.
        when(changeRepository.findByApprovalId(34L)).thenReturn(Optional.of(alreadyApplied));

        assertThatThrownBy(() -> handler.onApproved(approval))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("승인에 연결된 대기 중 인프라 설정 변경을 찾을 수 없습니다");
    }

    private Approval approvedApproval(Long approvalId) {
        return new Approval(
                approvalId, 7L, 11L, null, null, ApprovalType.INFRA_OPERATION,
                ApprovalStatus.APPROVED, "인프라 설정 변경 요청", LocalDateTime.now(), LocalDateTime.now()
        );
    }

    private Approval rejectedApproval(Long approvalId) {
        return new Approval(
                approvalId, 7L, 11L, null, null, ApprovalType.INFRA_OPERATION,
                ApprovalStatus.REJECTED, "인프라 설정 변경 요청", LocalDateTime.now(), LocalDateTime.now()
        );
    }
}
