package com.example.dvely.provisioning.application.service;

/** 백엔드 소스 빌드(clone·gradle) 실패. 배포 러너가 이걸 잡아 서버를 FAILED 로 닫는다. */
public class BackendBuildException extends RuntimeException {
    public BackendBuildException(String message) {
        super(message);
    }
}
