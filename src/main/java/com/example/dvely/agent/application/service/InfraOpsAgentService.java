package com.example.dvely.agent.application.service;

import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.service.CodeAgentService.CodeResult;
import com.example.dvely.agent.domain.value.InfraOperation;
import com.example.dvely.agent.infrastructure.docker.ContainerResourceUsage;
import com.example.dvely.agent.infrastructure.docker.ContainerRuntimeStatus;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.deployment.application.query.DeploymentQueryService;
import com.example.dvely.deployment.application.result.DeploymentFailureAnalysisResult;
import com.example.dvely.deployment.application.result.DeploymentHistoryResult;
import com.example.dvely.deployment.application.result.DeploymentLogsResult;
import com.example.dvely.deployment.application.service.DeploymentFailureAnalysisService;
import com.example.dvely.preview.application.result.PreviewSessionInfo;
import com.example.dvely.preview.application.service.PreviewSessionService;
import com.example.dvely.project.domain.model.ProjectApprovalPolicy;
import com.example.dvely.project.domain.repository.ProjectApprovalPolicyRepository;
import com.example.dvely.project.domain.repository.ProjectCloudConnectionSettingRepository;
import com.example.dvely.project.domain.repository.ProjectInfrastructureSettingRepository;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Executes {@code AgentType.INFRA_OPERATE} steps (Cloud Ops Agent, EPIC 15 design §3). This is the
 * only agent service that reaches this code with zero information about *which* resource to act
 * on beyond a {@code projectId} — the LLM-supplied {@code operation} name is looked up in
 * {@link InfraOperation}'s whitelist and the actual target (preview container, deployment history)
 * is always re-resolved here from the DB under {@code (projectId, ownerUserId)}, never taken from
 * step parameters. That is the entire prompt-injection defense for this feature: there is no
 * parameter path from the LLM into a container id or history id (design D3).
 *
 * <p>Deliberately NOT {@code @Transactional}: STATUS_CHECK/LOG_VIEW fan out to Docker + (via
 * {@link DeploymentQueryService}) GitHub Actions calls, and RESTART does a Docker exec — holding a
 * DB transaction open across that I/O would tie up a connection pool slot for no benefit, mirroring
 * {@code PreviewContainerOpsService}'s and {@code DeploymentFailureAnalysisService}'s existing
 * decision. Each repository call below runs in its own short, adapter-level transaction.</p>
 *
 * <p>Read-only operations (STATUS_CHECK/LOG_VIEW) are answered from a fixed template, not the LLM
 * (design D6) — summarizing infrastructure state is a "list known facts" task, and letting an LLM
 * phrase it risks a hallucinated "정상입니다" that isn't backed by anything actually checked.
 * FAILURE_ANALYSIS is the one exception, reusing {@link DeploymentFailureAnalysisService} (U6)
 * as-is rather than duplicating its LLM+rule-based-fallback logic here.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InfraOpsAgentService {

    /** §4.7 fixed guidance for an operation the catalog couldn't identify (missing/typo'd/unsupported string). */
    private static final String OPERATION_NOT_IDENTIFIED_MESSAGE =
            "요청을 인프라 운영 작업으로 특정하지 못했습니다. 예: '서버 상태 알려줘', '배포 로그 보여줘', "
                    + "'왜 실패했는지 분석해줘', 'preview 재시작해줘'.";

    // §3.2: chat bubbles are read in a message list, not a log viewer — capped far below the
    // preview HTTP API's tail=200 default so a single infra-ops answer stays skimmable.
    private static final int LOG_TAIL_LINES = 50;
    private static final int LOG_MAX_CHARS = 2000;

    private final ProjectRepository projectRepository;
    private final DeploymentQueryService deploymentQueryService;
    private final DeploymentFailureAnalysisService deploymentFailureAnalysisService;
    private final PreviewSessionService previewSessionService;
    private final DockerContainerService dockerService;
    private final ProjectCloudConnectionSettingRepository cloudConnectionSettingRepository;
    private final CloudConnectionRepository cloudConnectionRepository;
    private final ProjectInfrastructureSettingRepository infrastructureSettingRepository;
    // Not part of the design doc's initial dependency list (§3 preamble), but required by §3.4's
    // RESTART policy-off warning, which explicitly calls for "the same policyRepository lookup as
    // the orchestrator" — added here rather than threading the orchestrator's already-computed
    // policy through AgentStep/AgentPlan, which would leak an approval-layer concept into the
    // plan DTO shared by every agent type.
    private final ProjectApprovalPolicyRepository policyRepository;

    public CodeResult execute(AgentStep step, Long userId, String taskId, Long projectId) {
        String rawOperation = step.parameters().get("operation");
        log.info("[InfraOpsAgent] 인프라 운영 요청 수신 | userId={} taskId={} projectId={} operation={}",
                userId, taskId, projectId, rawOperation);

        // (1) No project in context — this cannot be a normal task failure (there was never a
        // resource to act on), so it ends the same way a missing operation does: a guidance
        // reply, not markFailed.
        if (projectId == null) {
            return new CodeResult(null, "인프라 운영 작업은 프로젝트 대화에서 요청해주세요.");
        }

        // (2) Ownership check — identical shape to DeployAgentService/DomainBindAgentService:
        // absence here is a genuine failure (stale/foreign projectId), not a guidance case.
        projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, userId)
                .orElseThrow(() -> new IllegalStateException("프로젝트를 찾을 수 없습니다. projectId=" + projectId));

        // (3) Catalog lookup — see class javadoc: this is the only place an LLM-supplied string
        // is interpreted, and it is interpreted only as a name, never as a target.
        Optional<InfraOperation> operationOpt = InfraOperation.parse(rawOperation);
        if (operationOpt.isEmpty()) {
            return new CodeResult(null, OPERATION_NOT_IDENTIFIED_MESSAGE);
        }
        InfraOperation operation = operationOpt.get();

        String summary = switch (operation) {
            case STATUS_CHECK -> statusCheck(userId, projectId);
            case LOG_VIEW -> logView(userId, projectId);
            case FAILURE_ANALYSIS -> failureAnalysis(userId, projectId);
            // Reaching RESTART means AgentPlanExecutor only dispatched here after any required
            // approval already completed (same contract DeployAgentService relies on) — this
            // method does not itself gate on approval, it re-derives whether the policy was OFF
            // purely to decide the response's warning prefix (design §3.4).
            case RESTART -> restart(projectId, userId, policyWasOff(projectId, operation));
            case RESOURCE_SCALING, AUTOSCALING_CHANGE, RESOURCE_CLEANUP -> unsupported(operation);
        };
        log.info("[InfraOpsAgent] 인프라 운영 작업 완료 | operation={} projectId={}", operation, projectId);
        return new CodeResult(null, summary);
    }

    // ── 3.1 STATUS_CHECK — deterministic template, each row degrades independently ────────────

    private String statusCheck(Long userId, Long projectId) {
        StringBuilder report = new StringBuilder("서버/서비스 상태\n");
        report.append("- 배포: ").append(section(() ->
                describeLatestDeployment(deploymentQueryService.getDeploymentHistories(userId, projectId)))).append('\n');
        report.append("- Preview: ").append(section(() -> describePreview(projectId, userId))).append('\n');
        report.append("- 클라우드 연결: ").append(section(() -> describeCloudConnection(projectId, userId))).append('\n');
        report.append("- 인프라 설정: ").append(section(() -> describeInfrastructureSetting(projectId))).append('\n');
        // Fixed line, always present regardless of the other rows (design D5): connection/setting
        // rows describe *desired* state only — there is no cloud SDK call behind this feature that
        // could ever contradict this sentence with a real resource.
        report.append("- 프로비저닝: 저장된 인프라 설정의 실 클라우드 리소스는 아직 프로비저닝되지 않았습니다.");
        return report.toString();
    }

    /** Wraps one status row so a Docker/GitHub hiccup degrades only that row (design §4.2), not the whole reply. */
    private String section(java.util.function.Supplier<String> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            log.warn("[InfraOpsAgent] 상태 조회 중 한 항목 확인 실패, degrade 처리: exceptionType={}", e.getClass().getSimpleName());
            return "확인 불가";
        }
    }

    private String describeLatestDeployment(List<DeploymentHistoryResult> histories) {
        if (histories.isEmpty()) {
            return "배포 이력 없음";
        }
        DeploymentHistoryResult latest = histories.get(0);
        return String.format("상태 %s, 버전 %s, URL %s",
                latest.status(),
                blankToDash(latest.versionLabel()),
                blankToDash(latest.deployedUrl()));
    }

    private String describePreview(Long projectId, Long userId) {
        return findActiveSession(projectId, userId)
                .map(session -> {
                    ContainerRuntimeStatus runtime = dockerService.getContainerStatus(session.containerId());
                    if (!runtime.running()) {
                        return "실행 중 아님 (preview 세션은 있으나 컨테이너가 중지됨)";
                    }
                    String resource = dockerService.getContainerStats(session.containerId())
                            .map(this::describeResourceUsage)
                            .orElse("리소스 사용량 확인 불가");
                    return "실행 중 (" + resource + ")";
                })
                .orElse("실행 중인 preview 없음");
    }

    private String describeResourceUsage(ContainerResourceUsage usage) {
        return String.format("CPU %.1f%%, 메모리 %dMB/%dMB",
                usage.cpuPercent(),
                usage.memoryUsageBytes() / (1024 * 1024),
                usage.memoryLimitBytes() / (1024 * 1024));
    }

    private String describeCloudConnection(Long projectId, Long userId) {
        return cloudConnectionSettingRepository.findByProjectId(projectId)
                .flatMap(setting -> cloudConnectionRepository.findByIdAndOwnerUserId(setting.getCloudConnectionId(), userId))
                .map(connection -> connection.getProvider() + " / " + connection.getRegion() + " / " + connection.getStatus())
                .orElse("미연결");
    }

    private String describeInfrastructureSetting(Long projectId) {
        return infrastructureSettingRepository.findByProjectId(projectId)
                .map(setting -> setting.getConfiguration().summaryText())
                .orElse("미설정");
    }

    // ── 3.2 LOG_VIEW — up to two sources, each truncated independently, never logged server-side ──

    private String logView(Long userId, Long projectId) {
        List<String> sections = new ArrayList<>();

        findActiveSession(projectId, userId).ifPresent(session -> {
            try {
                // Docker's own `tail` already limits this to the last LOG_TAIL_LINES lines —
                // the char cap below only guards against those lines being unusually long.
                String logText = dockerService.getContainerLogs(session.containerId(), LOG_TAIL_LINES, null);
                sections.add("[Preview 로그]\n" + truncateForChat(logText));
            } catch (RuntimeException e) {
                log.warn("[InfraOpsAgent] preview 로그 조회 실패, degrade 처리: projectId={} exceptionType={}",
                        projectId, e.getClass().getSimpleName());
                sections.add("[Preview 로그]\n확인 불가");
            }
        });

        // Review Medium (x-cloudops-review.md): this call was previously unguarded while the
        // preview-log fetch above it already had per-section degrade — a GitHub Actions hiccup
        // here threw past this point and discarded the [Preview 로그] section already computed
        // above, escalating a partial-availability situation into a full task FAILED. Now
        // symmetric with statusCheck()'s section() helper: a failure here degrades only the
        // [배포 로그] row instead of the whole response.
        List<DeploymentHistoryResult> histories;
        try {
            histories = deploymentQueryService.getDeploymentHistories(userId, projectId);
        } catch (RuntimeException e) {
            log.warn("[InfraOpsAgent] 배포 이력 조회 실패, degrade 처리: projectId={} exceptionType={}",
                    projectId, e.getClass().getSimpleName());
            sections.add("[배포 로그]\n확인 불가");
            histories = List.of();
        }
        if (!histories.isEmpty()) {
            Long historyId = histories.get(0).historyId();
            try {
                // Already-redacted, U6-vetted path (DeploymentQueryService -> GithubActionsPort) —
                // no separate redaction needed here, unlike DeploymentFailureAnalysisService which
                // sends this kind of text onward to an LLM.
                DeploymentLogsResult logs = deploymentQueryService.getDeploymentLogs(userId, historyId);
                sections.add("[배포 로그]\n" + truncateForChat(lastLines(logs.logText(), LOG_TAIL_LINES)));
            } catch (RuntimeException e) {
                log.warn("[InfraOpsAgent] 배포 로그 조회 실패, degrade 처리: projectId={} historyId={} exceptionType={}",
                        projectId, historyId, e.getClass().getSimpleName());
                sections.add("[배포 로그]\n확인 불가");
            }
        }

        if (sections.isEmpty()) {
            return "조회할 로그가 없습니다(실행 중 preview·배포 이력 없음).";
        }
        sections.add("전체 로그는 Preview/Deployment 화면에서 확인할 수 있습니다.");
        return String.join("\n\n", sections);
    }

    private String truncateForChat(String text) {
        if (text == null || text.isBlank()) {
            return "(로그 없음)";
        }
        String trimmed = text.strip();
        return trimmed.length() <= LOG_MAX_CHARS ? trimmed : trimmed.substring(0, LOG_MAX_CHARS) + "\n... (생략)";
    }

    private String lastLines(String text, int maxLines) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] lines = text.split("\n", -1);
        int start = Math.max(0, lines.length - maxLines);
        return String.join("\n", Arrays.copyOfRange(lines, start, lines.length));
    }

    // ── 3.3 FAILURE_ANALYSIS — U6 analysis reused as-is; no new analysis logic here ────────────

    private String failureAnalysis(Long userId, Long projectId) {
        // Review Medium (x-cloudops-review.md): unlike logView()'s equivalent call, this one is
        // deliberately left unguarded — FAILURE_ANALYSIS has no meaningful degrade target without
        // a history to analyze (there is no "analysis: 확인 불가" that would be useful), so a hard
        // failure here (-> task FAILED, same as every other agent service's repository read) is
        // more honest than fabricating a "최근 실패한 배포가 없습니다" answer we can't actually confirm.
        List<DeploymentHistoryResult> histories = deploymentQueryService.getDeploymentHistories(userId, projectId);
        Optional<DeploymentHistoryResult> latest = histories.isEmpty() ? Optional.empty() : Optional.of(histories.get(0));

        String body;
        if (latest.isEmpty() || !DeployStatus.FAILED.name().equals(latest.get().status())) {
            body = "최근 실패한 배포가 없습니다.\n- 배포: " + describeLatestDeployment(histories);
        } else {
            DeploymentFailureAnalysisResult analysis =
                    deploymentFailureAnalysisService.analyze(userId, latest.get().historyId());
            body = "배포 실패 원인 분석\n- 원인: " + analysis.summary() + "\n- 제안된 수정: " + analysis.suggestedFix();
        }
        return body + previewCrashNote(projectId, userId);
    }

    /**
     * Notes a stopped-but-still-ACTIVE preview container without analyzing it (crash analysis is
     * non-goal, §3.3). Wrapped in try/catch (review Medium, x-cloudops-review.md): this is a
     * secondary annotation appended to the already-computed {@code failureAnalysis()} body (either
     * a real analysis or a "no failed deployment" message) — a lookup failure here must degrade to
     * "no note added" rather than propagate and discard that body. {@code isContainerRunning}
     * itself already swallows all Docker exceptions internally (never throws), but
     * {@code findActiveSession} still reads through JPA, so this stays defensive rather than
     * relying on that internal detail.
     */
    private String previewCrashNote(Long projectId, Long userId) {
        try {
            return findActiveSession(projectId, userId)
                    .filter(session -> !dockerService.isContainerRunning(session.containerId()))
                    .map(session -> "\n- 참고: preview 컨테이너가 존재하지만 현재 중지 상태입니다.")
                    .orElse("");
        } catch (RuntimeException e) {
            log.warn("[InfraOpsAgent] preview 상태 부가 확인 실패, degrade 처리: projectId={} exceptionType={}",
                    projectId, e.getClass().getSimpleName());
            return "";
        }
    }

    // ── 3.4 RESTART — the only mutating operation; target is always the DB-resolved ACTIVE session ──

    private String restart(Long projectId, Long userId, boolean policyWasOff) {
        Optional<PreviewSessionInfo> session = findActiveSession(projectId, userId);
        String body;
        if (session.isEmpty()) {
            // Not a failure (design D8) — TTL expiry between approval and execution, or no
            // preview ever created for this project, are both normal outcomes here.
            body = "재시작할 실행 중인 서버가 없습니다. GitHub Pages는 정적 호스팅이라 재시작 개념이 없고"
                    + "(재배포는 '배포해줘'로 요청), 클라우드 서버는 아직 프로비저닝 전입니다.";
        } else {
            String containerId = session.get().containerId();
            // Propagates uncaught on Docker NotFound (container removed out-of-band between the
            // findActiveSession read above and this call) — the design's error table (§4.2) calls
            // that a genuine failure, not a guidance case, since the DB said ACTIVE moments ago.
            dockerService.restartContainer(containerId);
            log.info("[InfraOpsAgent] preview 컨테이너 재시작 완료 | containerId={} projectId={}", containerId, projectId);
            // Review Medium (x-cloudops-review.md): restartContainer() above already succeeded and
            // mutated real state. getContainerStatus only catches NotFoundException internally, so
            // any other Docker hiccup on this immediately-following re-check would otherwise turn
            // an already-completed restart into a task FAILED. Degrade only this status line.
            String statusLine;
            try {
                ContainerRuntimeStatus status = dockerService.getContainerStatus(containerId);
                statusLine = status.running() ? "정상 실행 중" : "재시작 직후 확인 중 — 잠시 후 다시 확인해주세요";
            } catch (RuntimeException e) {
                log.warn("[InfraOpsAgent] 재시작 후 상태 재확인 실패, degrade 처리: containerId={} projectId={} exceptionType={}",
                        containerId, projectId, e.getClass().getSimpleName());
                statusLine = "확인 불가(재시작 자체는 완료됨)";
            }
            body = "preview 서버를 재시작했습니다.\n- URL: " + session.get().publicUrl()
                    + "\n- 실행 상태: " + statusLine;
        }
        return policyWasOff
                ? InfraOperation.RESTART.impactMarkers() + "승인 정책이 꺼져 있어 즉시 실행했습니다.\n" + body
                : body;
    }

    /**
     * Re-derives, at execution time, whether {@code operation} would have required approval but the
     * project's policy is OFF — so the response can carry the same warning the orchestrator would
     * have skipped creating an Approval for (design §3.4/D4). Deliberately re-computed from the
     * catalog + policy rather than inspecting whether an Approval row exists for this task: a
     * missing Approval is ambiguous (could mean "policy OFF" or "read-only op, no approval needed
     * at all"), while this recomputation is unambiguous and matches
     * {@code AgentOrchestrator.createRequiredApprovals}'s own gate exactly.
     */
    private boolean policyWasOff(Long projectId, InfraOperation operation) {
        if (!operation.approvalRequired()) {
            return false;
        }
        ProjectApprovalPolicy policy = policyRepository.findByProjectId(projectId)
                .orElseGet(() -> new ProjectApprovalPolicy(projectId));
        return !policy.requires(ApprovalType.INFRA_OPERATION);
    }

    // ── 3.5 Unsupported ops — BI-176/177 detection surfaced as guidance, never approval ─────────

    private String unsupported(InfraOperation operation) {
        String markers = operation.impactMarkers();
        return switch (operation) {
            case RESOURCE_SCALING -> markers + "리소스 스펙 변경은 아직 대화로 실행할 수 없습니다. "
                    + "Project Settings > Cloud Infrastructure에서 컴퓨팅 티어를 변경해주세요 — "
                    + "변경은 승인 정책에 따라 INFRA_OPERATION 승인을 거칩니다.";
            case AUTOSCALING_CHANGE -> markers + "오토스케일링 설정은 아직 지원하지 않습니다.";
            case RESOURCE_CLEANUP -> markers + "리소스 정리는 비용 분석 기능과 함께 제공될 예정입니다.";
            default -> throw new IllegalStateException("지원하지 않는 분기: " + operation);
        };
    }

    // ── 공통 유틸 ────────────────────────────────────────────────────────────────────────────

    private Optional<PreviewSessionInfo> findActiveSession(Long projectId, Long userId) {
        return previewSessionService.findActiveByProject(projectId, userId);
    }

    private String blankToDash(String value) {
        return value == null || value.isBlank() ? "없음" : value;
    }
}
