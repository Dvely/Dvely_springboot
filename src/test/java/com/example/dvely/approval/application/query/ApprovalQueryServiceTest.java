package com.example.dvely.approval.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dvely.approval.application.result.ApprovalResult;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.project.domain.value.ProjectStatus;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ApprovalQueryServiceTest {

    private final ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final ApprovalQueryService service = new ApprovalQueryService(approvalRepository, projectRepository);

    // REPOSITORY_BINDING 은 승인할 때 저장소 이름을 함께 받는다. FE 가 그 기본값을 summary
    // ("[저장소 연결] my-project")에서 접두사를 잘라 쓰지 않도록 별도 필드로 내려준다.

    @Test
    void pendingRepositoryBindingCarriesTheInputSpecWithTheCandidateName() {
        stubProject("My Project");

        ApprovalResult result = service.toResult(
                approval(ApprovalType.REPOSITORY_BINDING, ApprovalStatus.PENDING));

        assertThat(result.input()).isNotNull();
        assertThat(result.input().field()).isEqualTo("repositoryName");
        assertThat(result.input().defaultValue()).isEqualTo("my-project");
        assertThat(result.input().required()).isFalse();
        assertThat(result.input().pattern()).isEqualTo("^[a-z0-9-]+$");
    }

    @Test
    void defaultValueMatchesWhatTheGateShowedTheUser() {
        // 게이트와 같은 RepositoryNamePolicy 로 계산하므로 한글만 있는 이름의 폴백도 같아야 한다.
        stubProject("동미대");

        ApprovalResult result = service.toResult(
                approval(ApprovalType.REPOSITORY_BINDING, ApprovalStatus.PENDING));

        assertThat(result.input().defaultValue()).isEqualTo("qeploy-project-7");
    }

    @Test
    void otherApprovalTypesCarryNoInput() {
        // 이 타입들은 단순 승인/거절이다. FE 는 input 이 null 이면 버튼만 그리면 된다.
        for (ApprovalType type : new ApprovalType[]{
                ApprovalType.CHANGE, ApprovalType.DEPLOYMENT,
                ApprovalType.DOMAIN_BINDING, ApprovalType.INFRA_OPERATION, ApprovalType.RESULT}) {
            assertThat(service.toResult(approval(type, ApprovalStatus.PENDING)).input())
                    .as("type=%s", type)
                    .isNull();
        }
        // 프로젝트를 읽을 이유가 없다 — 목록 조회가 승인 건마다 조회를 더 하지 않는다.
        verifyNoInteractions(projectRepository);
    }

    @Test
    void decidedRepositoryBindingCarriesNoInput() {
        // 이미 결정된 승인에 입력창을 그릴 이유가 없다.
        assertThat(service.toResult(approval(ApprovalType.REPOSITORY_BINDING, ApprovalStatus.APPROVED)).input())
                .isNull();
        verifyNoInteractions(projectRepository);
    }

    @Test
    void inputIsNullWhenTheProjectIsGone() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 7L)).thenReturn(Optional.empty());

        assertThat(service.toResult(approval(ApprovalType.REPOSITORY_BINDING, ApprovalStatus.PENDING)).input())
                .isNull();
    }

    private void stubProject(String name) {
        LocalDateTime now = LocalDateTime.now();
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 7L)).thenReturn(Optional.of(new Project(
                11L, 7L, name, ProjectStatus.ACTIVE, "vue", null, "fast", DeployStatus.DRAFT,
                null, null, null, null, RepositoryVisibility.PRIVATE,
                RepositoryBindingStatus.NOT_BOUND, RepositoryHealthStatus.UNKNOWN_ERROR, false, now, now
        )));
    }

    private Approval approval(ApprovalType type, ApprovalStatus status) {
        return new Approval(9L, 7L, 11L, 21L, "task-1", type, status, "[저장소 연결] my-project",
                LocalDateTime.now(), null);
    }
}
