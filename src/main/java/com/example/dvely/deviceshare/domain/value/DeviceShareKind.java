package com.example.dvely.deviceshare.domain.value;

/**
 * 공유하는 앱 화면의 종류. 1단계 {@link #WEB}(CLI 가 띄운 dev 브라우저), 2단계 {@link #ANDROID}(에뮬레이터).
 * {@link #IOS} 는 아직 받지 않는다.
 */
public enum DeviceShareKind {
    WEB,
    ANDROID,
    IOS
}
