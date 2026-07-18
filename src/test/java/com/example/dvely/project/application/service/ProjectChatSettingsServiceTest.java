package com.example.dvely.project.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dvely.project.application.result.ProjectChatSettingsResult;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.model.ProjectApprovalPolicy;
import com.example.dvely.project.domain.repository.ProjectApprovalPolicyRepository;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProjectChatSettingsServiceTest {

    @Test
    void defaultsEveryApprovalPolicyToRequired() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ProjectApprovalPolicyRepository policyRepository = mock(ProjectApprovalPolicyRepository.class);
        ProjectChatSettingsService service = new ProjectChatSettingsService(
                projectRepository,
                policyRepository
        );
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(mock(Project.class)));
        when(policyRepository.findByProjectId(11L)).thenReturn(Optional.empty());

        ProjectChatSettingsResult result = service.get(1L, 11L);

        assertThat(result.changeApprovalRequired()).isTrue();
        assertThat(result.deploymentApprovalRequired()).isTrue();
        assertThat(result.domainApprovalRequired()).isTrue();
        assertThat(result.infraApprovalRequired()).isTrue();
        assertThat(result.resultApprovalRequired()).isTrue();
    }

    @Test
    void updatesProjectSpecificApprovalPolicy() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ProjectApprovalPolicyRepository policyRepository = mock(ProjectApprovalPolicyRepository.class);
        ProjectChatSettingsService service = new ProjectChatSettingsService(
                projectRepository,
                policyRepository
        );
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(mock(Project.class)));
        when(policyRepository.findByProjectId(11L))
                .thenReturn(Optional.of(new ProjectApprovalPolicy(11L)));
        when(policyRepository.save(any(ProjectApprovalPolicy.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProjectChatSettingsResult result = service.update(
                1L,
                11L,
                false,
                true,
                false,
                true,
                false
        );

        assertThat(result.changeApprovalRequired()).isFalse();
        assertThat(result.deploymentApprovalRequired()).isTrue();
        assertThat(result.domainApprovalRequired()).isFalse();
        assertThat(result.infraApprovalRequired()).isTrue();
        assertThat(result.resultApprovalRequired()).isFalse();
    }

    // ── Track Z (#56) D4/§5.5: resultApprovalRequired is the one nullable field in this PATCH ──

    @Test
    void nullResultApprovalRequiredLeavesTheCurrentValueUnchanged() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ProjectApprovalPolicyRepository policyRepository = mock(ProjectApprovalPolicyRepository.class);
        ProjectChatSettingsService service = new ProjectChatSettingsService(
                projectRepository,
                policyRepository
        );
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(mock(Project.class)));
        // Existing policy already has resultApprovalRequired=false — a legacy/partial FE request
        // that omits the field (null) must not silently flip it back to true.
        when(policyRepository.findByProjectId(11L))
                .thenReturn(Optional.of(new ProjectApprovalPolicy(11L, true, true, true, true, false)));
        when(policyRepository.save(any(ProjectApprovalPolicy.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProjectChatSettingsResult result = service.update(1L, 11L, true, true, true, true, null);

        assertThat(result.resultApprovalRequired()).isFalse();
    }

    @Test
    void nonNullResultApprovalRequiredOverridesTheCurrentValue() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ProjectApprovalPolicyRepository policyRepository = mock(ProjectApprovalPolicyRepository.class);
        ProjectChatSettingsService service = new ProjectChatSettingsService(
                projectRepository,
                policyRepository
        );
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(mock(Project.class)));
        when(policyRepository.findByProjectId(11L))
                .thenReturn(Optional.of(new ProjectApprovalPolicy(11L)));
        when(policyRepository.save(any(ProjectApprovalPolicy.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProjectChatSettingsResult result = service.update(1L, 11L, true, true, true, true, false);

        assertThat(result.resultApprovalRequired()).isFalse();
    }
}
