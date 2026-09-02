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
        @Schema(description = "RUNNING 일 때만 접속 URL, 아니면 null") String url,
        String instanceId,
        @Schema(description = "실패 분류. 사용자 거부는 null(=거부됨).") String errorCode,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ServerResponse from(ProvisionedServer s) {
        String url = s.getStatus() == ServerStatus.RUNNING && s.getPublicHost() != null
                ? "http://" + s.getPublicHost() + ":" + s.getPort() : null;
        return new ServerResponse(
                s.getId(), s.getProjectId(), s.getStatus().name(), s.getInstanceType(),
                s.getPublicHost(), s.getPort(), url, s.getInstanceId(),
                s.getFailureCode() == null ? null : s.getFailureCode().name(),
                s.getErrorMessage(), s.getCreatedAt(), s.getUpdatedAt());
    }
}
