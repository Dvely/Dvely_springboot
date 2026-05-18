package com.example.dvely.cloudconnection.application.result;

import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.cloudconnection.domain.value.CloudProvider;
import java.time.LocalDateTime;

public record CloudConnectionHealthResult(
        Long cloudConnectionId,
        CloudProvider provider,
        CloudConnectionStatus status,
        String message,
        LocalDateTime checkedAt
) {
}
