package com.example.dvely.user.presentation.dto;

import com.example.dvely.auth.domain.model.User;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "로그인 유저 프로필 정보")
public record UserResponse(
        @Schema(description = "유저 ID") Long id,
        @Schema(description = "GitHub 로그인명") String username,
        @Schema(description = "GitHub 프로필 이미지 URL") String avatarUrl,
        @Schema(description = "GitHub App 설치 여부. false이면 /api/v1/auth/github/app/install-url로 유도 필요") boolean githubAppInstalled,
        @Schema(description = "GitHub App User Token 연동 여부. false이면 GitHub App 재설치 필요") boolean githubAppTokenLinked,
        @Schema(description = "현재 액세스 토큰이 만료됐는지 여부") boolean githubAppTokenExpired,
        @Schema(description = "GitHub App 액세스 토큰 만료 시각 (null이면 미연동)") LocalDateTime githubAppAccessTokenExpiresAt,
        @Schema(description = "GitHub App 리프레시 토큰 만료 시각 (null이면 미연동). 이 시각이 지나면 GitHub App 재설치 필요") LocalDateTime githubAppRefreshTokenExpiresAt
) {
    private static final long ACCESS_TOKEN_HOURS = 8L;
    private static final long REFRESH_TOKEN_DAYS = 180L;

    public static UserResponse from(User user) {
        LocalDateTime accessExpiresAt = user.getUserAccessTokenExpiresAt();
        LocalDateTime refreshExpiresAt = accessExpiresAt != null
                ? accessExpiresAt.minusHours(ACCESS_TOKEN_HOURS).plusDays(REFRESH_TOKEN_DAYS)
                : null;

        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getAvatarUrl(),
                user.hasGithubAppInstalled(),
                user.getGithubUserRefreshToken() != null,
                user.isUserAccessTokenExpired(),
                accessExpiresAt,
                refreshExpiresAt
        );
    }
}
