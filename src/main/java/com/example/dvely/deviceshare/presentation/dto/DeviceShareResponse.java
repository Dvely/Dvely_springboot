package com.example.dvely.deviceshare.presentation.dto;

import com.example.dvely.deviceshare.domain.value.DeviceShareKind;
import com.example.dvely.deviceshare.domain.value.DeviceShareStatus;
import java.time.LocalDateTime;

/** 목록 한 줄. 토큰은 앞 6자만 보인다. */
public record DeviceShareResponse(
        Long shareId,
        DeviceShareKind kind,
        String targetLabel,
        String tokenPrefix,
        DeviceShareStatus status,
        boolean deviceConnected,
        int viewers,
        LocalDateTime expiresAt,
        LocalDateTime revokedAt,
        LocalDateTime lastViewedAt,
        LocalDateTime createdAt
) {
}
