package com.example.dvely.apitoken.infrastructure.persistence.entity;

import com.example.dvely.apitoken.domain.model.ApiToken;
import com.example.dvely.apitoken.domain.value.ApiTokenScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * JPA row for {@code api_tokens} (V60).
 *
 * <p>No {@code @Convert} here, unlike {@code AiProviderCredentialEntity}: this column holds a hash,
 * not a recoverable secret. Also deliberately no Lombok {@code @ToString} — the hash is not the
 * token, but there is no reason for it to reach a log line either.</p>
 */
@Entity
@Table(name = "api_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApiTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "api_token_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "token_prefix", nullable = false, length = 16)
    private String tokenPrefix;

    @Column(name = "scope", nullable = false, length = 10)
    private String scope;

    @Column(name = "label", length = 64)
    private String label;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    private ApiTokenEntity(Long userId, String tokenHash, String tokenPrefix,
                           String scope, String label, LocalDateTime expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.tokenPrefix = tokenPrefix;
        this.scope = scope;
        this.label = label;
        this.expiresAt = expiresAt;
    }

    public static ApiTokenEntity from(ApiToken token) {
        return new ApiTokenEntity(
                token.getUserId(),
                token.getTokenHash(),
                token.getTokenPrefix(),
                token.getScope().name(),
                token.getLabel(),
                token.getExpiresAt()
        );
    }

    public ApiToken toDomain() {
        return new ApiToken(
                id, userId, tokenHash, tokenPrefix,
                ApiTokenScope.valueOf(scope), label, expiresAt, lastUsedAt, createdAt
        );
    }
}
