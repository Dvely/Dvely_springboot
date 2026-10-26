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

    /** 원문이 아니라 SHA-256 hex 다(#506). 원문은 이 테이블 어디에도 없다. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    public RefreshTokenEntity(Long userId, String tokenHash, LocalDateTime expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.revoked = false;
    }

    /** 원문은 저장하지 않으므로 읽어 온 도메인 객체의 token 은 비어 있다. */
    public RefreshToken toDomain() {
        return new RefreshToken(id, userId, null, expiresAt, revoked);
    }
}
