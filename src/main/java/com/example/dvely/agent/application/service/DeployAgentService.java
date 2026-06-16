package com.example.dvely.agent.application.service;

import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.exception.AgentInputRequiredException;
import com.example.dvely.agent.application.service.CodeAgentService.CodeResult;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.store.InputWaitStore;
import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.deployment.application.command.dto.DeployCommand;
import com.example.dvely.deployment.application.facade.DeploymentFacade;
import com.example.dvely.deployment.application.result.DeployResult;
import com.example.dvely.deployment.domain.value.DeployTargetType;
import com.example.dvely.project.application.port.out.GithubRepositoryPort;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import com.example.dvely.preview.application.result.PreviewSessionInfo;
import com.example.dvely.preview.application.service.PreviewSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeployAgentService {

    private final DockerContainerService dockerService;
    private final PreviewSessionService  previewSessionService;
    private final GithubRepositoryPort   githubRepositoryPort;
    private final UserRepository         userRepository;
    private final AuthCommandService     authCommandService;
    private final ProjectRepository      projectRepository;
    private final DeploymentFacade       deploymentFacade;
    private final InputWaitStore         inputWaitStore;

    public CodeResult execute(AgentStep step, Long userId, String taskId, Long projectId) {
        log.info("[DeployAgent] 배포 시작 | userId={} taskId={} projectId={}", userId, taskId, projectId);

        Optional<PreviewSessionInfo> containerOpt = previewSessionService.findByTaskId(taskId);
        Project project;
        boolean sourceChanged = false;

        if (containerOpt.isPresent()) {
            // CODE 스텝이 선행된 경우 — 컨테이너의 코드를 GitHub에 push 후 배포
            String containerId = containerOpt.get().containerId();
            User   user        = resolveUser(userId);
            String userToken   = user.getGithubUserAccessToken();
            String username    = user.getUsername();

            if (projectId != null) {
                Optional<Project> found = projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, userId);
                boolean bound = found.isPresent()
                        && found.get().getRepositoryBindingStatus() == RepositoryBindingStatus.BOUND;

                if (bound) {
                    // BOUND 프로젝트 존재 → 해당 저장소로 push
                    project = found.get();
                    log.info("[DeployAgent] 기존 프로젝트 저장소로 push: {}", project.getSourceRepository());
                    pushSourceToGithub(containerId, userToken, username, project.getSourceRepository(), false, taskId);
                    sourceChanged = true;
                } else {
                    // NOT_BOUND 또는 저장소 없음 → 신규 저장소 생성 후 push
                    log.info("[DeployAgent] projectId={} 저장소 미연결, 신규 저장소로 push", projectId);
                    String repoName     = resolveRepoName(step, userId, containerId, taskId);
                    String repoFullName = ensureGithubRepo(userId, username, repoName);
                    pushSourceToGithub(containerId, userToken, username, repoFullName, true, taskId);
                    sourceChanged = true;
                    project = findOrCreateProject(userId, repoName, repoFullName);
                }
            } else {
                String repoName     = resolveRepoName(step, userId, containerId, taskId);
                String repoFullName = ensureGithubRepo(userId, username, repoName);
                log.info("[DeployAgent] 신규 저장소 push: {}", repoFullName);
                pushSourceToGithub(containerId, userToken, username, repoFullName, true, taskId);
                sourceChanged = true;
                project = findOrCreateProject(userId, repoName, repoFullName);
            }

        } else if (projectId != null) {
            // 컨테이너 없음 — 기존 프로젝트를 코드 변경 없이 바로 배포
            log.info("[DeployAgent] 컨테이너 없음, 기존 프로젝트 직접 배포 | projectId={}", projectId);
            project = projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, userId)
                    .orElseThrow(() -> new IllegalStateException("프로젝트를 찾을 수 없습니다: " + projectId));

            // NOT_BOUND인 경우 프로젝트 이름 기반으로 GitHub 저장소 자동 연결 시도
            if (project.getRepositoryBindingStatus() != RepositoryBindingStatus.BOUND) {
                project = autoBindRepository(project, userId);
            }

        } else {
            throw new IllegalStateException("배포할 코드가 없습니다. 코드 생성 또는 기존 프로젝트 ID가 필요합니다.");
        }

        if (sourceChanged) {
            log.info("[DeployAgent] 승인된 변경을 preview 브랜치에 반영 | repository={} taskId={}",
                    project.getSourceRepository(), taskId);
        }

        // AgentPlanExecutor는 필요한 승인이 모두 끝난 뒤에만 이 서비스를 호출한다.
        DeployResult result = deploymentFacade.deploy(userId, project.getId(),
                new DeployCommand(DeployTargetType.LATEST, null, taskId));

        String summary = sourceChanged
                ? buildApprovedChangeSummary(project.getSourceRepository(), taskId, result.deploymentId())
                : buildSummary(project.getSourceRepository(), result.deploymentId());
        log.info("[DeployAgent] 배포 요청 저장 | deploymentId={}", result.deploymentId());
        return new CodeResult(null, summary);
    }

    // ── 저장소명 결정 ──────────────────────────────────────────────────────────

    private String resolveRepoName(AgentStep step, Long userId, String containerId, String taskId) {
        String fromStep = step.parameters().getOrDefault("repoName", "").trim();
        if (!fromStep.isEmpty()) return sanitize(fromStep);

        String remote = dockerService.exec(containerId,
                "git -C /workspace/app remote get-url origin 2>/dev/null || echo __none__").trim();
        if (!remote.equals("__none__") && !remote.isEmpty()) {
            String name = remote.substring(remote.lastIndexOf('/') + 1).replace(".git", "");
            log.info("[DeployAgent] 기존 remote에서 저장소명 추출: {}", name);
            return sanitize(name);
        }

        return askUserForRepoName(userId, taskId);
    }

    private String askUserForRepoName(Long userId, String taskId) {
        String question = "배포할 GitHub 저장소 이름을 입력해주세요.\n"
                + "(예: my-react-app, todo-kanban)\n"
                + "소문자, 숫자, 하이픈만 사용 가능합니다.";

        return inputWaitStore.consume(taskId)
                .map(String::trim)
                .map(this::sanitize)
                .filter(name -> !name.isEmpty())
                .orElseThrow(() -> new AgentInputRequiredException(question));
    }

    // ── User 조회 및 토큰 갱신 ──────────────────────────────────────────────────

    private User resolveUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("유저를 찾을 수 없습니다: " + userId));
        if (user.isUserAccessTokenExpired()) {
            authCommandService.refreshGithubUserToken(userId);
            user = userRepository.findById(userId).orElseThrow();
        }
        return user;
    }

    // ── GitHub 저장소 확인/생성 ─────────────────────────────────────────────────

    private String ensureGithubRepo(Long userId, String username, String repoName) {
        String fullName = username + "/" + repoName;
        if (!githubRepositoryPort.repositoryExists(userId, fullName)) {
            log.info("[DeployAgent] 신규 저장소 생성: {}", fullName);
            return githubRepositoryPort.createRepository(userId, repoName, RepositoryVisibility.PUBLIC);
        }
        log.info("[DeployAgent] 기존 저장소 재사용: {}", fullName);
        return fullName;
    }

    // ── Project 엔티티 조회/생성 ────────────────────────────────────────────────

    private Project findOrCreateProject(Long userId, String repoName, String repoFullName) {
        Optional<Project> existing = projectRepository
                .findFirstByOwnerUserIdAndSourceRepositoryIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(userId, repoFullName);
        if (existing.isPresent()) {
            log.info("[DeployAgent] 기존 Project 재사용: id={}", existing.get().getId());
            return existing.get();
        }
        Project project = new Project(userId, repoName, "agent", null, "agent", RepositoryVisibility.PUBLIC);
        project.bindRepository(repoFullName, RepositoryVisibility.PUBLIC);
        Project saved = projectRepository.save(project);
        log.info("[DeployAgent] 신규 Project 생성: id={}", saved.getId());
        return saved;
    }

    // ── Docker → GitHub 소스 푸시 ──────────────────────────────────────────────

    private void pushSourceToGithub(String containerId, String userToken, String username,
                                    String repoFullName, boolean isNew, String taskId) {
        dockerService.exec(containerId, "apk add --no-cache git");
        writeGitCredentials(containerId, username, userToken);
        dockerService.exec(containerId, "git config --global credential.helper 'store --file /tmp/.git-credentials'");
        dockerService.exec(containerId, "git config --global user.email 'agent@qeploy.com'");
        dockerService.exec(containerId, "git config --global user.name 'Qeploy Agent'");

        String remoteUrl = "https://github.com/" + repoFullName + ".git";
        boolean hasGit = "yes".equals(
                dockerService.exec(containerId, "[ -d /workspace/app/.git ] && echo yes || echo no").trim());

        if (!hasGit) {
            if (isNew) writeGitignore(containerId);
            dockerService.exec(containerId, "cd /workspace/app && git init -b preview");
            dockerService.exec(containerId, "cd /workspace/app && git remote add origin " + remoteUrl);
        } else {
            dockerService.exec(containerId, "cd /workspace/app && git remote set-url origin " + remoteUrl);
            dockerService.exec(containerId, "cd /workspace/app && git checkout -B preview");
        }

        dockerService.exec(containerId, "cd /workspace/app && git add -A");
        dockerService.exec(containerId,
                "cd /workspace/app && git diff --cached --quiet || git commit -m 'feat: apply Qeploy Agent task "
                        + taskId + "'");
        dockerService.exec(containerId, "cd /workspace/app && git push -u origin preview");
    }

    // ── NOT_BOUND 프로젝트 자동 바인딩 ────────────────────────────────────────────

    private Project autoBindRepository(Project project, Long userId) {
        User user = resolveUser(userId);
        String username = user.getUsername();
        String candidateRepo = username + "/" + sanitize(project.getName());
        log.info("[DeployAgent] NOT_BOUND 프로젝트 저장소 자동 탐색: candidate={}", candidateRepo);

        if (githubRepositoryPort.repositoryExists(userId, candidateRepo)) {
            Optional<GithubRepositoryPort.GithubRepository> repo =
                    githubRepositoryPort.getRepository(userId, candidateRepo);
            if (repo.isPresent()) {
                RepositoryVisibility visibility = repo.get().privateRepository()
                        ? RepositoryVisibility.PRIVATE : RepositoryVisibility.PUBLIC;
                project.bindRepository(candidateRepo, visibility);
                project.updateRepositoryHealth(RepositoryHealthStatus.HEALTHY);
                Project saved = projectRepository.save(project);
                log.info("[DeployAgent] 자동 바인딩 완료: projectId={}, repo={}", saved.getId(), candidateRepo);
                return saved;
            }
        }

        throw new IllegalStateException(
                "프로젝트(id=" + project.getId() + ")에 GitHub 저장소가 연결되어 있지 않습니다. " +
                "POST /api/v1/projects/" + project.getId() + "/repository API를 통해 먼저 저장소를 연결하거나, " +
                "GitHub 저장소 이름을 '" + candidateRepo + "'으로 생성해주세요.");
    }

    // ── 유틸 ───────────────────────────────────────────────────────────────────

    private void writeGitCredentials(String containerId, String username, String userToken) {
        String cred = "https://" + username + ":" + userToken + "@github.com";
        String b64  = Base64.getEncoder().encodeToString(cred.getBytes(StandardCharsets.UTF_8));
        dockerService.exec(containerId,
                "node -e \"require('fs').writeFileSync('/tmp/.git-credentials', Buffer.from('" + b64 + "', 'base64').toString('utf8'))\"");
    }

    private void writeGitignore(String containerId) {
        String content = "node_modules/\ndist/\nbuild/\nout/\n.env\n.env.local\n";
        String b64     = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        dockerService.exec(containerId,
                "node -e \"require('fs').writeFileSync('/workspace/app/.gitignore', Buffer.from('" + b64 + "', 'base64').toString('utf8'))\"");
    }

    private String sanitize(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9-]", "-").replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
    }

    private String defaultRepoName(Long userId) {
        return "qeploy-project-" + userId;
    }

    private String buildSummary(String repoFullName, Long deploymentId) {
        return String.format("""
                GitHub Pages 배포 요청을 접수했습니다.
                - 저장소: https://github.com/%s
                - 배포 ID: %d
                - worker가 버전 확정과 workflow 실행을 비동기로 진행합니다.
                """, repoFullName, deploymentId);
    }

    private String buildApprovedChangeSummary(String repoFullName, String taskId, Long deploymentId) {
        return String.format("""
                승인된 변경 사항의 배포 요청을 접수했습니다.
                - 저장소: https://github.com/%s
                - 요청 ID: %s
                - 배포 ID: %d
                - preview 브랜치 반영과 배포 workflow는 worker가 비동기로 진행합니다.
                """, repoFullName, taskId, deploymentId);
    }
}
