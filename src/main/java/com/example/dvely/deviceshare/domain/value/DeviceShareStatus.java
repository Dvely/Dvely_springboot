package com.example.dvely.deviceshare.domain.value;

/** 링크 상태. 저장하지 않고 만료·회수 시각에서 계산한다. */
public enum DeviceShareStatus {
    ACTIVE,
    EXPIRED,
    REVOKED
}
