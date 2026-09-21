package com.example.dvely.deployment.application.service;

import com.example.dvely.agent.application.service.BuildFailureAnalyzer;
import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.common.security.SecretRedactor;
import com.example.dvely.deployment.application.port.out.GithubActionsPort;
import com.example.dvely.deployment.application.result.DeploymentFailureAnalysisResult;
import com.example.dvely.deployment.domain.model.DeploymentFailureAnalysis;
import com.example.dvely.deployment.domain.model.DeploymentHistory;
import com.example.dvely.deployment.domain.repository.DeploymentFailureAnalysisRepository;
import com.example.dvely.deployment.domain.repository.DeploymentHistoryRepository;
import com.example.dvely.deployment.domain.value.AnalysisSource;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * On-demand deployment failure analysis (U6 design §3): given a FAILED {@code DeploymentHistory},
 * collects its GitHub Actions job logs (reusing the same {@code getJobLogs} call the existing
 * "view logs" feature uses — no new GitHub API surface), excerpts the most relevant ~12,000
 * characters, runs the rule-based {@link BuildFailureAnalyzer} over it, and persists the result.
 * A saved analysis is returned as-is on subsequent calls (idempotent, no repeat GitHub log
 * fetch) — see {@link #analyze}.
 *
 * <p><b>이 경로에는 LLM 이 없다(#364).</b> 이 분석은 사용자가 {@code aiProvider} 를 고르지 않는
 * 내부 경로라 BYOK(사용자 본인 키)로 돌릴 수 없고, 예전처럼 서버 키로 호출하면 운영자 계정에
 * 과금된다. 그래서 LLM 호출을 걷어내고 룰 기반 분석만 남겼다 — 품질은 떨어져도 기능은 그대로다.
 * 이 클래스는 LLM 관련 의존을 일부러 갖지 않으며(테스트가 못박는다), BYOK 요약을 붙이더라도
 * 사용자 키를 넘겨받는 별도 경로로 붙여야 한다.</p>
 *
 * <p>Deliberately NOT {@code @Transactional} at the class/method level for {@link #analyze}:
 * the GitHub log fetch is a network call that can take seconds, and holding a DB transaction
 * open for that long would tie up a connection pool slot for no benefit (design §3.4). Each DB
 * interaction (cache lookup, final save) uses its own short-lived transaction instead.</p>
 *
 * <p>Logging rule (design §4): never print the analysis summary, log excerpt, or raw GitHub log
 * text to the server log — user code and possibly tokens can appear in build output. Only
 * historyId/runId/source/excerpt length/duration are logged.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeploymentFailureAnalysisService {

    private static final int MAX_EXCERPT_LENGTH = 12_000;
    private static final int ERROR_LINES_BUDGET = 8_000;
    private static final int CONTEXT_LINES = 3;
    private static final Pattern ERROR_LINE_PATTERN = Pattern.compile(
            "##\\[error\\]|\\berror\\b|npm ERR!|exception|failed", Pattern.CASE_INSENSITIVE);

    private final DeploymentHistoryRepository deploymentHistoryRepository;
    private final DeploymentFailureAnalysisRepository analysisRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final AuthCommandService authCommandService;
    private final GithubActionsPort githubActionsPort;
    private final BuildFailureAnalyzer buildFailureAnalyzer;

    // Review follow-up F1: per-historyId in-flight lock so concurrent POSTs on *this instance*
    // serialize onto a single GitHub log fetch instead of each independently issuing one (see
    // analyzeExclusively()). Entries are removed once their analysis completes — see that
    // method's try/finally — so this stays bounded by "analyses currently in flight", not by
    // total historical analysis count.
    private final ConcurrentHashMap<Long, Object> inFlightLocks = new ConcurrentHashMap<>();

    /**
     * GET semantics: returns only a previously saved analysis, no side effects, no GitHub
     * call. 404 when nothing has been saved yet — the FE is expected to offer a "run analysis"
     * action ({@link #analyze}) in that case (design §1.2).
     */
    public DeploymentFailureAnalysisResult getAnalysis(Long ownerUserId, Long historyId) {
        DeploymentHistory history = findOwnedHistory(ownerUserId, historyId);
        DeploymentFailureAnalysis analysis = analysisRepository.findByHistoryId(history.getId())
                .orElseThrow(() -> new NotFoundException(
                        "저장된 분석 결과가 없습니다. historyId=" + historyId));
        return toResult(analysis);
    }

    /**
     * POST semantics: idempotent-create. If an analysis already exists for this history, it is
     * returned unchanged (no GitHub call at all — verified by the cache-hit test case).
     * Otherwise the target must be FAILED (409 otherwise), and a fresh analysis is computed and
     * persisted.
     */
    public DeploymentFailureAnalysisResult analyze(Long ownerUserId, Long historyId) {
        DeploymentHistory history = findOwnedHistory(ownerUserId, historyId);

        Optional<DeploymentFailureAnalysis> cached = analysisRepository.findByHistoryId(history.getId());
        if (cached.isPresent()) {
            return toResult(cached.get());
        }
        if (history.getStatus() != DeployStatus.FAILED) {
            throw new IllegalStateException("실패한 배포만 분석할 수 있습니다. status=" + history.getStatus());
        }

        return analyzeExclusively(ownerUserId, history);
    }

    /**
     * F1 fix: the cache-miss check in {@link #analyze} is not itself exclusive — without this,
     * N concurrent POSTs for the same historyId would each pass that check and independently
     * issue a full GitHub log fetch. {@code uk_deployment_failure_analyses_history}
     * (see {@link #saveGuardingAgainstRace}) only prevents the duplicate row, not the duplicate
     * work (and the extra GitHub API rate-limit budget it burns), so it alone doesn't guarantee
     * the "one analysis per history" promise (design D1). A per-historyId in-memory lock serializes concurrent requests
     * <b>on this instance</b> so only the first caller actually does the work; every other
     * caller blocks briefly on the same lock and then hits the double-checked cache below
     * instead of redoing it.
     *
     * <p>This is a single-instance guard only. If this service ever runs behind more than one
     * app instance, {@code uk_deployment_failure_analyses_history} + the race recovery in
     * {@link #saveGuardingAgainstRace} remain the cross-instance backstop exactly as before this
     * fix — a second instance could still issue one redundant log fetch in that narrow
     * cross-instance race window, but never produce a duplicate row.</p>
     */
    private DeploymentFailureAnalysisResult analyzeExclusively(Long ownerUserId, DeploymentHistory history) {
        Long historyId = history.getId();
        Object lock = inFlightLocks.computeIfAbsent(historyId, id -> new Object());
        try {
            synchronized (lock) {
                // Double-check: another thread on this instance may have finished computing and
                // saving the analysis while we were waiting to acquire this lock.
                Optional<DeploymentFailureAnalysis> settled = analysisRepository.findByHistoryId(historyId);
                if (settled.isPresent()) {
                    return toResult(settled.get());
                }

                long startedAt = System.currentTimeMillis();
                GithubActionsPort.DeploymentLogs logs = collectLogs(ownerUserId, history);
                // F2: redact before the excerpt goes anywhere else — into the analyzer or into
                // the DB row — not just at one of the two.
                String excerpt = redact(buildExcerpt(logs.jobs(), logs.logText()));
                BuildFailureAnalyzer.Analysis ruleBased = buildFailureAnalyzer.analyze(excerpt);

                // provider/model 이 null 인 이유: 모델이 관여하지 않은 분석이다. 예전 RULE_BASED
                // 폴백 행도 같은 형태(null, null)로 저장돼 있어 스키마·조회 쪽은 그대로다.
                DeploymentFailureAnalysis analysis = new DeploymentFailureAnalysis(
                        historyId,
                        ownerUserId,
                        AnalysisSource.RULE_BASED,
                        ruleBased.userMessage(),
                        excerpt,
                        ruleBased.suggestedFix(),
                        null,
                        null
                );
                DeploymentFailureAnalysis saved = saveGuardingAgainstRace(historyId, analysis);
                log.info("배포 실패 분석 완료: historyId={} runId={} source={} excerptLength={} elapsedMs={}",
                        historyId, history.getWorkflowRunId(), saved.getSource(),
                        excerpt.length(), System.currentTimeMillis() - startedAt);
                return toResult(saved);
            }
        } finally {
            // Only remove the entry if it's still *our* lock object — if it already changed
            // (shouldn't happen given the computeIfAbsent/remove pairing here, but this keeps
            // the operation safe/idempotent rather than assuming it) this is a no-op.
            inFlightLocks.remove(historyId, lock);
        }
    }

    // ── 로그 수집 ────────────────────────────────────────────────────────────

    private GithubActionsPort.DeploymentLogs collectLogs(Long ownerUserId, DeploymentHistory history) {
        if (history.getWorkflowRunId() == null) {
            // Dispatch never happened (e.g. the workflow trigger itself failed before a run
            // existed) — there is no GitHub Actions run to fetch logs from. Fall back to
            // whatever the worker recorded when it gave up (DeploymentCommandService's retry/
            // fail path). No GitHub call at all in this branch.
            return fallbackLogsFromErrorMessage(history, null);
        }
        try {
            Project project = resolveProject(ownerUserId, history.getProjectId());
            User user = resolveUser(ownerUserId);
            return githubActionsPort.getJobLogs(
                    user.getGithubUserAccessToken(),
                    project.getSourceRepository(),
                    history.getWorkflowRunId()
            );
        } catch (RuntimeException exception) {
            // F3: GitHub Actions log retrieval can fail independently of the deployment itself
            // (rate limit, log retention expired, transient API error, revoked token) — without
            // this guard that failure propagated out of analyze() unhandled, defeating the "this
            // endpoint always returns 200 with *some* analysis" guarantee (design §3.3).
            // Degrade to the same errorMessage-based
            // input used when there's no run at all, rather than failing the whole request.
            log.warn("배포 로그 수집 실패, errorMessage 기반으로 분석 진행: historyId={} runId={} exceptionType={}",
                    history.getId(), history.getWorkflowRunId(), exception.getClass().getSimpleName());
            return fallbackLogsFromErrorMessage(history, history.getWorkflowRunId());
        }
    }

    private GithubActionsPort.DeploymentLogs fallbackLogsFromErrorMessage(DeploymentHistory history, Long runId) {
        String errorMessage = history.getErrorMessage() == null ? "" : history.getErrorMessage();
        return new GithubActionsPort.DeploymentLogs(runId, List.of(), errorMessage);
    }

    // ── 발췌 전략 (design §3.2: 최대 12,000자, 에러 라인 우선) ──────────────────

    private String buildExcerpt(List<GithubActionsPort.JobInfo> jobs, String logText) {
        StringBuilder header = new StringBuilder();
        if (jobs != null) {
            for (GithubActionsPort.JobInfo job : jobs) {
                if (!"failure".equalsIgnoreCase(job.conclusion())) {
                    continue;
                }
                header.append("=== FAILED JOB: ").append(job.name()).append(" ===\n");
                for (GithubActionsPort.StepInfo step : job.steps()) {
                    if ("failure".equalsIgnoreCase(step.conclusion())) {
                        header.append("  - FAILED STEP: ").append(step.name()).append("\n");
                    }
                }
            }
        }

        String body = logText == null ? "" : logText;
        String errorLines = extractErrorLinesWithContext(body, ERROR_LINES_BUDGET);
        int remainingBudget = MAX_EXCERPT_LENGTH - header.length() - errorLines.length();
        String tail = remainingBudget > 0 ? tail(body, remainingBudget) : "";

        String combined = header.toString() + errorLines + (tail.isEmpty() ? "" : "\n...\n" + tail);
        return truncate(combined, MAX_EXCERPT_LENGTH);
    }

    /** Matching lines plus ±{@link #CONTEXT_LINES} surrounding lines, capped at {@code budget}. */
    private String extractErrorLinesWithContext(String text, int budget) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] lines = text.split("\n", -1);
        SortedSet<Integer> selected = new TreeSet<>();
        for (int i = 0; i < lines.length; i++) {
            if (ERROR_LINE_PATTERN.matcher(lines[i]).find()) {
                for (int j = Math.max(0, i - CONTEXT_LINES); j <= Math.min(lines.length - 1, i + CONTEXT_LINES); j++) {
                    selected.add(j);
                }
            }
        }

        StringBuilder result = new StringBuilder();
        int lastAppended = -2;
        for (int index : selected) {
            if (result.length() >= budget) {
                break;
            }
            if (index != lastAppended + 1) {
                result.append("...\n");
            }
            result.append(lines[index]).append("\n");
            lastAppended = index;
        }
        return truncate(result.toString(), budget);
    }

    private String tail(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(text.length() - maxLength);
    }

    private String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    /**
     * F2: replace common token shapes with a fixed placeholder. Delegates to the shared
     * {@link SecretRedactor} (design ad-audit-log-design.md ADR-A9) rather than keeping a private
     * copy of the pattern — the audit domain applies the exact same redaction to its
     * {@code error_summary} column, and a security-critical regex should have exactly one
     * definition. Behavior is unchanged from before this delegation (same pattern, same
     * replacement token) — see {@code DeploymentFailureAnalysisServiceTest}'s redaction cases and
     * {@code SecretRedactorTest} for the shared coverage.
     */
    private static String redact(String text) {
        return SecretRedactor.redact(text);
    }

    // ── 동시성 (design §3.4) ─────────────────────────────────────────────────

    private DeploymentFailureAnalysis saveGuardingAgainstRace(Long historyId, DeploymentFailureAnalysis analysis) {
        try {
            return analysisRepository.save(analysis);
        } catch (DataIntegrityViolationException exception) {
            // Two concurrent POSTs can both pass the cache-miss check above before either
            // INSERT commits; uk_deployment_failure_analyses_history is the real guard. Re-fetch
            // and return whichever analysis the other request already persisted instead of
            // treating this as an error — the caller only wants *an* analysis for this history,
            // not necessarily the one computed by this exact request.
            return analysisRepository.findByHistoryId(historyId)
                    .orElseThrow(() -> new IllegalStateException(
                            "동시 분석 요청 처리 중 결과를 다시 조회하지 못했습니다. historyId=" + historyId, exception));
        }
    }

    // ── 소유권/조회 유틸 ──────────────────────────────────────────────────────

    private DeploymentHistory findOwnedHistory(Long ownerUserId, Long historyId) {
        DeploymentHistory history = deploymentHistoryRepository.findById(historyId)
                .orElseThrow(() -> new NotFoundException("배포 이력을 찾을 수 없습니다. historyId=" + historyId));
        // Ownership check mirrors DeploymentQueryService#getDeploymentLogs (existing precedent).
        resolveProject(ownerUserId, history.getProjectId());
        return history;
    }

    private Project resolveProject(Long ownerUserId, Long projectId) {
        return projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, ownerUserId)
                .orElseThrow(() -> new NotFoundException(
                        "프로젝트를 찾을 수 없습니다. projectId=" + projectId + ", ownerUserId=" + ownerUserId));
    }

    private User resolveUser(Long ownerUserId) {
        User user = userRepository.findById(ownerUserId)
                .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다. userId=" + ownerUserId));
        if (user.isUserAccessTokenExpired()) {
            authCommandService.refreshGithubUserToken(ownerUserId);
            user = userRepository.findById(ownerUserId)
                    .orElseThrow(() -> new IllegalStateException("유저를 찾을 수 없습니다. userId=" + ownerUserId));
        }
        return user;
    }

    private DeploymentFailureAnalysisResult toResult(DeploymentFailureAnalysis analysis) {
        return new DeploymentFailureAnalysisResult(
                analysis.getHistoryId(),
                analysis.getSummary(),
                analysis.getLogExcerpt(),
                analysis.getSuggestedFix(),
                analysis.getSource().name(),
                analysis.getCreatedAt()
        );
    }
}
