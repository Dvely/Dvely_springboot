package com.example.dvely.environment.application.command;

import com.example.dvely.environment.application.query.EnvironmentVariableQueryService;
import com.example.dvely.environment.application.result.EnvironmentVariableResult;
import com.example.dvely.environment.domain.model.EnvironmentVariable;
import com.example.dvely.environment.domain.model.EnvironmentVariableHistory;
import com.example.dvely.environment.domain.repository.EnvironmentVariableHistoryRepository;
import com.example.dvely.environment.domain.repository.EnvironmentVariableRepository;
import com.example.dvely.environment.domain.value.EnvironmentScope;
import com.example.dvely.environment.domain.value.EnvironmentVariableAction;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Write side of the environment/Secrets domain. Every method writes the variable and its
 * append-only history row in the same transaction (U3 design §5) — there is no separate event
 * bus, so "no history lost" is guaranteed by ordinary DB atomicity rather than extra plumbing.
 */
@Service
@RequiredArgsConstructor
public class EnvironmentVariableCommandService {

    private final EnvironmentVariableRepository environmentVariableRepository;
    private final EnvironmentVariableHistoryRepository environmentVariableHistoryRepository;
    private final EnvironmentVariableQueryService queryService;

    @Transactional
    public EnvironmentVariableResult create(Long userId,
                                            Long projectId,
                                            String rawScope,
                                            String key,
                                            String value,
                                            boolean secret) {
        queryService.assertProjectOwner(userId, projectId);
        EnvironmentScope scope = EnvironmentVariableQueryService.parseScope(rawScope);

        // First guard: a plain SELECT, cheap and gives a clean error message for the common
        // (non-concurrent) case. See saveNewGuardingAgainstRace() for the second guard that
        // catches the race this can't (design §3.6 "중복 생성 레이스 방어", two layers required).
        assertKeyAvailable(projectId, scope, key);

        EnvironmentVariable variable = new EnvironmentVariable(projectId, scope, key, value, secret);
        EnvironmentVariable saved = saveNewGuardingAgainstRace(variable);

        recordHistory(saved, EnvironmentVariableAction.CREATED, userId, true);
        return queryService.toResult(saved);
    }

    @Transactional
    public EnvironmentVariableResult update(Long userId,
                                            Long projectId,
                                            Long variableId,
                                            String newValue,
                                            Boolean secretRequest) {
        queryService.assertProjectOwner(userId, projectId);
        if (newValue == null && secretRequest == null) {
            throw new IllegalArgumentException("변경할 항목이 없습니다.");
        }
        EnvironmentVariable variable = queryService.findOwned(projectId, variableId);

        boolean valueChanged = false;
        if (newValue != null) {
            valueChanged = !newValue.equals(variable.getValue());
            variable.changeValue(newValue);
        }
        if (secretRequest != null) {
            if (secretRequest) {
                variable.markSecret();
            } else if (variable.isSecret()) {
                // true -> false: EnvironmentVariable.unmarkSecret() always throws (D7). If it's
                // already false and false was requested again, that's a no-op — we simply skip
                // calling unmarkSecret() in that case rather than treating it as an error.
                variable.unmarkSecret();
            }
        }

        EnvironmentVariable saved = environmentVariableRepository.save(variable);
        recordHistory(saved, EnvironmentVariableAction.UPDATED, userId, valueChanged);
        return queryService.toResult(saved);
    }

    @Transactional
    public void delete(Long userId, Long projectId, Long variableId) {
        queryService.assertProjectOwner(userId, projectId);
        EnvironmentVariable variable = queryService.findOwned(projectId, variableId);
        environmentVariableRepository.deleteById(variable.getId());
        // Recorded with the pre-delete variable's own id: the FK (ON DELETE SET NULL) only clears
        // environment_variable_id on *reload* after commit, not on the object we already hold.
        recordHistory(variable, EnvironmentVariableAction.DELETED, userId, false);
    }

    private void assertKeyAvailable(Long projectId, EnvironmentScope scope, String key) {
        environmentVariableRepository.findByProjectIdAndScopeAndKey(projectId, scope, key)
                .ifPresent(existing -> {
                    throw new IllegalStateException(conflictMessage(scope, key));
                });
    }

    private EnvironmentVariable saveNewGuardingAgainstRace(EnvironmentVariable variable) {
        try {
            return environmentVariableRepository.save(variable);
        } catch (DataIntegrityViolationException exception) {
            // Two concurrent creates can both pass assertKeyAvailable()'s SELECT before either
            // INSERT commits; the DB unique constraint (project_id, scope, env_key) is the real
            // guard for that race. Translate it to the same 409 shape the pre-check throws so
            // callers only ever see one conflict error, never a raw 500 (design D8).
            throw new IllegalStateException(
                    conflictMessage(variable.getScope(), variable.getKey()), exception
            );
        }
    }

    private String conflictMessage(EnvironmentScope scope, String key) {
        return "이미 존재하는 환경변수 키입니다. scope=" + scope + ", key=" + key;
    }

    private void recordHistory(EnvironmentVariable variable,
                               EnvironmentVariableAction action,
                               Long actorUserId,
                               boolean valueChanged) {
        environmentVariableHistoryRepository.save(new EnvironmentVariableHistory(
                variable.getProjectId(),
                variable.getId(),
                variable.getScope(),
                variable.getKey(),
                action,
                variable.isSecret(),
                valueChanged,
                actorUserId
        ));
    }
}
