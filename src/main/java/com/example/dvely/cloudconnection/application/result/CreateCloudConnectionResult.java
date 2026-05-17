package com.example.dvely.cloudconnection.application.result;

import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.cloudconnection.domain.value.CloudProvider;

public record CreateCloudConnectionResult(
        Long cloudConnectionId,
        CloudProvider provider,
        CloudConnectionStatus status,
        String jobId
) {
}
