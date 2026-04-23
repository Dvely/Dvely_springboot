package com.example.dvely.user.presentation.dto;

import com.example.dvely.auth.domain.model.User;

public record UserResponse(
        Long id,
        String username,
        String avatarUrl,
        boolean githubAppInstalled
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
