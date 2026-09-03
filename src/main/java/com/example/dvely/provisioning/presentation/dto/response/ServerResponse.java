package com.example.dvely.provisioning.presentation.dto.response;

import com.example.dvely.provisioning.domain.model.ProvisionedServer;
import com.example.dvely.provisioning.domain.value.ServerStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "EC2 백엔드 서버 상태. 비밀은 싣지 않는다. status 전이(QUEUED·BUILDING·PROVISIONING)면 폴링, "
        + "종료(RUNNING·FAILED·TERMINATED)면 정지.")
public record ServerResponse(
        Long serverId,
        Long projectId,
        String status,
        String instanceType,
        String host,
        int port,
        @Schema(description = "RUNNING 일 때만 원시 EIP 접속 URL, 아니면 null") String url,
        @Schema(description = "백엔드에 연결된 도메인 URL(있고 RUNNING 일 때만). 현재 DNS-only http:8080; "
                + "HTTPS 는 추후. 도메인 없으면 null — 그땐 url(EIP) 사용.") String domainUrl,
        String instanceId,
        @Schema(description = "실패 분류. 사용자 거부는 null(=거부됨).") String errorCode,
        String errorMessage,
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
        // 도메인이 붙어 있으면 원시 EIP 대신 도메인 URL 도 함께 준다(FE 가 서버 카드에 우선 표시).
        // 현재 DNS-only 라 http:8080; HTTPS(깔끔한 URL)는 B 이후.
        String domainUrl = running && domainHostname != null && !domainHostname.isBlank()
                ? "http://" + domainHostname + ":" + s.getPort() : null;
        return new ServerResponse(
                s.getId(), s.getProjectId(), s.getStatus().name(), s.getInstanceType(),
                s.getPublicHost(), s.getPort(), url, domainUrl, s.getInstanceId(),
                s.getFailureCode() == null ? null : s.getFailureCode().name(),
                s.getErrorMessage(), s.getCreatedAt(), s.getUpdatedAt());
    }
}
