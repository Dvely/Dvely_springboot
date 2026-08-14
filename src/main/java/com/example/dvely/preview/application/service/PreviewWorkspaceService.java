package com.example.dvely.preview.application.service;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 프리뷰 컨테이너 안의 워크스페이스를 준비하고 서빙까지 붙이는 공용 절차.
 *
 * <p>같은 일을 두 경로가 필요로 한다. Agent CODE 스텝은 "저장소를 가져와 → LLM 이 고치고 →
 * 빌드한 결과를 서빙"하고, 프로젝트 단위 프리뷰({@link ProjectPreviewService})는 "저장소를
 * 가져와 → 그대로 빌드해 → 서빙"한다. 가운데 LLM 루프만 다르고 앞뒤는 같다 — 그 앞뒤를 각자
 * 들고 있으면 한쪽만 고쳐지는 순간 두 프리뷰가 서로 다른 브랜치나 다른 디렉터리를 서빙하기
 * 시작한다. 사용자 입장에서 "작업 결과 프리뷰"와 "현재 상태 프리뷰"는 같은 것을 보여줘야 하므로,
 * 여기 한 벌만 둔다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreviewWorkspaceService {

    private static final String APP_DIR = "/workspace/app";
    private static final String BUILD_LOG_PATH = "/tmp/qeploy-build.log";

    private final DockerContainerService dockerService;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final AuthCommandService authCommandService;

    /**
     * 프로젝트의 GitHub 저장소를 컨테이너로 clone/fetch 하고 preview 브랜치로 맞춘 뒤 의존성을
     * 설치한다. 저장소가 연결되지 않은 프로젝트면 아무것도 하지 않는다(신규 프로젝트는 CODE 스텝이
     * 빈 워크스페이스에 스캐폴딩하는 것이 정상 경로다).
     */
    public void prepareProject(String containerId, Long userId, Long projectId) {
        Project project = projectRepository.findByIdAndOwnerUserId(projectId, userId)
                .orElseThrow(() -> new RuntimeException("프로젝트를 찾을 수 없거나 접근 권한이 없습니다: projectId=" + projectId));

        String sourceRepo = project.getSourceRepository();
        if (sourceRepo == null || sourceRepo.isBlank()) {
            log.warn("[PreviewWorkspace] projectId={} 에 연결된 GitHub 저장소 없음, 신규 프로젝트로 진행", projectId);
            return;
        }

        // 유저 토큰 조회 (만료 시 갱신)
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("유저를 찾을 수 없습니다: " + userId));
        if (user.isUserAccessTokenExpired()) {
            authCommandService.refreshGithubUserToken(userId);
            user = userRepository.findById(userId).orElseThrow();
        }
        String userToken = user.getGithubUserAccessToken();
        String username  = user.getUsername();

        // 인증 포함 clone URL
        String cloneUrl = "https://" + username + ":" + userToken + "@github.com/" + sourceRepo + ".git";

        // git credential 파일 작성 (토큰 로그 노출 최소화)
        dockerService.exec(containerId, "apk add --no-cache git 2>/dev/null || true");
        String cred = "https://" + username + ":" + userToken + "@github.com";
        String credB64 = Base64.getEncoder().encodeToString(cred.getBytes(StandardCharsets.UTF_8));
        dockerService.exec(containerId,
                "node -e \"require('fs').writeFileSync('/tmp/.git-credentials', Buffer.from('" + credB64 + "', 'base64').toString('utf8'))\"");
        dockerService.exec(containerId, "git config --global credential.helper 'store --file /tmp/.git-credentials'");
        dockerService.exec(containerId, "git config --global user.email 'agent@qeploy.com'");
        dockerService.exec(containerId, "git config --global user.name 'Qeploy Agent'");

        String appExists = dockerService.exec(containerId, "[ -d " + APP_DIR + "/.git ] && echo yes || echo no").trim();

        if ("yes".equals(appExists)) {
            // 이미 clone됨 → pull로 최신화
            String currentRemote = dockerService.exec(containerId,
                    "git -C " + APP_DIR + " remote get-url origin 2>/dev/null || echo __none__").trim();
            if (!currentRemote.contains(sourceRepo)) {
                // 다른 repo → 삭제 후 재clone
                dockerService.exec(containerId, "rm -rf " + APP_DIR);
                dockerService.exec(containerId, "git clone " + cloneUrl + " " + APP_DIR);
                log.info("[PreviewWorkspace] 다른 repo 감지, 재clone: {}", sourceRepo);
            } else {
                dockerService.exec(containerId, "cd " + APP_DIR + " && git fetch origin");
                log.info("[PreviewWorkspace] 기존 repo fetch: {}", sourceRepo);
            }
        } else {
            // 처음 clone
            dockerService.exec(containerId, "mkdir -p /workspace");
            dockerService.exec(containerId, "git clone " + cloneUrl + " " + APP_DIR);
            log.info("[PreviewWorkspace] 저장소 clone 완료: {}", sourceRepo);
        }

        dockerService.exec(containerId,
                "cd " + APP_DIR + " && git fetch origin preview 2>/dev/null || true");
        dockerService.exec(containerId,
                "cd " + APP_DIR + " && "
                        + "(git show-ref --verify --quiet refs/remotes/origin/preview "
                        + "&& git checkout -B preview origin/preview || git checkout -B preview)");

        // clone 후 의존성 설치
        String pkgJson = dockerService.exec(containerId, "[ -f " + APP_DIR + "/package.json ] && echo yes || echo no").trim();
        if ("yes".equals(pkgJson)) {
            log.info("[PreviewWorkspace] npm install 실행");
            dockerService.exec(containerId, "cd " + APP_DIR + " && npm install");
        }
    }

    /**
     * package.json 에 build 스크립트가 있으면 빌드한다.
     *
     * <p>CODE 스텝에서는 LLM 이 직접 build 를 호출하지만(시스템 프롬프트가 그렇게 지시한다),
     * 프로젝트 단위 프리뷰에는 지시할 LLM 이 없어 서버가 직접 돌려야 한다. 빌드 스크립트가 없는
     * 정적 사이트는 빌드 없이 그대로 서빙되는 것이 맞으므로 조용히 건너뛴다.</p>
     *
     * <p>출력을 {@code /tmp/qeploy-build.log} 로 흘리는 것은 CODE 스텝의 빌드 명령과 같은 규약이다
     * — 실패했을 때 읽어갈 곳이 한 군데여야 한다.</p>
     */
    public void buildIfConfigured(String containerId) {
        String hasBuildScript = dockerService.exec(containerId,
                "node -e \"try{const s=(require('" + APP_DIR + "/package.json').scripts)||{};"
                        + "process.stdout.write(s.build?'yes':'no')}catch(e){process.stdout.write('no')}\"").trim();
        if (!"yes".equals(hasBuildScript)) {
            log.info("[PreviewWorkspace] build 스크립트 없음 — 빌드 없이 서빙");
            return;
        }
        log.info("[PreviewWorkspace] npm run build 실행");
        dockerService.exec(containerId,
                "cd " + APP_DIR + " && set -o pipefail; (npm run build) 2>&1 | tee " + BUILD_LOG_PATH);
    }

    /** 빌드 로그 꼬리. 실패 사유를 사용자에게 그대로 보여주기 위해 컨테이너를 지우기 전에 읽어둔다. */
    public String tailBuildLog(String containerId, int lines) {
        return dockerService.exec(containerId,
                "tail -n " + lines + " " + BUILD_LOG_PATH + " 2>/dev/null || true");
    }

    // ── Preview 서버 (빌드 종료 후 서버가 직접 실행) ───────────────────────────
    public void startPreviewServer(String containerId) {
        String buildDir = detectBuildOutputDir(containerId);
        dockerService.exec(containerId, "pkill -f 'npx serve' 2>/dev/null || true");
        dockerService.exec(containerId,
                "nohup npx serve -s " + buildDir + " -l 3000 > /tmp/serve.log 2>&1 &");
        String serveLog = dockerService.exec(containerId, "sleep 3 && cat /tmp/serve.log");
        log.info("[PreviewWorkspace] 프리뷰 서버 시작 | buildDir={} | log={}", buildDir, serveLog);
    }

    /**
     * Resolves the directory to serve as the preview.
     *
     * <p>The known output directories are checked first; the index.html search below them exists
     * for projects that have no build step at all (a plain static site), and for build output
     * under a directory this list does not name. That search used to accept the first index.html
     * it found anywhere under /workspace, which for a Vite or CRA project is the <em>source</em>
     * entry point — so a run whose build never happened or failed still resolved to a directory,
     * started a preview over unbuilt sources, and reported success. That was the second half of
     * the frontend's report: a task marked complete whose preview does not work.</p>
     *
     * <p>What separates the two is a sibling package.json: a project root has one next to its
     * index.html, a build output directory does not. Skipping those roots means a missing build
     * now reaches the caller as a failure, where the build log drives the CODE step's
     * {@code BuildFailureAnalyzer} (and, for a project-scoped preview, the session's
     * failure_reason) — which is what should have happened all along.</p>
     */
    private String detectBuildOutputDir(String containerId) {
        for (String candidate : List.of(
                APP_DIR + "/dist",
                APP_DIR + "/build",
                APP_DIR + "/out")) {
            String result = dockerService.exec(containerId,
                    "[ -d " + candidate + " ] && echo exists || echo missing");
            if ("exists".equals(result.trim())) {
                log.info("[PreviewWorkspace] 빌드 결과물 감지: {}", candidate);
                return candidate;
            }
        }
        for (String candidate : findIndexHtmlDirectories(containerId)) {
            if (isProjectSourceRoot(containerId, candidate)) {
                log.info("[PreviewWorkspace] 프로젝트 소스 루트이므로 빌드 결과물에서 제외: {}", candidate);
                continue;
            }
            log.info("[PreviewWorkspace] index.html 기반 빌드 경로 감지: {}", candidate);
            return candidate;
        }
        throw new IllegalStateException(
                "빌드 결과 디렉터리를 찾지 못했습니다. build가 실행되지 않았거나 실패했습니다.");
    }

    /**
     * Directories holding an index.html, nearest-first. {@code public/} is excluded because CRA
     * keeps its source template there; node_modules because a dependency's own index.html is never
     * this project's output. More than one candidate is read so that a skipped source root does not
     * exhaust the search — a Vite project has its source index.html and its dist/index.html both.
     */
    private List<String> findIndexHtmlDirectories(String containerId) {
        String found = dockerService.exec(containerId,
                "find /workspace -name 'index.html' -not -path '*/node_modules/*' "
                        + "-not -path '*/public/*' 2>/dev/null | head -5");
        return found.lines()
                .map(String::trim)
                .filter(line -> line.endsWith("/index.html"))
                .map(line -> line.substring(0, line.lastIndexOf('/')))
                .distinct()
                .toList();
    }

    private boolean isProjectSourceRoot(String containerId, String directory) {
        String result = dockerService.exec(containerId,
                "[ -f " + directory + "/package.json ] && echo exists || echo missing");
        return "exists".equals(result.trim());
    }
}
