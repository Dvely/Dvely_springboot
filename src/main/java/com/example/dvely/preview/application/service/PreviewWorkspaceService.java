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
        // 종료 코드를 읽는다. 예전에는 exec 이 stdout 만 돌려줘서 set -o pipefail 을 걸어둬도
        // 아무 의미가 없었다 — 빌드가 깨져도 호출자는 성공으로 알았고, 컨테이너를 재사용하는
        // 프리뷰에서는 이전 성공 빌드의 dist 가 남아 있어 detectBuildOutputDir 이 그것을 잡았다.
        // 그래서 "빌드가 실패했는데 옛 화면이 그대로 보이는" 상태가 됐다.
        DockerContainerService.ExecResult result = dockerService.execWithExitCode(containerId,
                "cd " + APP_DIR + " && set -o pipefail; (npm run build) 2>&1 | tee " + BUILD_LOG_PATH);
        if (!result.succeeded()) {
            log.warn("[PreviewWorkspace] 빌드 실패 | exitCode={} | log={}",
                    result.exitCode(), tailBuildLog(containerId, 20));
            throw new IllegalStateException("프리뷰 빌드에 실패했습니다. exitCode=" + result.exitCode());
        }
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
        // 패턴이 'npx serve' 였을 때는 아무것도 잡지 못했다. 실행 중 프로세스의 cmdline 은
        //   npm exec serve -s /workspace/app/dist -l 3000
        //   node /root/.npm/_npx/<hash>/node_modules/.bin/serve -s ...
        // 이라 리터럴 'npx serve' 가 없다. 대신 이 pkill 을 실행하는 sh -c 자신의 cmdline 에는
        // 그 문자열이 들어 있어서 자기 자신에게 SIGTERM 을 보냈다(종료코드 143 실측).
        //
        // 그래서 옛 serve 가 포트 3000 을 계속 쥐고, 새 serve 는 EADDRINUSE 로 죽는 대신
        // 랜덤 포트로 조용히 옮겨 붙는다("Accepting connections at http://localhost:46113").
        // 게이트웨이는 3000 만 프록시하므로 옛 serve 가, 즉 그 serve 가 붙들고 있는 옛 디렉터리가
        // 계속 응답한다 — 빌드 산출물 경로가 바뀌면 낡은 화면이 그대로 보인다(2026-08-25 실측).
        //
        // [s]erve 는 pkill 자신의 cmdline 에는 '[s]erve -s' 로 남고 정규식 'serve -s' 와는
        // 매치되지 않아 자기 매치를 피한다. 실측에서 2건을 잡아 0건으로 만들었다.
        dockerService.exec(containerId, "pkill -f '[s]erve -s' 2>/dev/null || true");
        dockerService.exec(containerId,
                "nohup npx serve -s " + buildDir + " -l 3000 > /tmp/serve.log 2>&1 &");

        awaitServerReady(containerId, "/tmp/serve.log", "buildDir=" + buildDir);
    }

    /**
     * NODE_SERVER 런타임: 정적 serve 대신 앱 자체 서버를 3000 에 띄운다. UI 와 API 를 그 한 서버가
     * 모두 서빙하므로 게이트웨이는 그대로 3000 만 프록시한다.
     *
     * env(사용자 PREVIEW env + DB 커넥션 + PORT=3000)는 명령 문자열이 아니라 exec 의 env 로만
     * 넘긴다(execWithEnv) — DB 비밀번호 같은 값이 로그·예외에 남지 않게. startCommand 가 비면
     * {@code npm start} 로 실행한다.
     *
     * @throws IllegalStateException 제한 시간 안에 3000 이 응답하지 않으면. 호출자가 세션을 FAILED 로 닫는다.
     */
    public void startNodeServer(String containerId, String startCommand, List<String> env) {
        String command = (startCommand == null || startCommand.isBlank()) ? "npm start" : startCommand;
        dockerService.execWithExitCode(containerId,
                "cd " + APP_DIR + " && nohup " + command + " > /tmp/preview-server.log 2>&1 &", env);

        awaitServerReady(containerId, "/tmp/preview-server.log", "command=" + command);
    }

    /**
     * 3000 이 실제로 응답할 때까지 폴링한다. "프로세스를 띄웠다"와 "포트가 응답한다"는 다르고,
     * 후자가 돼야 프리뷰를 ACTIVE 로 올릴 수 있다(그러지 않으면 첫 요청이 502). curl 이 없는
     * 이미지가 있어 node 로 확인한다 — 프리뷰 컨테이너에는 node 가 반드시 있다.
     */
    private void awaitServerReady(String containerId, String logPath, String context) {
        awaitPortReady(containerId, 3000, SERVE_READY_TIMEOUT_SECONDS, logPath, context);
    }

    // ── JAVA_FULLSTACK (정적 FE + Java BE 를 한 컨테이너에서, 내부 nginx 로 3000 에서 라우팅) ──

    /** Java BE 가 붙는 포트. 내부 nginx 가 apiPathPrefix 요청을 여기로 프록시한다. */
    private static final int JAVA_BACKEND_PORT = 8080;
    /** Java BE 준비 대기(초). gradle 배포 다운로드 + 컴파일 + Spring 기동은 첫 실행에 수 분 걸린다. */
    private static final int JAVA_READY_TIMEOUT_SECONDS = 300;

    /**
     * JAVA_FULLSTACK: 정적 FE + Java BE 를 한 컨테이너에서 돌린다.
     *
     * <p>node:20-alpine 위에 부팅 때 JDK·nginx 를 apk 로 얹는다(합본 이미지 없이 시작). FE 는
     * npm build 로 정적 산출물을 만들고, Java BE 는 startCommand(기본 {@code ./gradlew bootRun})로
     * 8080 에 띄운다. env(사용자 PREVIEW + DB 커넥션 + SERVER_PORT=8080)는 execWithEnv 로만 넘겨
     * 비밀번호가 로그에 안 남게 한다. 마지막으로 내부 nginx 가 3000 에서 {@code apiPathPrefix}→8080,
     * 나머지→FE 정적 산출물로 가른다. 게이트웨이는 여전히 3000 만 프록시하므로 무변경이다.</p>
     *
     * @throws IllegalStateException 어느 단계든 실패하면. 호출자가 세션을 FAILED 로 닫는다.
     */
    public void startJavaFullstack(String containerId, String startCommand, List<String> backendEnv,
                                   String apiPathPrefix) {
        String backendDir = detectBackendDir(containerId);
        String frontendDir = detectFrontendDir(containerId);
        log.info("[PreviewWorkspace] JAVA_FULLSTACK 시작 | backendDir={} frontendDir={}", backendDir, frontendDir);

        // JDK + nginx. 이미 있으면 no-op, 정말 없으면 아래 strict 단계가 드러낸다.
        dockerService.exec(containerId, "apk add --no-cache openjdk21 nginx 2>&1 | tail -n 5 || true");

        // FE: 빌드해서 정적 산출물을 만든다.
        requireExec(containerId, "cd " + frontendDir + " && npm install", "프론트 npm install");
        requireExec(containerId, "cd " + frontendDir + " && npm run build", "프론트 빌드");
        String feBuildDir = detectFeBuildDir(containerId, frontendDir);

        // BE: 8080 에 띄운다. env 는 execWithEnv 로만(로그 유출 방지).
        String command = (startCommand == null || startCommand.isBlank()) ? "./gradlew bootRun" : startCommand;
        dockerService.execWithExitCode(containerId,
                "cd " + backendDir + " && nohup " + command + " > /tmp/preview-backend.log 2>&1 &", backendEnv);
        awaitPortReady(containerId, JAVA_BACKEND_PORT, JAVA_READY_TIMEOUT_SECONDS,
                "/tmp/preview-backend.log", "java-backend cmd=" + command);

        // nginx: 3000 에서 apiPathPrefix→8080, 나머지→FE 정적.
        startInternalNginxRouter(containerId, apiPathPrefix, feBuildDir);
    }

    /**
     * 내부 nginx 를 3000 에 띄워 {@code apiPathPrefix}→127.0.0.1:8080, 나머지→FE 정적 산출물로 가른다.
     * nginx 는 이미 설치돼 있다고 전제한다(startJavaFullstack 이 apk 로 얹는다). 게이트웨이가 3000 만
     * 프록시하므로, 이 라우터가 한 컨테이너 안에서 UI/API 를 나눠 게이트웨이는 무변경으로 둔다.
     */
    private static final String NGINX_CONF_PATH = "/tmp/preview-nginx.conf";

    public void startInternalNginxRouter(String containerId, String apiPathPrefix, String feBuildDir) {
        writeFile(containerId, NGINX_CONF_PATH, nginxConfig(apiPathPrefix, feBuildDir));
        dockerService.exec(containerId, "pkill -x nginx 2>/dev/null || true");
        requireExec(containerId, "nginx -c " + NGINX_CONF_PATH, "nginx 시작");
        awaitPortReady(containerId, 3000, SERVE_READY_TIMEOUT_SECONDS,
                "/tmp/preview-nginx-error.log", "nginx-3000");
    }

    /**
     * 내부 nginx 전체 설정. 프리뷰 컨테이너는 cap-drop ALL 이라 root 라도 DAC_OVERRIDE 가 없어
     * nginx 소유 기본 경로(/var/lib/nginx, /run/nginx)에 못 쓴다. 그래서 pid·로그·temp 를 전부
     * world-writable 한 /tmp 로 돌리고, 워커도 {@code user root} 로 돌려(정적 산출물이 root 소유라)
     * 권한 문제를 피한다. 시작 시 컴파일 기본 error_log 를 못 여는 alert 이 한 줄 뜨지만, 곧 이
     * 설정의 error_log 로 바꿔 붙으므로 무해하다(nginx 는 정상 기동한다).
     */
    private String nginxConfig(String apiPathPrefix, String feBuildDir) {
        String prefix = (apiPathPrefix == null || apiPathPrefix.isBlank()) ? "/api" : apiPathPrefix;
        return "user root;\n"
                + "worker_processes 1;\n"
                + "pid /tmp/preview-nginx.pid;\n"
                + "error_log /tmp/preview-nginx-error.log warn;\n"
                + "events { worker_connections 256; }\n"
                + "http {\n"
                + "  include /etc/nginx/mime.types;\n"
                + "  access_log off;\n"
                + "  client_body_temp_path /tmp/preview-nginx-client;\n"
                + "  proxy_temp_path /tmp/preview-nginx-proxy;\n"
                + "  fastcgi_temp_path /tmp/preview-nginx-fastcgi;\n"
                + "  uwsgi_temp_path /tmp/preview-nginx-uwsgi;\n"
                + "  scgi_temp_path /tmp/preview-nginx-scgi;\n"
                + "  server {\n"
                + "    listen 3000;\n"
                + "    location " + prefix + " {\n"
                + "      proxy_pass http://127.0.0.1:" + JAVA_BACKEND_PORT + ";\n"
                + "      proxy_http_version 1.1;\n"
                + "      proxy_set_header Host $host;\n"
                + "      proxy_set_header X-Forwarded-For $remote_addr;\n"
                + "    }\n"
                + "    location / {\n"
                + "      root " + feBuildDir + ";\n"
                + "      index index.html;\n"
                + "      try_files $uri /index.html;\n"
                + "    }\n"
                + "  }\n"
                + "}\n";
    }

    /** build.gradle(.kts)/pom.xml 이 있는 디렉터리 = Java BE. 못 찾으면 APP_DIR. */
    private String detectBackendDir(String containerId) {
        String found = dockerService.exec(containerId,
                "f=$(find " + APP_DIR + " -maxdepth 2 \\( -name build.gradle -o -name build.gradle.kts "
                        + "-o -name pom.xml \\) -not -path '*/node_modules/*' | head -1); "
                        + "[ -n \"$f\" ] && dirname \"$f\" || echo " + APP_DIR).trim();
        return found.isEmpty() ? APP_DIR : found;
    }

    /** build 스크립트가 있는 package.json 의 디렉터리 = FE. 못 찾으면 APP_DIR. */
    private String detectFrontendDir(String containerId) {
        String script = "node -e \"const {execSync}=require('child_process');"
                + "const fs=require('fs');"
                + "const out=execSync(\\\"find " + APP_DIR + " -maxdepth 2 -name package.json -not -path '*/node_modules/*'\\\").toString().trim().split('\\n').filter(Boolean);"
                + "for(const f of out){try{const p=JSON.parse(fs.readFileSync(f));if(p.scripts&&p.scripts.build){process.stdout.write(require('path').dirname(f));process.exit(0)}}catch(e){}}"
                + "process.stdout.write('" + APP_DIR + "')\" 2>/dev/null";
        String found = dockerService.exec(containerId, script).trim();
        return found.isEmpty() ? APP_DIR : found;
    }

    /** FE 빌드 산출물 디렉터리(baseDir 아래 dist/build/out). 없으면 baseDir 자체를 정적 루트로. */
    private String detectFeBuildDir(String containerId, String baseDir) {
        for (String candidate : List.of(baseDir + "/dist", baseDir + "/build", baseDir + "/out")) {
            if ("exists".equals(dockerService.exec(containerId,
                    "[ -d " + candidate + " ] && echo exists || echo missing").trim())) {
                return candidate;
            }
        }
        return baseDir;
    }

    /** 실패하면 던지는 exec. env 없는 strict 단계용. */
    private void requireExec(String containerId, String command, String what) {
        DockerContainerService.ExecResult r = dockerService.execWithExitCode(containerId, command);
        if (!r.succeeded()) {
            throw new IllegalStateException(what + " 실패(exitCode=" + r.exitCode() + ").");
        }
    }

    /** base64 로 감싸 셸 따옴표·특수문자 문제 없이 파일을 쓴다. */
    private void writeFile(String containerId, String path, String content) {
        String b64 = java.util.Base64.getEncoder()
                .encodeToString(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        dockerService.exec(containerId, "echo '" + b64 + "' | base64 -d > " + path);
    }

    /**
     * 지정 포트가 실제로 응답할 때까지 폴링한다. "프로세스를 띄웠다"와 "포트가 응답한다"는 다르고,
     * 후자가 돼야 다음 단계로 갈 수 있다(3000 이면 프리뷰 ACTIVE, 8080 이면 nginx 앞단 붙이기).
     * curl 이 없는 이미지가 있어 node 로 확인한다 — 프리뷰 컨테이너에는 node 가 반드시 있다.
     */
    private void awaitPortReady(String containerId, int port, int timeoutSeconds,
                                String logPath, String context) {
        String probe = "node -e \"require('http')"
                + ".get({host:'127.0.0.1',port:" + port + ",timeout:1000},r=>process.exit(0))"
                + ".on('error',()=>process.exit(1))\" 2>/dev/null";
        String result = dockerService.exec(containerId,
                "ready=no; "
                        + "for i in $(seq 1 " + timeoutSeconds + "); do "
                        + "if " + probe + "; then ready=yes; break; fi; sleep 1; "
                        + "done; "
                        + "echo \"serve_ready=$ready\"; "
                        + "tail -n 20 " + logPath + " 2>/dev/null || true");

        if (result == null || !result.contains("serve_ready=yes")) {
            log.warn("[PreviewWorkspace] 포트 {} 가 {}초 안에 응답하지 않음 | {} | log={}",
                    port, timeoutSeconds, context, result);
            throw new IllegalStateException(
                    "프리뷰 서버가 제한 시간 안에 시작되지 않았습니다(port=" + port + "). " + context);
        }
        log.info("[PreviewWorkspace] 포트 {} 준비 완료 | {} | log={}", port, context, result);
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
