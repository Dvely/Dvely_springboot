package com.example.dvely.aiaccount.infrastructure.persistence.entity;

import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.aiaccount.domain.model.AiProviderCredential;
import com.example.dvely.auth.infrastructure.persistence.converter.AesEncryptor;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import org.hibernate.annotations.UpdateTimestamp;

/**
 * JPA row for {@code ai_provider_credentials} (V44 migration). {@code encrypted_api_key} is
 * encrypted at rest via the existing {@link AesEncryptor} converter — reused as-is, no new crypto
 * code (same choice as {@code EnvironmentVariableEntity}).
 *
 * <p>Deliberately no Lombok {@code @ToString}: never let a decrypted API key reach a log line
 * through an accidental {@code toString()} call.</p>
 */
@Entity
@Table(name = "ai_provider_credentials")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiProviderCredentialEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ai_provider_credential_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "provider", nullable = false, length = 20)
    private String provider;

    @Column(name = "encrypted_api_key", nullable = false, columnDefinition = "MEDIUMTEXT")
    @Convert(converter = AesEncryptor.class)
    private String apiKey;

    @Column(name = "label", length = 64)
    private String label;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    private AiProviderCredentialEntity(Long userId, String provider, String apiKey, String label) {
        this.userId = userId;
        this.provider = provider;
        this.apiKey = apiKey;
        this.label = label;
    }

    public static AiProviderCredentialEntity from(AiProviderCredential credential) {
        return new AiProviderCredentialEntity(
                credential.getUserId(),
                credential.getProvider().name(),
                credential.getApiKey(),
                credential.getLabel()
        );
    }

    /**
     * Only key/label are copied — owner and provider are immutable by design: changing either
     * would make this a different credential, and the (user, provider) unique key is what keeps
     * one row per vendor per user.
     */
    public void updateFrom(AiProviderCredential credential) {
        this.apiKey = credential.getApiKey();
        this.label = credential.getLabel();
    }

    public AiProviderCredential toDomain() {
        return new AiProviderCredential(
                id,
                userId,
                AiProvider.valueOf(provider),
                apiKey,
                label,
                createdAt,
                updatedAt
        );
    }
}
