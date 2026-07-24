package com.example.dvely.audit.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.audit.application.query.AuditLogQueryService;
import com.example.dvely.audit.application.result.AuditLogResult;
import com.example.dvely.audit.presentation.dto.response.AuditLogResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditLogControllerTest {

    @Mock
    private AuditLogQueryService auditLogQueryService;

    @InjectMocks
    private AuditLogController controller;

    @Test
    void getProjectAuditLogsDelegatesUsingAuthenticatedUserIdProjectIdCategoryAndLimit() {
        AuditLogResult result = new AuditLogResult(
                42L, "DEPLOYMENT", "DEPLOYMENT_REQUESTED", "SUCCEEDED", "USER", 7L,
                "DEPLOYMENT", "123", null, null, "target=LATEST", null, LocalDateTime.now()
        );
        when(auditLogQueryService.getProjectAuditLogs(1L, 11L, "DEPLOYMENT", 50)).thenReturn(List.of(result));

        List<AuditLogResponse> responses = controller.getProjectAuditLogs(1L, 11L, "DEPLOYMENT", 50);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).auditLogId()).isEqualTo(42L);
        assertThat(responses.get(0).action()).isEqualTo("DEPLOYMENT_REQUESTED");
        verify(auditLogQueryService).getProjectAuditLogs(1L, 11L, "DEPLOYMENT", 50);
    }

    @Test
    void getProjectAuditLogsPassesNullCategoryAndLimitThrough() {
        when(auditLogQueryService.getProjectAuditLogs(1L, 11L, null, null)).thenReturn(List.of());

        List<AuditLogResponse> responses = controller.getProjectAuditLogs(1L, 11L, null, null);

        assertThat(responses).isEmpty();
        verify(auditLogQueryService).getProjectAuditLogs(1L, 11L, null, null);
    }
}
