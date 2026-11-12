package com.example.dvely.deviceshare.domain.value;

/** 공유하는 앱 화면의 종류. 1단계는 {@link #WEB}(CLI 가 띄운 dev 브라우저)만 쓴다. */
public enum DeviceShareKind {
    WEB,
    ANDROID,
    IOS
}
