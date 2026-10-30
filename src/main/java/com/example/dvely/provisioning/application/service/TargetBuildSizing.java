package com.example.dvely.provisioning.application.service;

import java.util.Locale;

/**
 * 대상 EC2 빌드(#556)의 사양 하한. JVM(Gradle·Maven) 빌드는 메모리가 커서 1 GiB 이하 인스턴스(nano·micro)에서는
 * 빌드가 죽거나 한없이 느려진다. swap 으로 버티지 않고 t3.small(2 GiB) 이상을 요구한다(#545 결정). Node 등은
 * 지금처럼 micro 를 허용한다.
 */
final class TargetBuildSizing {

    private TargetBuildSizing() {
    }

    /** nano·micro 크기면 JVM 빌드에 작다. 타입을 모르면(비었으면) 막지 않는다 — 기본값은 서버 생성 쪽이 정한다. */
    static boolean tooSmallForJvm(String instanceType) {
        if (instanceType == null || instanceType.isBlank()) {
            return false;
        }
        String size = instanceType.trim().toLowerCase(Locale.ROOT);
        int dot = size.indexOf('.');
        size = dot < 0 ? size : size.substring(dot + 1);
        return size.equals("nano") || size.equals("micro");
    }

    static String jvmTooSmallMessage(String instanceType) {
        return "JVM(Gradle·Maven) 앱을 서버에서 직접 빌드하려면 t3.small 이상이 필요합니다(지금 " + instanceType
                + "). 인스턴스 타입을 t3.small 이상으로 골라 다시 배포해 주세요.";
    }
}
