package com.example.dvely.common.response;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Auth
    INVALID_TOKEN(401, "INVALID_TOKEN", "유효하지 않은 토큰입니다"),
    EXPIRED_REFRESH_TOKEN(401, "EXPIRED_REFRESH_TOKEN", "만료된 리프레시 토큰입니다"),
    REVOKED_REFRESH_TOKEN(401, "REVOKED_REFRESH_TOKEN", "이미 사용된 리프레시 토큰입니다"),
    GITHUB_APP_NOT_INSTALLED(403, "GITHUB_APP_NOT_INSTALLED", "GitHub App이 설치되지 않았습니다"),

    // Common
    BAD_REQUEST(400, "BAD_REQUEST", "잘못된 요청입니다"),
    UNAUTHORIZED(401, "UNAUTHORIZED", "인증이 필요합니다"),
    FORBIDDEN(403, "FORBIDDEN", "접근 권한이 없습니다"),
    NOT_FOUND(404, "NOT_FOUND", "리소스를 찾을 수 없습니다"),
    METHOD_NOT_ALLOWED(405, "METHOD_NOT_ALLOWED", "허용되지 않는 HTTP 메서드입니다"),
    CONFLICT(409, "CONFLICT", "현재 리소스 상태와 요청이 충돌합니다"),
    INTERNAL_SERVER_ERROR(500, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다");

    private final int status;
    private final String code;
    private final String message;
}
