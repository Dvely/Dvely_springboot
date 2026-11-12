package com.example.dvely.deviceshare.presentation.dto;

import com.example.dvely.deviceshare.domain.value.DeviceShareKind;
import jakarta.validation.constraints.NotNull;

/** @param ttlMinutes 비우면 60분, 최대 1440분 */
public record CreateDeviceShareRequest(
        @NotNull DeviceShareKind kind,
        String targetLabel,
        Integer ttlMinutes
) {
}
