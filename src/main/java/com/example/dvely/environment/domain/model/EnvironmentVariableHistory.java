package com.example.dvely.environment.domain.model;

import com.example.dvely.environment.domain.value.EnvironmentScope;
import com.example.dvely.environment.domain.value.EnvironmentVariableAction;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Append-only audit record for a single change to an {@link EnvironmentVariable}. Deliberately
 * has no value/old-value/new-value field (U3 design D5) — recording secret snapshots here would
 * let them accumulate indefinitely with no rotation/cleanup story, widening the attack surface
 * for no PRD-required benefit ("what changed and when", not "what was the old value").
 *
 * <p>Immutable: no setters, no update methods. {@code environmentVariableId} is the one field
 * that can legitimately become {@code null} later — not at construction time here, but after the
 * referenced row is hard-deleted and the DB FK (`ON DELETE SET NULL`) clears it on reload.</p>
 */
public class EnvironmentVariableHistory {

    private final Long id;
    private final Long projectId;
    private final Long environmentVariableId;
    private final EnvironmentScope scope;
    private final String key;
    private final EnvironmentVariableAction action;
    private final boolean secret;
    private final boolean valueChanged;
    private final Long actorUserId;
    private final LocalDateTime createdAt;

    /** New-record constructor (used when CommandService writes an event). */
    public EnvironmentVariableHistory(Long projectId,
                                      Long environmentVariableId,
                                      EnvironmentScope scope,
                                      String key,
                                      EnvironmentVariableAction action,
                                      boolean secret,
                                      boolean valueChanged,
                                      Long actorUserId) {
        this(null, projectId, environmentVariableId, scope, key, action, secret, valueChanged, actorUserId, null);
    }

    /** Restore-from-storage constructor. */
    public EnvironmentVariableHistory(Long id,
                                      Long projectId,
                                      Long environmentVariableId,
                                      EnvironmentScope scope,
                                      String key,
                                      EnvironmentVariableAction action,
                                      boolean secret,
                                      boolean valueChanged,
                                      Long actorUserId,
                                      LocalDateTime createdAt) {
        this.id = id;
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        // environmentVariableId is intentionally NOT null-checked: it can be null once the
        // referenced variable has been hard-deleted (FK ON DELETE SET NULL) and the row reloaded.
        this.environmentVariableId = environmentVariableId;
        this.scope = Objects.requireNonNull(scope, "scope must not be null");
        this.key = Objects.requireNonNull(key, "key must not be null");
        this.action = Objects.requireNonNull(action, "action must not be null");
        this.secret = secret;
        this.valueChanged = valueChanged;
        this.actorUserId = Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getProjectId() { return projectId; }
    public Long getEnvironmentVariableId() { return environmentVariableId; }
    public EnvironmentScope getScope() { return scope; }
    public String getKey() { return key; }
    public EnvironmentVariableAction getAction() { return action; }
    public boolean isSecret() { return secret; }
    public boolean isValueChanged() { return valueChanged; }
    public Long getActorUserId() { return actorUserId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
