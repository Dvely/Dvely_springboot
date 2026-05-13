package com.example.dvely.auth.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "인증 토큰 응답")
public record AuthTokenResponse(
        @Schema(description = "서비스 JWT. API 요청 시 Authorization: Bearer {accessToken} 헤더에 사용합니다.") String accessToken,
        @Schema(description = "Access Token 갱신용 토큰. 만료 시 /api/v1/auth/refresh로 새 토큰을 발급합니다.") String refreshToken,
        @Schema(description = "GitHub App 설치 여부. false이면 /api/v1/auth/github/app/install-url로 유도해야 합니다.") boolean githubAppInstalled
) {}
