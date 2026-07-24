package com.example.dvely.audit.presentation;

import com.example.dvely.audit.application.query.AuditLogQueryService;
import com.example.dvely.audit.application.result.AuditLogResult;
import com.example.dvely.audit.presentation.dto.response.AuditLogResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "AuditLog", description = "프로젝트 감사 로그 조회 API (design ad-audit-log-design.md §6)")
@RestController
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogQueryService auditLogQueryService;

    @Operation(
            summary = "프로젝트 감사 로그 조회",
            description = "category 쿼리 파라미터로 필터링(생략 시 전체). limit 기본 50, 최대 200(초과 시 200으로 보정). "
                    + "audit_log_id desc(최신순), offset 페이징 없음."
    )
    @GetMapping("/api/v1/projects/{projectId}/audit-logs")
    public List<AuditLogResponse> getProjectAuditLogs(
            @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long projectId,
            @Parameter(description = "필터링할 카테고리. 생략 시 전체 조회",
                    schema = @Schema(allowableValues = {"GITHUB", "DEPLOYMENT", "DOMAIN", "INFRA"}))
            @RequestParam(required = false) String category,
            @Parameter(description = "조회 개수. 기본 50, 최대 200(초과 시 200으로 보정)")
            @RequestParam(required = false) Integer limit
    ) {
        return auditLogQueryService.getProjectAuditLogs(ownerUserId, projectId, category, limit).stream()
                .map(this::toResponse)
                .toList();
    }

    private AuditLogResponse toResponse(AuditLogResult result) {
        return new AuditLogResponse(
                result.auditLogId(),
                result.category(),
                result.action(),
                result.outcome(),
                result.actorType(),
                result.actorUserId(),
                result.resourceType(),
                result.resourceId(),
                result.taskId(),
                result.approvalId(),
                result.detail(),
                result.errorSummary(),
                result.createdAt()
        );
    }
}
