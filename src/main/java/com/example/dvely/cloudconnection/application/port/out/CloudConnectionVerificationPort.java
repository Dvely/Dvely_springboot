package com.example.dvely.cloudconnection.application.port.out;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;

public interface CloudConnectionVerificationPort {

    VerificationResult verify(CloudConnection cloudConnection);

    record VerificationResult(CloudConnectionStatus status, String message) {
    }
}
