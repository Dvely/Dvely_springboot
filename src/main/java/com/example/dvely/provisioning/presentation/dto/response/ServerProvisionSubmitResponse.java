package com.example.dvely.provisioning.presentation.dto.response;

import com.example.dvely.provisioning.application.result.ServerProvisionSubmitResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "서버 생성 요청 응답. 과금이라 항상 승인 대기(requiresApproval=true) — approvalIds 로 승인 화면 연결.")
public record ServerProvisionSubmitResponse(
        boolean requiresApproval,
        Long serverId,
        List<Long> approvalIds
) {
    public static ServerProvisionSubmitResponse from(ServerProvisionSubmitResult r) {
        return new ServerProvisionSubmitResponse(r.requiresApproval(), r.serverId(), r.approvalIds());
    }
}
