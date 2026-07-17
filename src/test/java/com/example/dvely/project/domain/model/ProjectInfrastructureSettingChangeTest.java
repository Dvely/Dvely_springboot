package com.example.dvely.project.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dvely.project.domain.value.ComputeTier;
import com.example.dvely.project.domain.value.DeploymentArchitecture;
import com.example.dvely.project.domain.value.InfrastructureChangeAction;
import com.example.dvely.project.domain.value.InfrastructureChangeStatus;
import com.example.dvely.project.domain.value.InfrastructureConfiguration;
import com.example.dvely.project.domain.value.NetworkAccess;
import com.example.dvely.project.domain.value.StorageType;
import org.junit.jupiter.api.Test;

class ProjectInfrastructureSettingChangeTest {

    private final InfrastructureConfiguration configuration = new InfrastructureConfiguration(
            DeploymentArchitecture.CONTAINER, ComputeTier.SMALL, StorageType.OBJECT_STORAGE, NetworkAccess.PUBLIC
    );

    @Test
    void appliedFactoryIsDecidedImmediately() {
        ProjectInfrastructureSettingChange change =
                ProjectInfrastructureSettingChange.applied(11L, InfrastructureChangeAction.CREATED, configuration, 7L);

        assertThat(change.getStatus()).isEqualTo(InfrastructureChangeStatus.APPLIED);
        assertThat(change.getApprovalId()).isNull();
        assertThat(change.getDecidedAt()).isNotNull();
        assertThat(change.getConfiguration()).isEqualTo(configuration);
        assertThat(change.getActorUserId()).isEqualTo(7L);
    }

    @Test
    void pendingApprovalFactoryIsUndecidedAndLinksApprovalId() {
        ProjectInfrastructureSettingChange change = ProjectInfrastructureSettingChange.pendingApproval(
                11L, InfrastructureChangeAction.UPDATED, configuration, 34L, 7L
        );

        assertThat(change.getStatus()).isEqualTo(InfrastructureChangeStatus.PENDING_APPROVAL);
        assertThat(change.getApprovalId()).isEqualTo(34L);
        assertThat(change.getDecidedAt()).isNull();
    }

    @Test
    void markAppliedTransitionsPendingToApplied() {
        ProjectInfrastructureSettingChange change = ProjectInfrastructureSettingChange.pendingApproval(
                11L, InfrastructureChangeAction.CREATED, configuration, 34L, 7L
        );

        change.markApplied();

        assertThat(change.getStatus()).isEqualTo(InfrastructureChangeStatus.APPLIED);
        assertThat(change.getDecidedAt()).isNotNull();
    }

    @Test
    void markRejectedTransitionsPendingToRejected() {
        ProjectInfrastructureSettingChange change = ProjectInfrastructureSettingChange.pendingApproval(
                11L, InfrastructureChangeAction.CREATED, configuration, 34L, 7L
        );

        change.markRejected();

        assertThat(change.getStatus()).isEqualTo(InfrastructureChangeStatus.REJECTED);
        assertThat(change.getDecidedAt()).isNotNull();
    }

    @Test
    void reDecidingAnAlreadyDecidedChangeThrows() {
        ProjectInfrastructureSettingChange change = ProjectInfrastructureSettingChange.pendingApproval(
                11L, InfrastructureChangeAction.CREATED, configuration, 34L, 7L
        );
        change.markApplied();

        assertThatThrownBy(change::markApplied)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 확정된 인프라 설정 변경입니다");
        assertThatThrownBy(change::markRejected)
                .isInstanceOf(IllegalStateException.class);
    }
}
