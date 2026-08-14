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
    INTERNAL_SERVER_ERROR(500, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다"),

    // AI 제공자 (Anthropic/OpenAI). 요청 자체는 정상이고 제공자 쪽 상태가 원인이므로 4xx가 아니다 —
    // 클라이언트는 이 코드들을 보고 "다른 제공자로 전환"(UNAVAILABLE)과 "잠시 후 재시도"(나머지)를
    // 구분할 수 있어야 한다.
    AI_PROVIDER_UNAVAILABLE(503, "AI_PROVIDER_UNAVAILABLE", "선택한 AI 제공자를 사용할 수 없습니다"),
    AI_PROVIDER_RATE_LIMITED(429, "AI_PROVIDER_RATE_LIMITED", "AI 제공자 요청량 한도를 초과했습니다"),
    AI_PROVIDER_ERROR(502, "AI_PROVIDER_ERROR", "AI 제공자 호출에 실패했습니다"),

    // 프리뷰 실행 환경(Docker). AI 제공자 코드들과 같은 이유로 4xx가 아니다 — 요청은 정상이고
    // 서버 쪽 실행 환경이 원인이며, 클라이언트가 할 수 있는 일은 재시도 또는 운영자 문의뿐이다.
    PREVIEW_ENVIRONMENT_UNAVAILABLE(503, "PREVIEW_ENVIRONMENT_UNAVAILABLE", "프리뷰 실행 환경을 사용할 수 없습니다");

    private final int status;
    private final String code;
    private final String message;
}
