package com.example.dvely.deviceshare.presentation.dto;

import com.example.dvely.deviceshare.domain.value.DeviceShareKind;
import java.time.LocalDateTime;

/**
 * 막 만든 공유. {@code viewUrl} 은 남에게 줄 보기 링크, {@code controlUrl} 은 주인만 쓰는 내 제어 링크(비밀이 URL 조각에
 * 있다), {@code tunnelUrl} 은 기기(CLI)가 여는 WebSocket 주소다. 평문 토큰·비밀은 이 응답에만 실린다.
 */
public record CreatedDeviceShareResponse(
        Long shareId,
        DeviceShareKind kind,
        String targetLabel,
        String viewUrl,
        String controlUrl,
        String tunnelUrl,
        LocalDateTime expiresAt
) {

    @Override
    public String toString() {
        return "CreatedDeviceShareResponse[shareId=" + shareId + "]";
    }
}
