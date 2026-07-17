package com.example.dvely.environment.application.query;

import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.environment.application.result.EnvironmentVariableHistoryResult;
import com.example.dvely.environment.application.result.EnvironmentVariableResult;
import com.example.dvely.environment.domain.model.EnvironmentVariable;
import com.example.dvely.environment.domain.model.EnvironmentVariableHistory;
import com.example.dvely.environment.domain.repository.EnvironmentVariableHistoryRepository;
import com.example.dvely.environment.domain.repository.EnvironmentVariableRepository;
import com.example.dvely.environment.domain.value.EnvironmentScope;
import com.example.dvely.project.domain.exception.ProjectNotFoundException;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the environment/Secrets domain. {@link #assertProjectOwner}, {@link #findOwned}
 * and {@link #toResult} are also called directly by {@code EnvironmentVariableCommandService} —
 * mirroring how {@code ApprovalCommandService} reuses {@code ApprovalQueryService.toResult(...)}
 * — so ownership checks, not-found lookups and the secret-masking rule stay defined in one place.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EnvironmentVariableQueryService {

    private static final int DEFAULT_HISTORY_LIMIT = 50;
    private static final int MAX_HISTORY_LIMIT = 200;

    private final EnvironmentVariableRepository environmentVariableRepository;
    private final EnvironmentVariableHistoryRepository environmentVariableHistoryRepository;
    private final ProjectRepository projectRepository;

    public List<EnvironmentVariableResult> getVariables(Long userId, Long projectId, String scopeParam) {
        assertProjectOwner(userId, projectId);
        List<EnvironmentVariable> variables = scopeParam == null
                ? environmentVariableRepository.findByProjectIdOrderByScopeAscKeyAsc(projectId)
                : environmentVariableRepository.findByProjectIdAndScopeOrderByKeyAsc(projectId, parseScope(scopeParam));
        return variables.stream().map(this::toResult).toList();
    }

    public List<EnvironmentVariableHistoryResult> getHistory(Long userId, Long projectId, Integer limitParam) {
        assertProjectOwner(userId, projectId);
        int limit = clampLimit(limitParam);
        return environmentVariableHistoryRepository
                .findByProjectIdOrderByCreatedAtDescIdDesc(projectId, limit)
                .stream()
                .map(this::toHistoryResult)
                .toList();
    }

    /** Reused by CommandService so PATCH/DELETE share the exact same not-found lookup as GET. */
    public EnvironmentVariable findOwned(Long projectId, Long variableId) {
        return environmentVariableRepository.findByIdAndProjectId(variableId, projectId)
                .orElseThrow(() -> new NotFoundException("환경변수를 찾을 수 없습니다. variableId=" + variableId));
    }

    /** Reused by CommandService so every mutating endpoint checks ownership the same way as GET. */
    public void assertProjectOwner(Long userId, Long projectId) {
        projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, userId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId, userId));
    }

    /**
     * {@code EnvironmentScope.valueOf} throws {@code IllegalArgumentException} on an unknown
     * name already; we only intercept it to swap in a message that names the actual bad input
     * (raw scope strings are safe to echo — they are never secret values).
     */
    public static EnvironmentScope parseScope(String raw) {
        try {
            return EnvironmentScope.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("지원하지 않는 scope입니다: " + raw);
        }
    }

    /** Secret masking happens exactly here: {@code secret ? null : plaintext}, per design D4. */
    public EnvironmentVariableResult toResult(EnvironmentVariable variable) {
        return new EnvironmentVariableResult(
                variable.getId(),
                variable.getScope().name(),
                variable.getKey(),
                variable.isSecret() ? null : variable.getValue(),
                variable.isSecret(),
                variable.getCreatedAt(),
                variable.getUpdatedAt()
        );
    }

    private EnvironmentVariableHistoryResult toHistoryResult(EnvironmentVariableHistory history) {
        return new EnvironmentVariableHistoryResult(
                history.getId(),
                history.getEnvironmentVariableId(),
                history.getScope().name(),
                history.getKey(),
                history.getAction().name(),
                history.isSecret(),
                history.isValueChanged(),
                history.getActorUserId(),
                history.getCreatedAt()
        );
    }

    private static int clampLimit(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_HISTORY_LIMIT;
        }
        return Math.min(requested, MAX_HISTORY_LIMIT);
    }
}
