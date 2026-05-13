package com.example.dvely.user.presentation.dto;

import com.example.dvely.auth.domain.model.User;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "로그인 유저 프로필 정보")
public record UserResponse(
        @Schema(description = "유저 ID") Long id,
        @Schema(description = "GitHub 로그인명") String username,
        @Schema(description = "GitHub 프로필 이미지 URL") String avatarUrl,
        @Schema(description = "GitHub App 설치 여부. false이면 /api/v1/auth/github/app/install-url로 유도 필요") boolean githubAppInstalled
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getAvatarUrl(),
                user.hasGithubAppInstalled()
        );
    }
}
