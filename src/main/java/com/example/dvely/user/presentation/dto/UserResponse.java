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
        @Schema(description = "액세스 토큰이 만료됐는지 여부(진단용). true여도 리프레시 토큰이 살아 있으면 서버가 자동 갱신하므로 유저에게 재인증을 요구하면 안 된다 — 재인증 여부는 githubAppReauthorizationRequired로 판단할 것") boolean githubAppTokenExpired,
        @Schema(description = "유저가 직접 재인증해야 하는지 여부. true일 때만 재인증 UI를 띄운다") boolean githubAppReauthorizationRequired,
        @Schema(description = "GitHub App 액세스 토큰 만료 시각 (null이면 미연동)") LocalDateTime githubAppAccessTokenExpiresAt,
        @Schema(description = "GitHub App 리프레시 토큰 만료 시각 (null이면 미연동). 이 시각이 지나면 GitHub App 재설치 필요") LocalDateTime githubAppRefreshTokenExpiresAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getAvatarUrl(),
                user.hasGithubAppInstalled(),
                user.getGithubUserRefreshToken() != null,
                user.isUserAccessTokenExpired(),
                user.needsGithubAppReauthorization(),
                user.getUserAccessTokenExpiresAt(),
                user.getRefreshTokenExpiresAt()
        );
    }
}
