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

    // 토큰이 URL 에서 빠졌으므로 자격 증명 공급이 실패하면 git 이 인증 프롬프트를 띄우며 멈춘다.
    // 컨테이너 exec 는 입력이 없어 영원히 기다리게 되고 에이전트 스레드가 잡힌다. 즉시 실패시킨다.
    private static final String GIT_NO_PROMPT = "GIT_TERMINAL_PROMPT=0 ";

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

        // clone URL 에 토큰을 넣지 않는다.
        //
        // 자격 증명은 아래 credential helper 가 이미 공급하므로 URL 에 넣을 이유가 없고, 넣으면 두
        // 곳으로 샌다. 하나는 컨테이너의 프로세스 목록이다 — 이 컨테이너는 에이전트가 만든 코드를
        // 실행하는 곳이라, 그 코드가 clone 중에 ps 를 읽으면 사용자의 GitHub 토큰을 그대로
        // 가져갈 수 있다. 다른 하나는 서버 로그다 — DockerContainerService#exec 가 명령 전문을
        // log.debug 로 찍으므로 debug 를 켜는 순간 토큰이 로그에 남는다.
        //
        // PreviewBranchPushService 는 원래 이 방식이다(git push 에 URL 을 주지 않는다). 여기만
        // 어긋나 있었다.
        String cloneUrl = "https://github.com/" + sourceRepo + ".git";

        // git credential 파일 작성 — 토큰이 명령줄에 노출되지 않게 base64 로 파일에만 쓴다.
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
                dockerService.exec(containerId, GIT_NO_PROMPT + "git clone " + cloneUrl + " " + APP_DIR);
                log.info("[PreviewWorkspace] 다른 repo 감지, 재clone: {}", sourceRepo);
            } else {
                dockerService.exec(containerId, "cd " + APP_DIR + " && git fetch origin");
                log.info("[PreviewWorkspace] 기존 repo fetch: {}", sourceRepo);
            }
        } else {
            // 처음 clone
            dockerService.exec(containerId, "mkdir -p /workspace");
            dockerService.exec(containerId, GIT_NO_PROMPT + "git clone " + cloneUrl + " " + APP_DIR);
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

    /** 프리뷰 서버가 포트를 열 때까지 기다리는 최대 시간(초). */
    private static final int SERVE_READY_TIMEOUT_SECONDS = 30;

    /**
     * 빌드 산출물을 서빙하고, 포트가 실제로 응답할 때까지 기다린 뒤 반환한다.
     *
     * 호출자(CodeAgentService)는 이 메서드가 정상 반환하면 세션을 ACTIVE 로 올린다. 그래서
     * "반환됐다 = 열면 보인다"가 성립해야 한다. 예전에는 nohup 으로 띄우고 sleep 3 만 한 뒤
     * 로그만 읽고 끝냈는데, 그 3초는 npx serve 가 포트를 잡기에 모자랄 때가 있었다. 그러면
     * 세션은 ACTIVE 인데 프록시는 연결을 못 해 첫 요청이 502 가 된다(2026-08-15 dev 실측:
     * 13:04:27 ACTIVE → 13:04:30 첫 요청 502).
     *
     * 그래서 고정 대기 대신 포트를 직접 폴링한다. curl 이 없는 이미지가 있어 node 로 확인한다 —
     * npx 를 쓰는 컨테이너이므로 node 는 반드시 있다.
     *
     * @throws IllegalStateException 제한 시간 안에 포트가 응답하지 않으면. 호출자가 이 예외를
     *         받아 세션을 FAILED 로 닫으므로, FE 는 준비 중 화면을 무한히 돌리지 않는다.
     */
    public void startPreviewServer(String containerId) {
        String buildDir = detectBuildOutputDir(containerId);
        dockerService.exec(containerId, "pkill -f 'npx serve' 2>/dev/null || true");
        dockerService.exec(containerId,
                "nohup npx serve -s " + buildDir + " -l 3000 > /tmp/serve.log 2>&1 &");

        String probe = "node -e \"require('http')"
                + ".get({host:'127.0.0.1',port:3000,timeout:1000},r=>process.exit(0))"
                + ".on('error',()=>process.exit(1))\" 2>/dev/null";
        String result = dockerService.exec(containerId,
                "ready=no; "
                        + "for i in $(seq 1 " + SERVE_READY_TIMEOUT_SECONDS + "); do "
                        + "if " + probe + "; then ready=yes; break; fi; sleep 1; "
                        + "done; "
                        + "echo \"serve_ready=$ready\"; "
                        + "tail -n 20 /tmp/serve.log 2>/dev/null || true");

        if (result == null || !result.contains("serve_ready=yes")) {
            log.warn("[PreviewWorkspace] 프리뷰 서버가 {}초 안에 응답하지 않음 | buildDir={} | log={}",
                    SERVE_READY_TIMEOUT_SECONDS, buildDir, result);
            throw new IllegalStateException(
                    "프리뷰 서버가 제한 시간 안에 시작되지 않았습니다. buildDir=" + buildDir);
        }
        log.info("[PreviewWorkspace] 프리뷰 서버 준비 완료 | buildDir={} | log={}", buildDir, result);
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
