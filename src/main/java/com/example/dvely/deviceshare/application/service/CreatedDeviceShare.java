package com.example.dvely.deviceshare.application.service;

import com.example.dvely.deviceshare.domain.model.DeviceShareLink;

/**
 * 막 만든 링크와 평문 토큰·비밀. 평문은 생성 응답에만 실리고 어디에도 저장되지 않는다. 로그에 찍히지 않도록 record 의
 * 기본 {@code toString} 을 덮는다.
 */
public record CreatedDeviceShare(DeviceShareLink link, String viewToken, String controlSecret) {

    @Override
    public String toString() {
        return "CreatedDeviceShare[shareId=" + link.getId() + "]";
    }
}
