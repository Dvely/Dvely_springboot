package com.example.dvely.environment.infrastructure.persistence.entity;

import com.example.dvely.auth.infrastructure.persistence.converter.AesEncryptor;
import com.example.dvely.environment.domain.model.EnvironmentVariable;
import com.example.dvely.environment.domain.value.EnvironmentScope;
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
 * JPA row for {@code environment_variables} (V22 migration). {@code value} is encrypted at rest
 * via the existing {@link AesEncryptor} converter — reused as-is per U3 design D3, no new crypto
 * code. Deliberately no Lombok {@code @ToString}: never let this entity's decrypted value reach a
 * log line through an accidental toString() call (see EnvironmentVariable's class doc).
 */
@Entity
@Table(name = "environment_variables")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EnvironmentVariableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "environment_variable_id")
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "scope", nullable = false, length = 20)
    private String scope;

    // "key" is a MySQL reserved word, hence the env_ prefix (mirrors the migration's column name).
    @Column(name = "env_key", nullable = false, length = 128)
    private String key;

    @Column(name = "env_value", nullable = false, columnDefinition = "MEDIUMTEXT")
    @Convert(converter = AesEncryptor.class)
    private String value;

    @Column(name = "secret", nullable = false)
    private boolean secret;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    private EnvironmentVariableEntity(Long projectId, String scope, String key, String value, boolean secret) {
        this.projectId = projectId;
        this.scope = scope;
        this.key = key;
        this.value = value;
        this.secret = secret;
    }

    public static EnvironmentVariableEntity from(EnvironmentVariable variable) {
        return new EnvironmentVariableEntity(
                variable.getProjectId(),
                variable.getScope().name(),
                variable.getKey(),
                variable.getValue(),
                variable.isSecret()
        );
    }

    /**
     * Only value/secret are copied — scope/key are immutable by design (D6). Keeping them out of
     * this method (even though the domain object technically still has getters for them) makes
     * the "never changes after creation" contract explicit at the persistence boundary too.
     */
    public void updateFrom(EnvironmentVariable variable) {
        this.value = variable.getValue();
        this.secret = variable.isSecret();
    }

    public EnvironmentVariable toDomain() {
        return new EnvironmentVariable(
                id,
                projectId,
                EnvironmentScope.valueOf(scope),
                key,
                value,
                secret,
                createdAt,
                updatedAt
        );
    }
}
