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
                true
        );

        assertThat(result.changeApprovalRequired()).isFalse();
        assertThat(result.deploymentApprovalRequired()).isTrue();
        assertThat(result.domainApprovalRequired()).isFalse();
        assertThat(result.infraApprovalRequired()).isTrue();
    }
}
