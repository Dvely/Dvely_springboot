package com.example.dvely.provisioning.presentation.dto.response;

import com.example.dvely.provisioning.domain.model.ProvisionedServer;
import com.example.dvely.provisioning.domain.value.ServerStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "EC2 서버 상태. 비밀은 싣지 않는다. status 전이(QUEUED·BUILDING·PROVISIONING)면 폴링, "
        + "종료(RUNNING·FAILED·TERMINATED)면 정지.")
public record ServerResponse(
        Long serverId,
        Long projectId,
        String status,
        @Schema(description = "웹 전용(독립 프론트 EC2) 서버면 true, 백엔드 서버면 false. 한 프로젝트에 프론트·백엔드 "
                + "서버가 함께 뜰 수 있으므로 이 값으로 구분한다.") boolean webOnly,
        String instanceType,
        String host,
        int port,
        @Schema(description = "RUNNING 일 때만 원시 EIP 접속 URL, 아니면 null") String url,
        @Schema(description = "이 서버(백엔드 또는 독립 프론트)에 연결된 도메인 URL(있고 RUNNING 일 때만). "
                + "Caddy 가 443 에서 HTTPS 종단(https://{host}). 도메인 없으면 null — 그땐 url(EIP:8080) 사용.")
        String domainUrl,
        String instanceId,
        @Schema(description = "실패 분류. 사용자 거부는 null(=거부됨).") String errorCode,
        String errorMessage,
        @Schema(description = "RUNNING 이후 앱 건강(주기 TCP 헬스체크). true=응답 · false=포트 무응답(앱이 죽었을 "
                + "수 있음, 인스턴스는 살아있음) · null=아직 미확인. status=RUNNING 이라도 이 값이 false 면 앱 문제다.")
        Boolean healthy,
        LocalDateTime lastHealthCheckAt,
        @Schema(description = "부트 타임아웃으로 실패한 서버가 보존한 부트 로그를 갖고 있는지. true 면 인스턴스가 "
                + "종료됐어도 GET /servers/{id}/logs?source=BOOT 로 '왜 안 떴나'를 볼 수 있다.")
        boolean hasBootDiagnostics,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ServerResponse from(ProvisionedServer s) {
        return from(s, null);
    }

    public static ServerResponse from(ProvisionedServer s, String domainHostname) {
        boolean running = s.getStatus() == ServerStatus.RUNNING;
        String url = running && s.getPublicHost() != null
                ? "http://" + s.getPublicHost() + ":" + s.getPort() : null;
        // 도메인이 붙어 있으면 원시 EIP 대신 도메인 URL 을 준다(FE 가 서버 카드에 우선 표시). Caddy 가
        // 인스턴스에서 443 HTTPS 로 종단하므로 포트 없는 https URL 이다.
        String domainUrl = running && domainHostname != null && !domainHostname.isBlank()
                ? "https://" + domainHostname : null;
        return new ServerResponse(
                s.getId(), s.getProjectId(), s.getStatus().name(), s.isWebOnly(), s.getInstanceType(),
                s.getPublicHost(), s.getPort(), url, domainUrl, s.getInstanceId(),
                s.getFailureCode() == null ? null : s.getFailureCode().name(),
                s.getErrorMessage(), s.getHealthy(), s.getLastHealthCheckAt(),
                s.hasBootDiagnostics(), s.getCreatedAt(), s.getUpdatedAt());
    }
}
