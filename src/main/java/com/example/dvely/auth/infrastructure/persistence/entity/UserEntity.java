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

    public UserEntity(String githubId, String username) {
        this.githubId = githubId;
        this.username = username;
    }

    public void updateUsername(String username) {
        this.username = username;
    }

    public static UserEntity from(User user) {
        return new UserEntity(user.getGithubId().value(), user.getUsername());
    }

    public User toDomain() {
        return new User(id, new GithubId(githubId), username);
    }
}
