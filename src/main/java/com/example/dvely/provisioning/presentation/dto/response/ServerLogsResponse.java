package com.example.dvely.provisioning.presentation.dto.response;

import com.example.dvely.provisioning.application.query.ServerLogQueryService.ServerLogs;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "배포된 EC2 서버의 최근 로그(SSM Run Command 로 인스턴스에서 tail·docker logs). "
        + "살아있는 인스턴스에서만 조회되며, 종료된 서버는 로그가 남지 않는다.")
public record ServerLogsResponse(
        Long serverId,
        @Schema(description = "로그 소스: APP(앱) · BOOT(부트스트랩·왜 안 떴나) · CADDY(HTTPS)") String source,
        @Schema(description = "최근 로그 텍스트(최대 ~200줄). SSM 인라인 출력 한계로 매우 길면 잘릴 수 있다.") String content
) {
    public static ServerLogsResponse from(ServerLogs logs) {
        return new ServerLogsResponse(logs.serverId(), logs.source(), logs.content());
    }
}
