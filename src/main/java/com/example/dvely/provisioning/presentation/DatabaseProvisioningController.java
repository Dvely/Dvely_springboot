package com.example.dvely.provisioning.presentation;

import com.example.dvely.provisioning.application.command.DatabaseProvisioningCommandService;
import com.example.dvely.provisioning.application.query.DatabaseProvisioningQueryService;
import com.example.dvely.provisioning.application.result.ProvisionSubmitResult;
import com.example.dvely.provisioning.application.result.ProvisionedDatabaseResult;
import com.example.dvely.provisioning.presentation.dto.request.CreateDatabaseRequest;
import com.example.dvely.provisioning.presentation.dto.response.CreateDatabaseResponse;
import com.example.dvely.provisioning.presentation.dto.response.CreateDatabaseResponse.CreatedDatabase;
import com.example.dvely.provisioning.presentation.dto.response.ProvisionedDatabaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "provisioning", description = "백엔드 앱의 DB 프로비저닝")
@RestController
@RequiredArgsConstructor
public class DatabaseProvisioningController {

    private final DatabaseProvisioningCommandService commandService;
    private final DatabaseProvisioningQueryService queryService;

    @Operation(summary = "프로젝트 DB 목록 조회",
            description = "순수 DB 조회라 외부 API를 때리지 않습니다(상시 폴링 안전). 상태가 전이(PENDING·PROVISIONING)면 "
                    + "폴링, 종료(READY·FAILED·EXPIRED)면 정지하세요.")
    @GetMapping("/api/v1/projects/{projectId}/databases")
    public List<ProvisionedDatabaseResponse> list(
            @Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long projectId
    ) {
        return queryService.list(ownerUserId, projectId).stream().map(this::toResponse).toList();
    }

    @Operation(summary = "DB 프로비저닝 요청",
            description = "method 에 따라 형태가 갈리지 않고 항상 같은 응답입니다. LOCAL 은 즉시 만들어져 "
                    + "requiresApproval=false 로 database(password 포함)를 돌려주고, RDS/DOCKER 는 승인을 거쳐 "
                    + "requiresApproval=true 로 taskId/approvalIds 를 돌려줍니다. LOCAL 은 실행 중인 프리뷰가 있어야 합니다.")
    @PostMapping("/api/v1/projects/{projectId}/databases")
    public CreateDatabaseResponse create(
            @Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long projectId,
            @Valid @RequestBody CreateDatabaseRequest request
    ) {
        ProvisionSubmitResult result = commandService.provision(
                ownerUserId, projectId, request.method(), request.engine());
        return toResponse(result);
    }

    private ProvisionedDatabaseResponse toResponse(ProvisionedDatabaseResult r) {
        return new ProvisionedDatabaseResponse(
                r.databaseId(), r.projectId(), r.method(), r.engine(), r.origin(), r.status(),
                r.host(), r.port(), r.database(), r.username(),
                offset(r.expiresAt()), r.errorCode(), r.errorMessage(),
                offset(r.createdAt()), offset(r.updatedAt()));
    }

    private CreateDatabaseResponse toResponse(ProvisionSubmitResult r) {
        CreatedDatabase db = null;
        if (r.database() != null) {
            var d = r.database();
            db = new CreatedDatabase(d.databaseId(), d.method(), d.engine(), d.status(),
                    d.host(), d.port(), d.database(), d.username(), d.password(), offset(d.expiresAt()));
        }
        return new CreateDatabaseResponse(r.requiresApproval(), db, r.taskId(), r.approvalIds());
    }

    /**
     * LocalDateTime → OffsetDateTime. FE 가 이 값으로 남은시간을 계산하므로 오프셋이 반드시 있어야
     * 한다(오프셋 없으면 브라우저 로컬로 해석돼 9시간 어긋난 적이 있다). 시스템 타임존을 붙인다 —
     * 호스트가 KST 이므로 +09:00 이 나간다.
     */
    private OffsetDateTime offset(LocalDateTime t) {
        return t == null ? null : t.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    @Operation(summary = "DB 삭제",
            description = "프로비저닝된 DB 를 삭제합니다. RDS 는 저장된 클라우드 연결로 실제 인스턴스를 삭제하고, "
                    + "LOCAL 은 컨테이너를 정리한 뒤 상태를 EXPIRED 로 넘깁니다. 멱등입니다.")
    @DeleteMapping("/api/v1/databases/{databaseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long databaseId
    ) {
        commandService.deleteDatabase(ownerUserId, databaseId);
    }
}
