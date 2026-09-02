package com.example.dvely.provisioning.application.service;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService.ExecResult;
import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 프로젝트 소스를 격리된 샌드박스 컨테이너에서 빌드해 jar 를 꺼낸다. 온-인스턴스 빌드(옵션 A)의
 * OOM·느린 첫 부팅을 피하려는 것 — 프리뷰 JAVA_FULLSTACK 이 쓰는 것과 같은 2GiB 컨테이너에서 빌드하고,
 * EC2 인스턴스는 완성된 jar 만 받아 실행한다.
 *
 * <p><b>빌드 컨테이너에는 클라우드 자격을 주지 않는다.</b> jar 를 컨트롤 플레인으로 꺼낸 뒤
 * (copyFileFromContainer) S3 업로드는 러너가 한다 — 신뢰할 수 없는 사용자 코드가 도는 컨테이너가
 * AWS 자격을 보지 못하게 하기 위함이다. clone 은 프리뷰와 같은 안전 방식(토큰을 URL·명령줄에 넣지
 * 않고 credential helper 파일에만 base64 로 기록).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackendJarBuildService {

    private static final String APP_DIR = "/workspace/app";
    private static final String GIT_NO_PROMPT = "GIT_TERMINAL_PROMPT=0 ";

    private final DockerContainerService dockerService;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final AuthCommandService authCommandService;

    /** 소스를 빌드해 jar 를 호스트 임시파일로 꺼내 그 경로를 돌려준다. 실패는 BackendBuildException. */
    public Path buildJar(Long ownerUserId, Long projectId) {
        Project project = projectRepository.findByIdAndOwnerUserId(projectId, ownerUserId)
                .orElseThrow(() -> new BackendBuildException("프로젝트를 찾을 수 없습니다: " + projectId));
        String sourceRepo = project.getSourceRepository();
        if (sourceRepo == null || sourceRepo.isBlank()) {
            throw new BackendBuildException("연결된 GitHub 저장소가 없어 빌드할 수 없습니다.");
        }

        String sessionId = "build-" + projectId + "-" + System.currentTimeMillis();
        String containerId = dockerService.createAndStartContainer(
                ownerUserId, sessionId, projectId, null, null,
                DockerContainerService.JAVA_MEMORY_LIMIT_BYTES);
        try {
            cloneRepo(containerId, ownerUserId, sourceRepo);
            runGradleBuild(containerId);
            String jarPath = locateJar(containerId);
            Path dest = Files.createTempFile("qeploy-app-", ".jar");
            dockerService.copyFileFromContainer(containerId, jarPath, dest);
            log.info("백엔드 jar 빌드 완료: projectId={} jar={} bytes={}",
                    projectId, jarPath, sizeQuietly(dest));
            return dest;
        } catch (IOException e) {
            throw new BackendBuildException("jar 추출 실패: " + e.getMessage());
        } finally {
            dockerService.removeContainer(containerId);   // 빌드 컨테이너는 일회용
        }
    }

    private void cloneRepo(String containerId, Long ownerUserId, String sourceRepo) {
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

    private void runGradleBuild(String containerId) {
        String backendDir = detectBackendDir(containerId);
        ExecResult build = dockerService.execWithExitCode(containerId,
                "cd " + backendDir + " && chmod +x gradlew 2>/dev/null; "
                        + "./gradlew clean build -x test --no-daemon");
        if (!build.succeeded()) {
            throw new BackendBuildException("gradle 빌드 실패: " + tail(build.output()));
        }
    }

    /** build.gradle(.kts) 위치를 찾아 그 디렉터리를 쓴다. pom.xml 만 있으면 현재 미지원. */
    private String detectBackendDir(String containerId) {
        String found = dockerService.exec(containerId,
                "find " + APP_DIR + " -maxdepth 3 \\( -name build.gradle -o -name build.gradle.kts \\) "
                        + "| head -1").trim();
        if (found.isBlank()) {
            String hasPom = dockerService.exec(containerId,
                    "find " + APP_DIR + " -maxdepth 3 -name pom.xml | head -1").trim();
            if (!hasPom.isBlank()) {
                throw new BackendBuildException("현재 Gradle 프로젝트만 지원합니다(Maven 미지원).");
            }
            throw new BackendBuildException("빌드 파일(build.gradle)을 찾지 못했습니다.");
        }
        return found.substring(0, found.lastIndexOf('/'));
    }

    /** build/libs 의 실행가능 jar. -plain.jar(Spring Boot 의 비실행 jar)는 제외한다. */
    private String locateJar(String containerId) {
        // find 로 재귀 탐색한다(sh 는 ** globstar 가 기본 꺼져 있어 서브디렉터리 jar 를 놓친다).
        // Spring Boot 의 실행 jar 만 — -plain.jar(라이브러리 jar)와 sources/javadoc 은 제외.
        String jar = dockerService.exec(containerId,
                "find " + APP_DIR + " -path '*/build/libs/*.jar' ! -name '*-plain.jar' "
                        + "! -name '*-sources.jar' ! -name '*-javadoc.jar' | head -1").trim();
        if (jar.isBlank()) {
            throw new BackendBuildException("빌드 산출물 jar 를 찾지 못했습니다.");
        }
        return jar;
    }

    private long sizeQuietly(Path p) {
        try { return Files.size(p); } catch (IOException e) { return -1; }
    }

    private String tail(String s) {
        if (s == null) return "";
        String t = s.strip();
        return t.length() > 800 ? t.substring(t.length() - 800) : t;
    }
}
