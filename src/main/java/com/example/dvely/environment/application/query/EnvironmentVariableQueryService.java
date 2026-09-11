package com.example.dvely.environment.application.query;

import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.common.paging.CursorPage;
import com.example.dvely.common.paging.CursorPaging;
import com.example.dvely.environment.application.result.EnvironmentVariableHistoryResult;
import com.example.dvely.environment.application.result.EnvironmentVariableResult;
import com.example.dvely.environment.domain.model.EnvironmentVariable;
import com.example.dvely.environment.domain.model.EnvironmentVariableHistory;
import com.example.dvely.environment.domain.repository.EnvironmentVariableHistoryRepository;
import com.example.dvely.environment.domain.repository.EnvironmentVariableRepository;
import com.example.dvely.environment.domain.repository.EnvironmentVariableSummaryView;
import com.example.dvely.environment.domain.value.EnvironmentScope;
import com.example.dvely.project.domain.exception.ProjectNotFoundException;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.util.List;
import java.util.Map;
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

    /**
     * 변수 목록의 상한. 환경변수는 사람이 직접 정의하는 값이라 프로젝트당 수십 개가 현실적인 상한이고,
     * 200 을 넘는 것은 정상 사용이 아니다 — 그래서 커서로 이어 받는 대신 상한만 둔다(정렬 키가
     * (scope, key) 라 id 커서와 맞지 않는 것도 이유다. 필요해지면 복합 커서로 확장한다).
     */
    private static final int DEFAULT_VARIABLE_LIMIT = 200;
    private static final int MAX_VARIABLE_LIMIT = 500;

    private final EnvironmentVariableRepository environmentVariableRepository;
    private final EnvironmentVariableHistoryRepository environmentVariableHistoryRepository;
    private final ProjectRepository projectRepository;

    public List<EnvironmentVariableResult> getVariables(Long userId, Long projectId, String scopeParam) {
        return getVariables(userId, projectId, scopeParam, null).items();
    }

    /**
     * 변수 목록. U6 6-5 로 <b>값을 두 번에 나눠 읽는다</b>: 메타데이터는 env_value 없이 프로젝션으로,
     * 평문은 secret 이 아닌 행에 대해서만 따로. 응답은 예전과 같다(secret 이면 value=null) —
     * 달라진 것은 비밀 평문이 애초에 읽히지 않는다는 점이다.
     */
    public CursorPage<EnvironmentVariableResult> getVariables(Long userId,
                                                              Long projectId,
                                                              String scopeParam,
                                                              Integer limitParam) {
        assertProjectOwner(userId, projectId);
        EnvironmentScope scope = scopeParam == null ? null : parseScope(scopeParam);
        int size = CursorPaging.clamp(limitParam, DEFAULT_VARIABLE_LIMIT, MAX_VARIABLE_LIMIT);
        List<EnvironmentVariableSummaryView> probed =
                environmentVariableRepository.findSummaries(projectId, scope, size + 1);
        CursorPage<EnvironmentVariableSummaryView> page = CursorPaging.slice(
                probed, size, view -> String.valueOf(view.id()));
        Map<Long, String> plainValues = environmentVariableRepository.findPlainValuesByIds(
                page.items().stream().map(EnvironmentVariableSummaryView::id).toList());
        return page.map(view -> toResult(view, plainValues));
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

    /**
     * U6 6-5: 목록 경로의 매핑. secret 인 변수는 {@code plainValues} 에 애초에 들어오지 않으므로
     * value 가 null 이 된다 — 마스킹이 아니라 "읽지 않았다" 다. 결과 JSON 은 design D4 와 같다.
     */
    private EnvironmentVariableResult toResult(EnvironmentVariableSummaryView view,
                                               Map<Long, String> plainValues) {
        return new EnvironmentVariableResult(
                view.id(),
                view.scope(),
                view.key(),
                view.secret() ? null : plainValues.get(view.id()),
                view.secret(),
                view.createdAt(),
                view.updatedAt()
        );
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
