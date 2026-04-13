package com.example.dvely.auth.presentation.dto;

/**
 * @param accessToken        서비스 JWT (API 요청 시 Authorization 헤더에 사용)
 * @param githubAppInstalled GitHub App 설치 여부
 *                           false이면 /api/v1/auth/github/app/install-url로 유도
 */
public record AuthTokenResponse(String accessToken, boolean githubAppInstalled) {}
