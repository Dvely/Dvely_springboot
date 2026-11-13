package com.example.dvely.deviceshare.domain.value;

/**
 * 공유하는 앱 화면의 종류. 1단계 {@link #WEB}(CLI 가 띄운 dev 브라우저), 2단계 {@link #ANDROID}(에뮬레이터),
 * 3단계 {@link #IOS}(macOS 의 iOS 시뮬레이터). 화면 형식은 링크 종류와 따로 기기 config 의 format 이 정한다(설계 §12.2·§13).
 */
public enum DeviceShareKind {
    WEB,
    ANDROID,
    IOS
}
