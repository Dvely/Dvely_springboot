package com.example.dvely.deviceshare.application.service;

import com.example.dvely.deviceshare.domain.value.DeviceShareStatus;

/** 만료·회수된 링크로 터널을 열려고 했다. 터널 핸드셰이크가 410 으로 바꾼다. */
public class ShareEndedException extends RuntimeException {

    private final DeviceShareStatus status;

    public ShareEndedException(DeviceShareStatus status) {
        super(status == DeviceShareStatus.REVOKED ? "끝난 공유입니다." : "만료된 공유입니다.");
        this.status = status;
    }

    public DeviceShareStatus status() {
        return status;
    }
}
