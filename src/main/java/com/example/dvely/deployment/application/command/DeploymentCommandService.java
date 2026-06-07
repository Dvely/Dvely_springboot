package com.example.dvely.deployment.application.command;

import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.deployment.application.command.dto.DeployCommand;
import com.example.dvely.deployment.application.port.out.GithubActionsPort;
import com.example.dvely.deployment.application.port.out.GithubPagesPort;
import com.example.dvely.deployment.application.port.out.GithubRepoPort;
import com.example.dvely.deployment.application.result.DeployResult;
import com.example.dvely.deployment.domain.model.DeploymentHistory;
import com.example.dvely.deployment.domain.repository.DeploymentHistoryRepository;
import com.example.dvely.deployment.domain.value.DeployTargetType;
import com.example.dvely.deployment.domain.value.PackageManager;
import com.example.dvely.deployment.infrastructure.workflow.DeployWorkflowTemplate;
import com.example.dvely.common.exception.ForbiddenException;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.project.domain.exception.ProjectNotFoundException;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
        User user = resolveUser(ownerUserId);

        String userToken     = user.getGithubUserAccessToken();
        String sourceRepo    = project.getSourceRepository();
        String deploymentRepo = project.getDeploymentRepository();

        String versionLabel;

        if (command.deployTargetType() == DeployTargetType.LATEST) {
            versionLabel = deployLatest(userToken, sourceRepo);
        } else {
            versionLabel = command.versionName();
        }

        // GitHub Pages 설정
        String deployBranch = resolveDeployBranch(userToken, deploymentRepo, command, versionLabel);
        String pagesUrl     = deployToPages(userToken, deploymentRepo, deployBranch);

        // 빌드 워크플로우 트리거
        String checkoutRef = command.deployTargetType() == DeployTargetType.LATEST ? MAIN_BRANCH : versionLabel;
        LocalDateTime triggerTime = LocalDateTime.now();
        ensureAndTriggerWorkflow(userToken, sourceRepo, project.getTemplateType(), MAIN_BRANCH, checkoutRef);

        // 배포 이력 저장 + 프로젝트 상태 IN_PROGRESS 전환
        DeploymentHistory history = deploymentHistoryRepository.save(
                new DeploymentHistory(projectId, command.deployTargetType(), versionLabel, pagesUrl));
        project.updateDeployment(DeployStatus.IN_PROGRESS, pagesUrl, versionLabel);
        projectRepository.save(project);

        // run_id 폴링 후 이력에 저장 (최대 5회, 3초 간격)
        Long runId = githubActionsPort.pollRunId(
                userToken, sourceRepo, DeployWorkflowTemplate.fileName(),
                triggerTime, 5, 3000);
        if (runId != null) {
            history.assignRunId(runId);
            deploymentHistoryRepository.save(history);
        }

        log.info("배포 트리거 완료: projectId={}, historyId={}, runId={}, version={}, url={}",
                projectId, history.getId(), runId, versionLabel, pagesUrl);

        return new DeployResult(
                history.getId(),
                projectId,
                command.deployTargetType().name(),
                versionLabel,
                DeployStatus.IN_PROGRESS.name(),
                pagesUrl,
                history.getTriggeredAt()
        );
    }

    // ── LATEST 배포: preview → main merge + 태그 보장 ────────────────────────

    private String deployLatest(String userToken, String sourceRepo) {
        // 1. preview와 main 차이 확인 후 필요 시 PR 생성 + merge
        if (githubRepoPort.hasNewCommits(userToken, sourceRepo, MAIN_BRANCH, PREVIEW_BRANCH)) {
            log.info("preview → main 새 커밋 있음, PR 생성 및 merge: repo={}", sourceRepo);
            int prNumber = githubRepoPort.createOrGetPullRequest(
                    userToken, sourceRepo, PREVIEW_BRANCH, MAIN_BRANCH,
                    "[Qeploy] Deploy preview to main");
            githubRepoPort.mergePullRequest(userToken, sourceRepo, prNumber);
            log.info("PR merge 완료: repo={}, pr=#{}", sourceRepo, prNumber);
        } else {
            log.info("preview와 main 동일, PR/merge 생략: repo={}", sourceRepo);
        }

        // 2. main HEAD에 태그 없으면 순차 태그 생성
        String mainSha = githubRepoPort.getHeadCommitSha(userToken, sourceRepo, MAIN_BRANCH);
        if (!githubRepoPort.isCommitTagged(userToken, sourceRepo, mainSha)) {
            String tag = githubRepoPort.createNextSequentialTag(userToken, sourceRepo, mainSha);
            log.info("순차 태그 생성: repo={}, tag={}", sourceRepo, tag);
            return tag;
        }

        // 이미 태그 달린 경우 — 최신 순차 태그 조회는 별도 필요 없이 null 반환 후 상위에서 처리
        // (태그명이 필요하면 추가 조회 가능, 현재는 null 허용)
        return null;
    }

    // ── 프로젝트 검증 ──────────────────────────────────────────────────────────

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

    // ── 유저 조회 및 토큰 자동 갱신 ──────────────────────────────────────────

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

    // ── 배포 브랜치 결정 ──────────────────────────────────────────────────────

    private String resolveDeployBranch(String userToken, String deploymentRepo,
                                       DeployCommand command, String versionLabel) {
        if (command.deployTargetType() == DeployTargetType.LATEST) {
            return PAGES_BRANCH;
        }
        String branchName = RELEASE_BRANCH_PREFIX + command.versionName();
        return githubPagesPort.createBranchFromTag(
                userToken, deploymentRepo, command.versionName(), branchName);
    }

    // ── GitHub Pages 설정 ─────────────────────────────────────────────────────

    private String deployToPages(String userToken, String deploymentRepo, String branch) {
        GithubPagesPort.PagesInfo pagesInfo = githubPagesPort.getPages(userToken, deploymentRepo);

        if (!pagesInfo.enabled()) {
            log.info("GitHub Pages 미활성화 → 활성화: repo={}, branch={}", deploymentRepo, branch);
            return githubPagesPort.enablePages(userToken, deploymentRepo, branch);
        }
        if (branch.equals(pagesInfo.sourceBranch())) {
            log.info("GitHub Pages 소스 브랜치 동일, 업데이트 생략: repo={}, branch={}", deploymentRepo, branch);
            return pagesInfo.url();
        }
        log.info("GitHub Pages 소스 변경: repo={}, {} → {}", deploymentRepo, pagesInfo.sourceBranch(), branch);
        return githubPagesPort.updatePagesSource(userToken, deploymentRepo, branch, pagesInfo.customDomain());
    }

    // ── 빌드 워크플로우 보장 및 트리거 ──────────────────────────────────────

    private static final int WORKFLOW_TRIGGER_MAX_RETRY = 5;
    private static final long WORKFLOW_TRIGGER_RETRY_INTERVAL_MS = 3000;

    private void ensureAndTriggerWorkflow(String userToken, String sourceRepo,
                                          String templateType, String dispatchRef, String checkoutRef) {
        String workflowFile = DeployWorkflowTemplate.fileName();
        PackageManager pm = githubRepoPort.detectPackageManager(userToken, sourceRepo);
        log.info("패키지 매니저 감지: repo={}, pm={}", sourceRepo, pm);
        String nodeVersion = githubRepoPort.detectNodeVersion(userToken, sourceRepo);
        log.info("Node.js 버전 감지: repo={}, version={}", sourceRepo, nodeVersion);
        String detectedType = githubRepoPort.detectFrameworkType(userToken, sourceRepo);
        String resolvedType = detectedType != null ? detectedType : templateType;
        log.info("프레임워크 타입: repo={}, detected={}, stored={}, resolved={}", sourceRepo, detectedType, templateType, resolvedType);
        String content = DeployWorkflowTemplate.generate(resolvedType, null, pm, nodeVersion);
        githubActionsPort.createOrUpdateWorkflow(userToken, sourceRepo, workflowFile, content);

        triggerWithRetry(userToken, sourceRepo, workflowFile, dispatchRef, checkoutRef);
    }

    private void triggerWithRetry(String userToken, String sourceRepo, String workflowFile,
                                  String dispatchRef, String checkoutRef) {
        for (int attempt = 1; attempt <= WORKFLOW_TRIGGER_MAX_RETRY; attempt++) {
            try {
                githubActionsPort.triggerWorkflow(userToken, sourceRepo, workflowFile, dispatchRef, checkoutRef);
                log.info("워크플로우 dispatch 성공: repo={}, dispatchRef={}, checkoutRef={}",
                        sourceRepo, dispatchRef, checkoutRef);
                return;
            } catch (IllegalStateException e) {
                if (attempt == WORKFLOW_TRIGGER_MAX_RETRY) {
                    throw new IllegalStateException(
                            "워크플로우 트리거 실패 (" + WORKFLOW_TRIGGER_MAX_RETRY + "회 재시도 초과): " + e.getMessage(), e);
                }
                log.warn("워크플로우 dispatch 실패, {}초 후 재시도 ({}/{}): {}",
                        WORKFLOW_TRIGGER_RETRY_INTERVAL_MS / 1000, attempt, WORKFLOW_TRIGGER_MAX_RETRY, e.getMessage());
                try {
                    Thread.sleep(WORKFLOW_TRIGGER_RETRY_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("워크플로우 트리거 대기 중 인터럽트 발생", ie);
                }
            }
        }
    }
}
