package com.example.dvely.audit.application.query;

import com.example.dvely.audit.application.result.AuditLogResult;
import com.example.dvely.audit.domain.model.AuditLog;
import com.example.dvely.audit.domain.repository.AuditLogRepository;
import com.example.dvely.audit.domain.value.AuditCategory;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the audit log feature (design §6, ADR-A6). Mirrors the existing history-query
 * conventions exactly (design F15 — {@code EnvironmentVariableQueryService}/
 * {@code ProjectInfrastructureConfigurationService#getHistory}): owner-scoped 404, {@code limit}
 * clamped to a fixed range, no offset paging.
 */
@Service
@RequiredArgsConstructor
public class AuditLogQueryService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final AuditLogRepository auditLogRepository;
    private final ProjectRepository projectRepository;

    @Transactional(readOnly = true)
    public List<AuditLogResult> getProjectAuditLogs(Long ownerUserId, Long projectId, String categoryParam, Integer limitParam) {
        assertProjectOwner(ownerUserId, projectId);
        int limit = clampLimit(limitParam);
        List<AuditLog> auditLogs = categoryParam == null
                ? auditLogRepository.findByProjectIdOrderByIdDesc(projectId, limit)
                : auditLogRepository.findByProjectIdAndCategoryOrderByIdDesc(projectId, parseCategory(categoryParam), limit);
        return auditLogs.stream().map(this::toResult).toList();
    }

    private void assertProjectOwner(Long ownerUserId, Long projectId) {
        projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, ownerUserId)
                .orElseThrow(() -> new NotFoundException(
                        "프로젝트를 찾을 수 없습니다. projectId=" + projectId + ", ownerUserId=" + ownerUserId));
    }

    // Raw valueOf() failures are deliberately not surfaced to the client (design §6 — "raw
    // valueOf 메시지 노출 금지, U3 §3.6 선례"): wrapping keeps the 400 message limited to the
    // parameter name and the value the client sent, not an enum's fully-qualified class name.
    private AuditCategory parseCategory(String categoryParam) {
        try {
            return AuditCategory.valueOf(categoryParam.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("지원하지 않는 category입니다: " + categoryParam, exception);
        }
    }

    private static int clampLimit(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requested, MAX_LIMIT);
    }

    private AuditLogResult toResult(AuditLog auditLog) {
        return new AuditLogResult(
                auditLog.getId(),
                auditLog.getCategory().name(),
                auditLog.getAction().name(),
                auditLog.getOutcome().name(),
                auditLog.getActorType().name(),
                auditLog.getActorUserId(),
                auditLog.getResourceType(),
                auditLog.getResourceId(),
                auditLog.getTaskId(),
                auditLog.getApprovalId(),
                auditLog.getDetail(),
                auditLog.getErrorSummary(),
                auditLog.getCreatedAt()
        );
    }
}
