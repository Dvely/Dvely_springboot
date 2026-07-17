package com.example.dvely.deployment.application.command;

import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.common.exception.ForbiddenException;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.deployment.application.command.dto.DeployCommand;
import com.example.dvely.deployment.application.port.out.GithubActionsPort;
import com.example.dvely.deployment.application.port.out.GithubActionsPort.WorkflowRunMatch;
import com.example.dvely.deployment.application.port.out.GithubPagesPort;
import com.example.dvely.deployment.application.port.out.GithubRepoPort;
import com.example.dvely.deployment.application.port.out.GithubRepoPort.ReleaseMetadata;
import com.example.dvely.deployment.application.result.DeployResult;
import com.example.dvely.deployment.domain.model.DeploymentHistory;
import com.example.dvely.deployment.domain.repository.DeploymentHistoryRepository;
import com.example.dvely.deployment.domain.value.DeployTargetType;
import com.example.dvely.deployment.domain.value.PackageManager;
import com.example.dvely.deployment.infrastructure.workflow.DeployWorkflowTemplate;
import com.example.dvely.project.domain.exception.ProjectNotFoundException;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeploymentCommandService {

    private static final String PAGES_BRANCH = "gh-pages";
    private static final String RELEASE_BRANCH_PREFIX = "release/";
    private static final String PREVIEW_BRANCH = "preview";
    private static final String MAIN_BRANCH = "main";
    private static final int WORKFLOW_TRIGGER_MAX_RETRY = 5;
    private static final long WORKFLOW_TRIGGER_RETRY_INTERVAL_MS = 3000;

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final AuthCommandService authCommandService;
    private final GithubPagesPort githubPagesPort;
    private final GithubActionsPort githubActionsPort;
    private final GithubRepoPort githubRepoPort;
    private final DeploymentHistoryRepository deploymentHistoryRepository;

    @Transactional
    public DeployResult deploy(Long ownerUserId, Long projectId, DeployCommand command) {
        Project project = resolveProject(ownerUserId, projectId);
        return createAndQueueDeployment(ownerUserId, project, command, null);
    }

    /**
     * Re-queues a failed deployment as a brand-new job rather than resetting the failed row in
     * place (U6 design D3): reusing the same history would collide with its own
     * {@code correlationId}/workflow-run identity and would erase the failed attempt from the
     * audit trail. The new row links back via {@code retriedFromHistoryId} so the FE can render
     * a retry chain, and otherwise flows through the exact same PENDING → worker-lease pipeline
     * as a fresh {@link #deploy}.
     *
     * <p>No approval gate here (D5): {@code POST /deployments} itself never requires DEPLOYMENT
     * approval for direct user action (that ApprovalType is only created by the agent planning
     * path via AgentOrchestrator) — retry is the same kind of direct action, so the click itself
     * is the approval.</p>
     *
     * <p><b>Accepted risk (review follow-up F5, design D3/§7 — deliberate, not an oversight):</b>
     * there is no limit on how many times a given history can be retried, and no check for an
     * already in-flight PENDING/IN_PROGRESS job on the same project before queuing another one.
     * Design §7 explicitly scopes both out ("재시도 횟수 제한·rate limit", "진행 중 job 존재 시
     * 차단") as MVP simplifications, and D3 argues the same asymmetry already exists for the
     * plain {@code POST /projects/{id}/deployments} endpoint (which also queues unconditionally
     * regardless of concurrent jobs) — making retry strict here while the primary deploy path
     * isn't would be an inconsistent, surprising restriction rather than a safety improvement.
     * If abuse or accidental repeated retries become a real problem, the fix belongs at this
     * call site (e.g. a per-project in-flight check, or a retry-count/backoff column on
     * {@code DeploymentHistory}) — not a broader change to the shared queuing pipeline.</p>
     */
    @Transactional
    public DeployResult retryDeployment(Long ownerUserId, Long historyId) {
        DeploymentHistory target = deploymentHistoryRepository.findById(historyId)
                .orElseThrow(() -> new NotFoundException("배포 이력을 찾을 수 없습니다. historyId=" + historyId));
        Project project = resolveProject(ownerUserId, target.getProjectId());
        if (target.getStatus() != DeployStatus.FAILED) {
            throw new IllegalStateException("실패한 배포만 재시도할 수 있습니다. status=" + target.getStatus());
        }

        String requestedVersion = target.getDeployTargetType() == DeployTargetType.VERSION
                ? target.getVersionLabel()
                : null;
        // taskId=null: unlike agent-triggered deploys, a retry is a direct user click, not
        // something an Agent plan is tracking.
        DeployCommand command = new DeployCommand(target.getDeployTargetType(), requestedVersion, null);
        return createAndQueueDeployment(ownerUserId, project, command, target.getId());
    }

    private DeployResult createAndQueueDeployment(Long ownerUserId,
                                                  Project project,
                                                  DeployCommand command,
                                                  Long retriedFromHistoryId) {
        DeploymentHistory history = deploymentHistoryRepository.save(new DeploymentHistory(
                ownerUserId,
                project.getId(),
                command.deployTargetType(),
                command.versionName(),
                command.taskId(),
                retriedFromHistoryId
        ));
        project.updateDeployment(DeployStatus.PENDING, project.getCurrentUrl(), project.getCurrentVersion());
        projectRepository.save(project);
        log.info("배포 요청 저장: projectId={} historyId={} correlationId={} retriedFromHistoryId={}",
                project.getId(), history.getId(), history.getCorrelationId(), retriedFromHistoryId);
        return toResult(history);
    }

    @Async("deploymentExecutor")
    public void executeQueued(Long historyId) {
        try {
            execute(historyId);
        } catch (Exception exception) {
            handleExecutionFailure(historyId, exception);
        }
    }

    public void execute(Long historyId) {
        DeploymentHistory history = deploymentHistoryRepository.findById(historyId)
                .orElseThrow(() -> new IllegalStateException("배포 이력을 찾을 수 없습니다. historyId=" + historyId));
        if (history.getStatus() != DeployStatus.IN_PROGRESS || history.getLeaseOwner() == null) {
            return;
        }

        Project project = resolveProject(history.getOwnerUserId(), history.getProjectId());
        User user = resolveUser(history.getOwnerUserId());
        String userToken = user.getGithubUserAccessToken();
        String sourceRepo = project.getSourceRepository();
        String deploymentRepo = project.getDeploymentRepository();

        ensureWorkflow(userToken, sourceRepo, project.getTemplateType());

        ReleaseSelection release = prepareRelease(userToken, sourceRepo, history);
        String deployBranch = resolveDeployBranch(
                userToken,
                deploymentRepo,
                history.getDeployTargetType(),
                release.versionLabel()
        );
        String pagesUrl = deployToPages(userToken, deploymentRepo, deployBranch);
        String workflowHeadSha = githubRepoPort.getHeadCommitSha(
                userToken,
                sourceRepo,
                MAIN_BRANCH
        );

        history.prepare(
                release.versionLabel(),
                pagesUrl,
                workflowHeadSha,
                release.metadata()
        );
        history = deploymentHistoryRepository.save(history);
        if (isLatestProjectDeployment(history)) {
            updateProjectDeploymentState(project.getId(), DeployStatus.IN_PROGRESS, pagesUrl, release.versionLabel());
        }

        WorkflowRunMatch existingRun = githubActionsPort.findWorkflowRun(
                userToken,
                sourceRepo,
                DeployWorkflowTemplate.fileName(),
                history.getCorrelationId(),
                workflowHeadSha,
                history.getTriggeredAt()
        );
        if (existingRun.runId() != null) {
            finishDispatch(history, existingRun);
            return;
        }

        String checkoutRef = history.getDeployTargetType() == DeployTargetType.LATEST
                ? MAIN_BRANCH
                : release.versionLabel();
        triggerWithRetry(
                userToken,
                sourceRepo,
                DeployWorkflowTemplate.fileName(),
                MAIN_BRANCH,
                checkoutRef,
                history.getCorrelationId()
        );

        WorkflowRunMatch run = githubActionsPort.pollWorkflowRun(
                userToken,
                sourceRepo,
                DeployWorkflowTemplate.fileName(),
                history.getCorrelationId(),
                workflowHeadSha,
                history.getTriggeredAt(),
                5,
                3000
        );
        finishDispatch(history, run);
    }

    private void finishDispatch(DeploymentHistory history, WorkflowRunMatch run) {
        history.markDispatched(run.runId(), run.headSha());
        deploymentHistoryRepository.save(history);
        log.info("배포 workflow 연결: historyId={} runId={} correlationId={} version={}",
                history.getId(), run.runId(), history.getCorrelationId(), history.getVersionLabel());
    }

    /**
     * I45 (#45) review follow-up F1: {@code execute()} is deliberately not
     * {@code @Transactional} (design §0 case B — see {@code ProjectRepositoryAdapter}'s javadoc),
     * so it originally reused the {@code Project} snapshot read at the very top of
     * {@code execute()} for this save. Between that read and this write, this same method's own
     * GitHub I/O runs — {@code ensureWorkflow} commits the workflow file, {@code prepareRelease}
     * can merge a PR — both of which are pushes to the project's own repository that trigger
     * {@code WebhookEventHandler}'s push handling against this exact project row. That made this
     * a <em>self-inflicted</em> race: on a completely normal, uncontested deploy, the adapter's
     * version guard would frequently catch the webhook's commit landing first and reject this
     * save, burning one retry attempt (5s+ backoff) every time — not a rare concurrent-user edge
     * case but a routine tax on the deploy pipeline's own success path.
     * <p>
     * Re-reading the project immediately before this write shrinks that race window from "the
     * entire GitHub I/O phase of execute()" down to milliseconds. A concurrent version conflict
     * is still possible in that narrow window (e.g. a true concurrent user edit) — that still
     * throws {@link ObjectOptimisticLockingFailureException} up through {@code executeQueued}'s
     * catch to {@link #handleExecutionFailure}, i.e. into the existing worker-retry machine
     * exactly as design §2 intends, unaffected by this fix.
     */
    private void updateProjectDeploymentState(Long projectId, DeployStatus status, String url, String version) {
        Optional<Project> fresh = projectRepository.findById(projectId);
        if (fresh.isEmpty()) {
            // Project was deleted concurrently — the deployment history itself is already saved
            // and unaffected; there is simply nothing left to mirror this status onto.
            log.warn("배포 상태 프로젝트 반영 스킵: 프로젝트를 찾을 수 없습니다. projectId={}", projectId);
            return;
        }
        Project project = fresh.get();
        project.updateDeployment(status, url, version);
        projectRepository.save(project);
    }

    private void handleExecutionFailure(Long historyId, Exception exception) {
        DeploymentHistory history = deploymentHistoryRepository.findById(historyId).orElse(null);
        if (history == null || history.getStatus() == DeployStatus.LIVE) {
            return;
        }
        String message = exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
        history.retry(message, Duration.ofSeconds(Math.max(5, history.getAttempt() * 5L)));
        deploymentHistoryRepository.save(history);
        if (history.getStatus() == DeployStatus.FAILED && isLatestProjectDeployment(history)) {
            try {
                projectRepository.findById(history.getProjectId()).ifPresent(project -> {
                    project.updateDeployment(
                            DeployStatus.FAILED,
                            history.getDeployedUrl(),
                            history.getVersionLabel()
                    );
                    projectRepository.save(project);
                });
            } catch (ObjectOptimisticLockingFailureException lockException) {
                // F2 (design §2 "로그 후 소실 허용"): the deployment history's retry/fail state
                // was already saved above — that (not this best-effort project mirror) is the
                // durable source of truth a later webhook/retry converges from. A version
                // conflict here is not worth crashing this async worker over: caught, logged,
                // and left for the next successful project save/webhook to catch the row up.
                // The log.error(...) below (the actual failure cause) still runs either way —
                // this catch exists precisely so it isn't skipped by an uncaught exception here.
                log.warn("배포 실패 처리 중 프로젝트 상태 반영이 버전 경합으로 무시됨: historyId={} projectId={}",
                        historyId, history.getProjectId(), lockException);
            }
        }
        log.error("배포 worker 실행 실패: historyId={} attempt={}/{} status={}",
                historyId, history.getAttempt(), history.getMaxAttempts(), history.getStatus(), exception);
    }

    private boolean isLatestProjectDeployment(DeploymentHistory history) {
        return deploymentHistoryRepository.findLatestByProjectId(history.getProjectId())
                .map(latest -> latest.getId().equals(history.getId()))
                .orElse(false);
    }

    private ReleaseSelection prepareRelease(String userToken,
                                            String sourceRepo,
                                            DeploymentHistory history) {
        if (history.getDeployTargetType() == DeployTargetType.VERSION) {
            String commitSha = githubRepoPort.resolveCommitSha(
                    userToken,
                    sourceRepo,
                    history.getVersionLabel()
            );
            ReleaseMetadata metadata = githubRepoPort.getReleaseMetadata(
                    userToken,
                    sourceRepo,
                    commitSha,
                    null
            );
            return new ReleaseSelection(history.getVersionLabel(), metadata);
        }

        Integer prNumber = null;
        if (githubRepoPort.hasNewCommits(userToken, sourceRepo, MAIN_BRANCH, PREVIEW_BRANCH)) {
            prNumber = githubRepoPort.createOrGetPullRequest(
                    userToken,
                    sourceRepo,
                    PREVIEW_BRANCH,
                    MAIN_BRANCH,
                    "[Qeploy] Deploy preview to main"
            );
            githubRepoPort.mergePullRequest(userToken, sourceRepo, prNumber);
        }

        String mainSha = githubRepoPort.getHeadCommitSha(userToken, sourceRepo, MAIN_BRANCH);
        String versionLabel = githubRepoPort.findSequentialTagForCommit(userToken, sourceRepo, mainSha);
        if (versionLabel == null) {
            versionLabel = githubRepoPort.createNextSequentialTag(userToken, sourceRepo, mainSha);
        }
        ReleaseMetadata metadata = githubRepoPort.getReleaseMetadata(
                userToken,
                sourceRepo,
                mainSha,
                prNumber
        );
        return new ReleaseSelection(versionLabel, metadata);
    }

    private Project resolveProject(Long ownerUserId, Long projectId) {
        Project project = projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, ownerUserId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId, ownerUserId));
        if (project.getRepositoryBindingStatus() != RepositoryBindingStatus.BOUND) {
            throw new ForbiddenException(
                    "GitHub 저장소가 연결되지 않은 프로젝트입니다. 먼저 저장소를 연결해 주세요. projectId=" + projectId);
        }
        if (project.getDeploymentRepository() == null || project.getDeploymentRepository().isBlank()) {
            throw new ForbiddenException("배포 저장소가 설정되지 않은 프로젝트입니다. projectId=" + projectId);
        }
        if (project.getSourceRepository() == null || project.getSourceRepository().isBlank()) {
            throw new ForbiddenException("소스 저장소가 설정되지 않은 프로젝트입니다. projectId=" + projectId);
        }
        return project;
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

    private String resolveDeployBranch(String userToken,
                                       String deploymentRepo,
                                       DeployTargetType deployTargetType,
                                       String versionLabel) {
        if (deployTargetType == DeployTargetType.LATEST) {
            return PAGES_BRANCH;
        }
        String branchName = RELEASE_BRANCH_PREFIX + versionLabel;
        return githubPagesPort.createBranchFromTag(
                userToken,
                deploymentRepo,
                versionLabel,
                branchName
        );
    }

    private String deployToPages(String userToken, String deploymentRepo, String branch) {
        GithubPagesPort.PagesInfo pagesInfo = githubPagesPort.getPages(userToken, deploymentRepo);
        if (!pagesInfo.enabled()) {
            return githubPagesPort.enablePages(userToken, deploymentRepo, branch);
        }
        if (branch.equals(pagesInfo.sourceBranch())) {
            return pagesInfo.url();
        }
        return githubPagesPort.updatePagesSource(
                userToken,
                deploymentRepo,
                branch,
                pagesInfo.customDomain()
        );
    }

    private void ensureWorkflow(String userToken, String sourceRepo, String templateType) {
        PackageManager packageManager = githubRepoPort.detectPackageManager(userToken, sourceRepo);
        String nodeVersion = githubRepoPort.detectNodeVersion(userToken, sourceRepo);
        String detectedType = githubRepoPort.detectFrameworkType(userToken, sourceRepo);
        String resolvedType = detectedType != null ? detectedType : templateType;
        String content = DeployWorkflowTemplate.generate(
                resolvedType,
                null,
                packageManager,
                nodeVersion
        );
        githubActionsPort.createOrUpdateWorkflow(
                userToken,
                sourceRepo,
                DeployWorkflowTemplate.fileName(),
                content
        );
    }

    private void triggerWithRetry(String userToken,
                                  String sourceRepo,
                                  String workflowFile,
                                  String dispatchRef,
                                  String checkoutRef,
                                  String correlationId) {
        for (int attempt = 1; attempt <= WORKFLOW_TRIGGER_MAX_RETRY; attempt++) {
            try {
                githubActionsPort.triggerWorkflow(
                        userToken,
                        sourceRepo,
                        workflowFile,
                        dispatchRef,
                        checkoutRef,
                        correlationId
                );
                return;
            } catch (IllegalStateException exception) {
                if (attempt == WORKFLOW_TRIGGER_MAX_RETRY) {
                    throw new IllegalStateException(
                            "워크플로우 트리거 실패 (" + WORKFLOW_TRIGGER_MAX_RETRY + "회 재시도 초과): "
                                    + exception.getMessage(),
                            exception
                    );
                }
                sleep(WORKFLOW_TRIGGER_RETRY_INTERVAL_MS);
            }
        }
    }

    private void sleep(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("워크플로우 트리거 대기 중 인터럽트 발생", exception);
        }
    }

    private DeployResult toResult(DeploymentHistory history) {
        return new DeployResult(
                history.getId(),
                history.getProjectId(),
                history.getDeployTargetType().name(),
                history.getVersionLabel(),
                history.getStatus().name(),
                history.getDeployedUrl(),
                history.getTriggeredAt()
        );
    }

    private record ReleaseSelection(
            String versionLabel,
            ReleaseMetadata metadata
    ) {}
}
