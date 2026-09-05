package com.example.dvely.provisioning.presentation;

import com.example.dvely.provisioning.application.command.ServerProvisioningCommandService;
import com.example.dvely.provisioning.application.query.ServerLogQueryService;
import com.example.dvely.provisioning.application.query.ServerProvisioningQueryService;
import com.example.dvely.provisioning.domain.value.ServerLogSource;
import com.example.dvely.provisioning.presentation.dto.request.CreateServerRequest;
import com.example.dvely.provisioning.presentation.dto.response.ServerLogsResponse;
import com.example.dvely.provisioning.presentation.dto.response.ServerProvisionSubmitResponse;
import com.example.dvely.provisioning.presentation.dto.response.ServerResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import com.example.dvely.provisioning.domain.value.DatabaseEngine;
import com.example.dvely.provisioning.domain.value.ServerDeployMode;
import com.example.dvely.provisioning.domain.value.WebFrontendSpec;
import java.util.Locale;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "provisioning", description = "백엔드 앱의 EC2 서버 프로비저닝")
@RestController
@RequiredArgsConstructor
public class ServerProvisioningController {

    private final ServerProvisioningCommandService commandService;
    private final ServerProvisioningQueryService queryService;
    private final ServerLogQueryService logQueryService;

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
        DatabaseEngine bundledDb = parseBundledDb(request == null ? null : request.bundledDbEngine());
        WebFrontendSpec web = request == null ? new WebFrontendSpec(null, null, null)
                : new WebFrontendSpec(request.frontendRepo(), request.frontendDir(), request.apiPathPrefix());
        boolean webOnly = request != null && Boolean.TRUE.equals(request.webOnly());
        return ServerProvisionSubmitResponse.from(
                commandService.submit(ownerUserId, projectId, instanceType, deployMode, bundledDb, web, webOnly));
    }

    /** 요청의 deployMode 문자열 → enum. 생략/공백이면 NATIVE, 알 수 없는 값이면 400(IllegalArgument). */
    private ServerDeployMode parseDeployMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return ServerDeployMode.NATIVE;
        }
        return ServerDeployMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }

    /** 요청의 bundledDbEngine 문자열 → enum. 생략/공백이면 null(번들 DB 없음), 알 수 없는 값이면 400. */
    private DatabaseEngine parseBundledDb(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return DatabaseEngine.valueOf(raw.trim().toUpperCase(Locale.ROOT));
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
        // 웹 전용(프론트) 서버엔 프론트 도메인을, 백엔드 서버엔 백엔드 도메인을 매핑한다 — 한 프로젝트에
        // 둘이 함께 뜰 수 있어 도메인을 섞으면 카드가 잘못된 https URL 을 보인다.
        return result.servers().stream()
                .map(s -> ServerResponse.from(s,
                        s.isWebOnly() ? result.frontendDomainHostname() : result.backendDomainHostname()))
                .toList();
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

    @Operation(summary = "EC2 서버 최근 로그 조회",
            description = "SSM Run Command 로 인스턴스에서 최근 로그를 뽑아옵니다(느린 외부 호출이라 몇 초 걸립니다). "
                    + "source=APP(앱 로그·기본) · BOOT(부트스트랩, '왜 안 떴나' 진단) · CADDY(HTTPS). 살아있는 "
                    + "인스턴스(RUNNING·PROVISIONING)만 조회되고, 종료된 서버는 로그가 남지 않습니다.")
    @GetMapping("/api/v1/servers/{serverId}/logs")
    public ServerLogsResponse logs(
            @Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long serverId,
            @RequestParam(name = "source", required = false, defaultValue = "APP") ServerLogSource source
    ) {
        return ServerLogsResponse.from(logQueryService.fetchLogs(ownerUserId, serverId, source));
    }
}
