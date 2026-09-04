package com.example.dvely.provisioning.presentation;

import com.example.dvely.provisioning.application.command.ServerProvisioningCommandService;
import com.example.dvely.provisioning.application.query.ServerProvisioningQueryService;
import com.example.dvely.provisioning.presentation.dto.request.CreateServerRequest;
import com.example.dvely.provisioning.presentation.dto.response.ServerProvisionSubmitResponse;
import com.example.dvely.provisioning.presentation.dto.response.ServerResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import com.example.dvely.provisioning.domain.value.ServerDeployMode;
import java.util.Locale;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "provisioning", description = "백엔드 앱의 EC2 서버 프로비저닝")
@RestController
@RequiredArgsConstructor
public class ServerProvisioningController {

    private final ServerProvisioningCommandService commandService;
    private final ServerProvisioningQueryService queryService;

    @Operation(summary = "EC2 백엔드 서버 생성 요청",
            description = "과금 자원이라 항상 승인을 거칩니다. requiresApproval=true 와 approvalIds 가 내려오면 "
                    + "승인 화면으로 연결하세요. CONNECTED 클라우드 연결이 없으면 404/409 입니다.")
    @PostMapping("/api/v1/projects/{projectId}/servers")
    public ServerProvisionSubmitResponse create(
            @Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long projectId,
            @RequestBody(required = false) CreateServerRequest request
    ) {
        String instanceType = request == null ? null : request.instanceType();
        ServerDeployMode deployMode = parseDeployMode(request == null ? null : request.deployMode());
        return ServerProvisionSubmitResponse.from(
                commandService.submit(ownerUserId, projectId, instanceType, deployMode));
    }

    /** 요청의 deployMode 문자열 → enum. 생략/공백이면 NATIVE, 알 수 없는 값이면 400(IllegalArgument). */
    private ServerDeployMode parseDeployMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return ServerDeployMode.NATIVE;
        }
        return ServerDeployMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }

    @Operation(summary = "프로젝트 EC2 서버 목록 조회",
            description = "순수 DB 조회라 상시 폴링해도 안전합니다. status 가 전이(QUEUED·BUILDING·PROVISIONING)면 "
                    + "폴링, 종료(RUNNING·FAILED·TERMINATED)면 정지하세요.")
    @GetMapping("/api/v1/projects/{projectId}/servers")
    public List<ServerResponse> list(
            @Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long projectId
    ) {
        var result = queryService.list(ownerUserId, projectId);
        return result.servers().stream()
                .map(s -> ServerResponse.from(s, result.domainHostname())).toList();
    }

    @Operation(summary = "EC2 백엔드 서버 종료",
            description = "인스턴스를 종료하고 부수 자원(SSM·S3)을 정리합니다. 종료하는 순간부터 과금이 멈춥니다. 멱등입니다.")
    @PostMapping("/api/v1/servers/{serverId}/terminate")
    public void terminate(
            @Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long serverId
    ) {
        commandService.terminate(ownerUserId, serverId);
    }
}
