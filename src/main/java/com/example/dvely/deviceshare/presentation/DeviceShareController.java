package com.example.dvely.deviceshare.presentation;

import com.example.dvely.deviceshare.application.service.CreatedDeviceShare;
import com.example.dvely.deviceshare.application.service.DeviceShareService;
import com.example.dvely.deviceshare.application.session.ShareSessionRegistry;
import com.example.dvely.deviceshare.domain.model.DeviceShareLink;
import com.example.dvely.deviceshare.infrastructure.config.DeviceShareProperties;
import com.example.dvely.deviceshare.presentation.dto.CreateDeviceShareRequest;
import com.example.dvely.deviceshare.presentation.dto.CreatedDeviceShareResponse;
import com.example.dvely.deviceshare.presentation.dto.DeviceShareResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Tag(name = "DeviceShare", description = """
        내 기기 프리뷰 공유(#652). 내 기기의 앱 화면(1단계: dev 브라우저)을 링크로 공유하고, 조작권을 받은 한 사람이
        제어한다. 링크마다 <토큰>.share.qeploy.com 오리진을 쓴다. CLI `qeploy share web <url>` 이 이 API 를 쓴다.
        """)
@RestController
@RequiredArgsConstructor
public class DeviceShareController {

    private final DeviceShareService service;
    private final ShareSessionRegistry sessions;
    private final DeviceShareProperties properties;

    @Operation(summary = "공유 만들기", description = """
            보기 링크(viewUrl, 남에게 줄 것), 내 제어 링크(controlUrl, 주인 전용), 기기 터널 주소(tunnelUrl)를
            돌려준다. 평문 토큰·비밀은 이 응답에서만 볼 수 있다. 사용자당 살아 있는 링크 5개(넘으면 429
            DEVICE_SHARE_LIMIT_EXCEEDED). 개인 액세스 토큰(WRITE)으로도 부를 수 있다.
            """)
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/api/v1/device-shares")
    public CreatedDeviceShareResponse create(@AuthenticationPrincipal Long userId,
                                             @Valid @RequestBody CreateDeviceShareRequest request) {
        CreatedDeviceShare created = service.create(userId, request.kind(), request.targetLabel(), request.ttlMinutes());
        DeviceShareLink link = created.link();
        String viewUrl = properties.viewUrl(created.viewToken());
        return new CreatedDeviceShareResponse(link.getId(), link.getKind(), link.getTargetLabel(), viewUrl,
                viewUrl + "#c=" + created.controlSecret(), tunnelUrl(link.getId()), link.getExpiresAt());
    }

    @Operation(summary = "내 공유 목록", description = "최근 50개. 끝났거나 만료된 것도 상태와 함께 나온다.")
    @GetMapping("/api/v1/device-shares")
    public List<DeviceShareResponse> list(@AuthenticationPrincipal Long userId) {
        LocalDateTime now = service.now();
        return service.list(userId).stream()
                .map(link -> new DeviceShareResponse(link.getId(), link.getKind(), link.getTargetLabel(),
                        link.getTokenPrefix(), link.status(now), sessions.deviceConnected(link.getId()),
                        sessions.viewerCount(link.getId()), link.getExpiresAt(), link.getRevokedAt(),
                        link.getLastViewedAt(), link.getCreatedAt()))
                .toList();
    }

    @Operation(summary = "공유 끝내기", description = "링크를 바로 회수한다. 연결된 기기와 보는 사람이 모두 끊긴다.")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/api/v1/device-shares/{shareId}")
    public void revoke(@AuthenticationPrincipal Long userId, @PathVariable Long shareId) {
        service.revoke(userId, shareId);
    }

    private String tunnelUrl(Long shareId) {
        String base = properties.apiPublicUrl() == null || properties.apiPublicUrl().isBlank()
                ? ServletUriComponentsBuilder.fromCurrentContextPath().toUriString()
                : properties.apiPublicUrl().replaceAll("/+$", "");
        String ws = base.startsWith("https://") ? "wss://" + base.substring(8)
                : base.startsWith("http://") ? "ws://" + base.substring(7) : base;
        return ws + "/api/v1/device-shares/" + shareId + "/tunnel?v=1";
    }
}
