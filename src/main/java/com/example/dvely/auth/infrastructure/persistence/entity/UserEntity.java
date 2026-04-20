package com.example.dvely.auth.infrastructure.persistence.entity;

import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.value.GithubId;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String githubId;

    @Column(nullable = false)
    private String username;

    @Column(nullable = true)
    private String avatarUrl;

    // GitHub App Installation ID (nullable - App 미설치 시 null)
    @Column(nullable = true)
    private Long githubInstallationId;

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

    public static UserEntity from(User user) {
        UserEntity entity = new UserEntity(user.getGithubId().value(), user.getUsername(), user.getAvatarUrl());
        entity.githubInstallationId = user.getGithubInstallationId();
        return entity;
    }

    public User toDomain() {
        return new User(id, new GithubId(githubId), username, avatarUrl, githubInstallationId);
    }
}
