package com.example.dvely.cloudconnection.domain.value;

public enum CloudConnectionStatus {
    CHECKING,
    CONNECTED,
    PERMISSION_MISSING,
    BILLING_DISABLED,
    REGION_UNSUPPORTED,
    INVALID_CREDENTIAL,
    UNKNOWN_ERROR
}
