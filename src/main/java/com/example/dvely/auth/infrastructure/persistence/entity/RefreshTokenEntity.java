package com.example.dvely.auth.infrastructure.persistence.entity;

import com.example.dvely.auth.domain.model.RefreshToken;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, unique = true, length = 36)
    private String token;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    public RefreshTokenEntity(Long userId, String token, LocalDateTime expiresAt) {
        this.userId = userId;
        this.token = token;
        this.expiresAt = expiresAt;
        this.revoked = false;
    }

    public void revoke() {
        this.revoked = true;
    }

    public static RefreshTokenEntity from(RefreshToken domain) {
        RefreshTokenEntity entity = new RefreshTokenEntity(domain.getUserId(), domain.getToken(), domain.getExpiresAt());
        if (domain.isRevoked()) {
            entity.revoke();
        }
        return entity;
    }

    public RefreshToken toDomain() {
        return new RefreshToken(id, userId, token, expiresAt, revoked);
    }
}
