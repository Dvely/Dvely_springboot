package com.example.dvely.provisioning.application.service;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService.ExecResult;
import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 배포 대상 소스를 격리 샌드박스 컨테이너로 안전하게 clone 한다. jar 빌드(native)와 이미지 빌드(DOCKER)
 * 두 배포 경로가 공유한다 — 보안에 민감한 부분(토큰을 URL·명령줄에 안 넣고 credential helper 파일에만
 * 기록)이라 한 곳에만 두어 두 경로가 드리프트하지 않게 한다.
 */
@Component
@RequiredArgsConstructor
public class BackendSourceClone {

    /** clone 대상 경로. 두 빌드 서비스가 이 경로를 기준으로 산출물을 찾는다. */
    public static final String APP_DIR = "/workspace/app";
    private static final String GIT_NO_PROMPT = "GIT_TERMINAL_PROMPT=0 ";

    private final DockerContainerService dockerService;
    private final UserRepository userRepository;
    private final AuthCommandService authCommandService;

    /** 컨테이너 안 {@link #APP_DIR} 로 소스를 clone 한다. 실패는 {@link BackendBuildException}. */
    public void cloneInto(String containerId, Long ownerUserId, String sourceRepo) {
        User user = userRepository.findById(ownerUserId)
                .orElseThrow(() -> new BackendBuildException("유저를 찾을 수 없습니다: " + ownerUserId));
        if (user.isUserAccessTokenExpired()) {
            authCommandService.refreshGithubUserToken(ownerUserId);
            user = userRepository.findById(ownerUserId).orElseThrow();
        }
        String userToken = user.getGithubUserAccessToken();
        String username = user.getUsername();

        // 토큰을 URL·명령줄에 넣지 않는다(프로세스 목록·로그 유출 방지) — credential helper 파일에만.
        dockerService.exec(containerId, "apk add --no-cache git openjdk21 2>/dev/null || true");
        String cred = "https://" + username + ":" + userToken + "@github.com";
        String credB64 = Base64.getEncoder().encodeToString(cred.getBytes(StandardCharsets.UTF_8));
        dockerService.exec(containerId,
                "node -e \"require('fs').writeFileSync('/tmp/.git-credentials', Buffer.from('"
                        + credB64 + "', 'base64').toString('utf8'))\"");
        dockerService.exec(containerId, "git config --global credential.helper 'store --file /tmp/.git-credentials'");
        dockerService.exec(containerId, "mkdir -p /workspace");
        // 운영 배포는 기본 브랜치(main 등)를 받는다 — 프리뷰(preview 브랜치)와 다르다.
        ExecResult clone = dockerService.execWithExitCode(containerId,
                GIT_NO_PROMPT + "git clone --depth 1 https://github.com/" + sourceRepo + ".git " + APP_DIR);
        if (!clone.succeeded()) {
            throw new BackendBuildException("소스 clone 실패: " + tail(clone.output()));
        }
    }

    /**
     * 멀티라인 파일을 컨테이너 안에 base64 로 안전하게 기록한다(따옴표·개행이 셸에서 깨지지 않게).
     * 이미지 빌드 서비스들이 Dockerfile·nginx.conf 같은 생성물을 컨텍스트에 심을 때 쓴다.
     */
    public void writeFile(String containerId, String path, String content) {
        assertSafeContainerPath(path, "파일 경로");   // path 가 셸 문자열에 들어가므로 주입 차단
        String b64 = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        ExecResult w = dockerService.execWithExitCode(containerId, "echo " + b64 + " | base64 -d > " + path);
        if (!w.succeeded()) {
            throw new BackendBuildException("파일 기록 실패(" + path + "): " + tail(w.output()));
        }
    }

    // 컨테이너 경로가 sh -c 문자열에 연결되므로, 셸 메타문자·상위경로 이탈을 막는 엄격 화이트리스트.
    // 정상 경로(/workspace/app/frontend 등)는 통과하고, 세미콜론·파이프·$()·공백·`..` 는 거른다.
    private static final java.util.regex.Pattern SAFE_CONTAINER_PATH =
            java.util.regex.Pattern.compile("[A-Za-z0-9_./-]+");

    private static void assertSafeContainerPath(String path, String what) {
        if (path == null || path.isBlank() || !SAFE_CONTAINER_PATH.matcher(path).matches()
                || path.contains("..")) {
            throw new BackendBuildException("안전하지 않은 " + what + "입니다: " + path);
        }
    }

    /**
     * 컨테이너 안 {@code dir} 를 빌드 컨텍스트 tar 로 묶어 호스트 임시파일로 꺼내 그 경로를 돌려준다
     * (이후 빌드는 호스트 buildx 가 이 tar 로 한다). 호출자는 반환된 tar 를 반드시 지운다.
     */
    public Path tarContextOut(String containerId, String dir) {
        assertSafeContainerPath(dir, "컨텍스트 디렉터리");   // dir 이 셸 문자열에 들어가므로 주입 차단
        String ctxTar = "/tmp/qeploy-ctx.tar";
        ExecResult tar = dockerService.execWithExitCode(containerId, "tar -cf " + ctxTar + " -C " + dir + " .");
        if (!tar.succeeded()) {
            throw new BackendBuildException("빌드 컨텍스트 tar 실패: " + tail(tar.output()));
        }
        Path host;
        try {
            host = Files.createTempFile("qeploy-ctx-", ".tar");
        } catch (IOException e) {
            throw new BackendBuildException("컨텍스트 임시파일 생성 실패: " + e.getMessage());
        }
        try {
            dockerService.copyFileFromContainer(containerId, ctxTar, host);
        } catch (RuntimeException e) {
            try { Files.deleteIfExists(host); } catch (IOException ignore) { }   // 복사 실패 시 잔여 tar 정리
            throw e;
        }
        return host;
    }

    static String tail(String s) {
        if (s == null) return "";
        String t = s.strip();
        return t.length() > 800 ? t.substring(t.length() - 800) : t;
    }
}
