package com.example.dvely.audit.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.audit.application.result.AuditLogResult;
import com.example.dvely.audit.domain.model.AuditLog;
import com.example.dvely.audit.domain.repository.AuditLogRepository;
import com.example.dvely.audit.domain.value.AuditAction;
import com.example.dvely.audit.domain.value.AuditActorType;
import com.example.dvely.audit.domain.value.AuditCategory;
import com.example.dvely.audit.domain.value.AuditOutcome;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.project.domain.value.ProjectStatus;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditLogQueryServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private ProjectRepository projectRepository;

    private AuditLogQueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = new AuditLogQueryService(auditLogRepository, projectRepository);
    }

    @Test
    void othersProjectThrowsNotFound() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> queryService.getProjectAuditLogs(1L, 11L, null, null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void noCategoryDelegatesToProjectOnlyQueryIdDesc() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.of(project()));
        when(auditLogRepository.findByProjectIdOrderByIdDesc(eq(11L), anyInt()))
                .thenReturn(List.of(sampleLog()));

        List<AuditLogResult> results = queryService.getProjectAuditLogs(1L, 11L, null, null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).category()).isEqualTo("DEPLOYMENT");
        verify(auditLogRepository, never()).findByProjectIdAndCategoryOrderByIdDesc(any(), any(), anyInt());
    }

    @Test
    void categoryFilterDelegatesToCategoryScopedQuery() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.of(project()));
        when(auditLogRepository.findByProjectIdAndCategoryOrderByIdDesc(eq(11L), eq(AuditCategory.DEPLOYMENT), anyInt()))
                .thenReturn(List.of(sampleLog()));

        List<AuditLogResult> results = queryService.getProjectAuditLogs(1L, 11L, "deployment", null);

        assertThat(results).hasSize(1);
        verify(auditLogRepository).findByProjectIdAndCategoryOrderByIdDesc(11L, AuditCategory.DEPLOYMENT, 50);
    }

    @Test
    void unknownCategoryThrowsIllegalArgumentWithoutLeakingEnumValueOfMessage() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.of(project()));

        assertThatThrownBy(() -> queryService.getProjectAuditLogs(1L, 11L, "NOT_A_CATEGORY", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("지원하지 않는 category입니다")
                .hasMessageContaining("NOT_A_CATEGORY");
    }

    @Test
    void nullOrNonPositiveLimitDefaultsTo50() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.of(project()));
        when(auditLogRepository.findByProjectIdOrderByIdDesc(eq(11L), anyInt())).thenReturn(List.of());

        queryService.getProjectAuditLogs(1L, 11L, null, null);
        queryService.getProjectAuditLogs(1L, 11L, null, 0);
        queryService.getProjectAuditLogs(1L, 11L, null, -5);

        verify(auditLogRepository, org.mockito.Mockito.times(3)).findByProjectIdOrderByIdDesc(11L, 50);
    }

    @Test
    void limitAbove200IsClampedTo200() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.of(project()));
        when(auditLogRepository.findByProjectIdOrderByIdDesc(eq(11L), anyInt())).thenReturn(List.of());

        queryService.getProjectAuditLogs(1L, 11L, null, 500);

        verify(auditLogRepository).findByProjectIdOrderByIdDesc(11L, 200);
    }

    private AuditLog sampleLog() {
        return AuditLog.restore(
                1L,
                AuditAction.DEPLOYMENT_REQUESTED,
                AuditOutcome.SUCCEEDED,
                AuditActorType.USER,
                1L,
                11L,
                "DEPLOYMENT",
                "500",
                null,
                null,
                "target=LATEST",
                null,
                LocalDateTime.now()
        );
    }

    private Project project() {
        LocalDateTime now = LocalDateTime.now();
        return new Project(
                11L,
                1L,
                "my-project",
                ProjectStatus.ACTIVE,
                "blank",
                null,
                "fast",
                DeployStatus.LIVE,
                null,
                null,
                "octo/repo",
                "octo/repo",
                RepositoryVisibility.PUBLIC,
                RepositoryBindingStatus.BOUND,
                RepositoryHealthStatus.HEALTHY,
                false,
                now,
                now
        );
    }
}
