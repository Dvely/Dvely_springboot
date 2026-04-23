package com.example.dvely.auth.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "revoked_access_tokens",
    indexes = @Index(name = "idx_revoked_tokens_jti", columnList = "jti")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RevokedTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 36)
    private String jti;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    public RevokedTokenEntity(String jti, LocalDateTime expiresAt) {
        this.jti = jti;
        this.expiresAt = expiresAt;
    }
}
