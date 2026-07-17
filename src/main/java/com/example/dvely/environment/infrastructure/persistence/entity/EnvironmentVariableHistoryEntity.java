package com.example.dvely.environment.infrastructure.persistence.entity;

import com.example.dvely.environment.domain.model.EnvironmentVariableHistory;
import com.example.dvely.environment.domain.value.EnvironmentScope;
import com.example.dvely.environment.domain.value.EnvironmentVariableAction;
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
 * JPA row for {@code environment_variable_histories} (V22 migration). Append-only: unlike
 * {@link EnvironmentVariableEntity} this has no {@code updateFrom(...)} — history rows are never
 * modified after insert (U3 design D5). No value columns exist here by design.
 */
@Entity
@Table(name = "environment_variable_histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EnvironmentVariableHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "environment_variable_history_id")
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "environment_variable_id")
    private Long environmentVariableId;

    @Column(name = "scope", nullable = false, length = 20)
    private String scope;

    @Column(name = "env_key", nullable = false, length = 128)
    private String key;

    @Column(name = "action", nullable = false, length = 20)
    private String action;

    @Column(name = "secret", nullable = false)
    private boolean secret;

    @Column(name = "value_changed", nullable = false)
    private boolean valueChanged;

    @Column(name = "actor_user_id", nullable = false)
    private Long actorUserId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    private EnvironmentVariableHistoryEntity(Long projectId,
                                             Long environmentVariableId,
                                             String scope,
                                             String key,
                                             String action,
                                             boolean secret,
                                             boolean valueChanged,
                                             Long actorUserId) {
        this.projectId = projectId;
        this.environmentVariableId = environmentVariableId;
        this.scope = scope;
        this.key = key;
        this.action = action;
        this.secret = secret;
        this.valueChanged = valueChanged;
        this.actorUserId = actorUserId;
    }

    public static EnvironmentVariableHistoryEntity from(EnvironmentVariableHistory history) {
        return new EnvironmentVariableHistoryEntity(
                history.getProjectId(),
                history.getEnvironmentVariableId(),
                history.getScope().name(),
                history.getKey(),
                history.getAction().name(),
                history.isSecret(),
                history.isValueChanged(),
                history.getActorUserId()
        );
    }

    public EnvironmentVariableHistory toDomain() {
        return new EnvironmentVariableHistory(
                id,
                projectId,
                environmentVariableId,
                EnvironmentScope.valueOf(scope),
                key,
                EnvironmentVariableAction.valueOf(action),
                secret,
                valueChanged,
                actorUserId,
                createdAt
        );
    }
}
