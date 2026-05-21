package com.example.dvely.auth.infrastructure.persistence.entity;

import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.value.GithubId;
import com.example.dvely.auth.infrastructure.persistence.converter.AesEncryptor;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Column(name = "github_user_id", unique = true)
    private String githubId;

    @Column(name = "user_name")
    private String username;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "github_installation_id")
    private Long githubInstallationId;

    // GitHub App User Token (AES-256-GCM 암호화 저장)
    @Column(name = "github_user_access_token", columnDefinition = "TEXT")
    @Convert(converter = AesEncryptor.class)
    private String githubUserAccessToken;

    @Column(name = "github_user_refresh_token", columnDefinition = "TEXT")
    @Convert(converter = AesEncryptor.class)
    private String githubUserRefreshToken;

    @Column(name = "user_access_token_expires_at")
    private LocalDateTime userAccessTokenExpiresAt;

    public UserEntity(String githubId, String username, String avatarUrl) {
        this.githubId = githubId;
        this.username = username;
        this.avatarUrl = avatarUrl;
    }

    public void updateProfile(String username, String avatarUrl) {
        this.username = username;
        this.avatarUrl = avatarUrl;
    }

    public void updateInstallationId(Long installationId) {
        this.githubInstallationId = installationId;
    }

    public void updateUserToken(String accessToken, String refreshToken, LocalDateTime expiresAt) {
        this.githubUserAccessToken = accessToken;
        this.githubUserRefreshToken = refreshToken;
        this.userAccessTokenExpiresAt = expiresAt;
    }

    public static UserEntity from(User user) {
        UserEntity entity = new UserEntity(user.getGithubId().value(), user.getUsername(), user.getAvatarUrl());
        entity.githubInstallationId = user.getGithubInstallationId();
        entity.githubUserAccessToken = user.getGithubUserAccessToken();
        entity.githubUserRefreshToken = user.getGithubUserRefreshToken();
        entity.userAccessTokenExpiresAt = user.getUserAccessTokenExpiresAt();
        return entity;
    }

    public User toDomain() {
        return new User(id, new GithubId(githubId), username, avatarUrl,
                githubInstallationId, githubUserAccessToken,
                githubUserRefreshToken, userAccessTokenExpiresAt);
    }
}
