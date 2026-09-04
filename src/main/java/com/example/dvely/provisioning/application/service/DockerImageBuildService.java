package com.example.dvely.provisioning.application.service;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService.ExecResult;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.provisioning.infrastructure.EcrImageRegistry.EcrAuth;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * DOCKER 배포 모드의 산출물을 만든다 — 앱 소스를 격리 컨테이너로 clone 한 뒤 그 안의 {@code Dockerfile}
 * 로 <b>호스트 데몬에서</b> 이미지를 빌드하고, 이미지를 tar 로 save 해 호스트 임시파일 경로를 돌려준다.
 * 러너는 그 tar 를 S3 로 올리고(멀티파트), EC2 는 인스턴스 역할로 받아 {@code docker load}+{@code run} 한다.
 *
 * <p>native jar 경로({@link BackendJarBuildService})와 형제 — 스택 무관이 차이다. Dockerfile 만 있으면
 * Node·Java·Next 무엇이든 같은 경로로 이미지가 된다.</p>
 *
 * <p><b>보안:</b> 신뢰할 수 없는 Dockerfile 이 호스트 데몬에서 빌드된다. 단일 테넌트·검증 단계 한정이며
 * 멀티테넌트 운영 전 kaniko/rootless 로 하드닝한다(docs/multi-stack-deploy-design.md).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DockerImageBuildService {

    private static final String APP_DIR = BackendSourceClone.APP_DIR;
    private static final String CONTEXT_TAR = "/tmp/qeploy-ctx.tar";
    // 배포 대상 EC2 아키텍처(t3.* = amd64). buildx 로 이 플랫폼을 강제해 컨트롤 플레인 arch 와 무관하게
    // amd64 이미지를 만든다 — 안 그러면 arm64 컨트롤 플레인에서 arm64 이미지가 나와 EC2 에서 안 뜬다.
    private static final String TARGET_PLATFORM = "linux/amd64";

    private final DockerContainerService dockerService;
    private final ProjectRepository projectRepository;
    private final BackendSourceClone sourceClone;

    /**
     * 소스를 clone 해 Dockerfile 로 이미지를 빌드·save 한 tar 경로를 돌려준다(S3 전달용). 실패는
     * BackendBuildException. 로컬 이미지는 정리한다(호출자는 tar 만 받아 S3 업로드 후 지운다).
     */
    public Path buildImageTar(Long ownerUserId, Long projectId) {
        String imageTag = imageTagFor(projectId);
        Path contextTar = prepareContextTar(ownerUserId, projectId);
        try {
            Path imageTar = Files.createTempFile("qeploy-image-", ".tar");
            buildAndExportImage(contextTar, imageTag, imageTar);
            log.info("백엔드 이미지 빌드 완료(S3 전달): projectId={} tag={} platform={} bytes={}",
                    projectId, imageTag, TARGET_PLATFORM, sizeQuietly(imageTar));
            return imageTar;
        } catch (IOException e) {
            throw new BackendBuildException("이미지 tar 생성 실패: " + e.getMessage());
        } finally {
            deleteQuietly(contextTar);
        }
    }

    /**
     * 소스를 clone 해 Dockerfile 로 이미지를 빌드해 <b>ECR 로 직접 push</b> 한다(ECR 전달용). S3 를 거치지
     * 않는다 — 컨트롤 플레인이 {@code docker login} 후 buildx {@code --push} 로 바로 올리고, EC2 는 인스턴스
     * 역할로 pull 한다. imageRef 는 {registry}/{repo}:latest 로, EC2 의 {@code docker run} 태그와 같아야 한다.
     */
    public void buildAndPushImage(Long ownerUserId, Long projectId, EcrAuth auth, String imageRef) {
        Path contextTar = prepareContextTar(ownerUserId, projectId);
        try {
            dockerLogin(auth);
            try {
                runCli(new String[]{"docker", "buildx", "build", "--platform", TARGET_PLATFORM,
                        "-t", imageRef, "--push", "-"}, contextTar, "이미지 빌드·푸시(buildx)");
                log.info("백엔드 이미지 빌드·푸시 완료(ECR 전달): projectId={} ref={} platform={}",
                        projectId, imageRef, TARGET_PLATFORM);
            } finally {
                runCliQuiet(new String[]{"docker", "logout", auth.registry()});
            }
        } finally {
            deleteQuietly(contextTar);
        }
    }

    /**
     * 앱 소스를 격리 컨테이너로 clone 하고 Dockerfile 을 보장한 뒤, 빌드 컨텍스트 tar 를 호스트 임시파일로
     * 꺼내 그 경로를 돌려준다(컨테이너는 여기서 정리한다 — 이후 빌드는 호스트 buildx 가 tar 로 한다).
     * S3·ECR 두 전달 경로가 공유한다. 호출자는 반환된 tar 를 반드시 지운다.
     */
    private Path prepareContextTar(Long ownerUserId, Long projectId) {
        Project project = projectRepository.findByIdAndOwnerUserId(projectId, ownerUserId)
                .orElseThrow(() -> new BackendBuildException("프로젝트를 찾을 수 없습니다: " + projectId));
        String sourceRepo = project.getSourceRepository();
        if (sourceRepo == null || sourceRepo.isBlank()) {
            throw new BackendBuildException("연결된 GitHub 저장소가 없어 빌드할 수 없습니다.");
        }
        String sessionId = "img-" + projectId + "-" + System.currentTimeMillis();
        String containerId = dockerService.createAndStartContainer(
                ownerUserId, sessionId, projectId, null, null);
        try {
            sourceClone.cloneInto(containerId, ownerUserId, sourceRepo);
            ensureDockerfile(containerId);
            ExecResult tar = dockerService.execWithExitCode(containerId,
                    "tar -cf " + CONTEXT_TAR + " -C " + APP_DIR + " .");
            if (!tar.succeeded()) {
                throw new BackendBuildException("빌드 컨텍스트 tar 실패: " + BackendSourceClone.tail(tar.output()));
            }
            Path contextTar = Files.createTempFile("qeploy-ctx-", ".tar");
            try {
                dockerService.copyFileFromContainer(containerId, CONTEXT_TAR, contextTar);
            } catch (RuntimeException e) {
                deleteQuietly(contextTar);   // 복사 실패 시 방금 만든 임시 tar 를 남기지 않는다
                throw e;
            }
            return contextTar;
        } catch (IOException e) {
            throw new BackendBuildException("이미지 컨텍스트 처리 실패: " + e.getMessage());
        } finally {
            dockerService.removeContainer(containerId);   // 빌드 컨테이너는 일회용
        }
    }

    /** ECR 자격으로 docker login. 비밀번호는 stdin 으로만 준다(프로세스 목록·로그 유출 방지). */
    private void dockerLogin(EcrAuth auth) {
        runCliWithStdin(new String[]{"docker", "login", "--username", auth.username(), "--password-stdin",
                auth.registry()}, auth.password().getBytes(StandardCharsets.UTF_8), "ECR docker login");
    }

    /**
     * buildx 로 컨텍스트 tar 를 EC2 아키텍처(amd64)로 빌드해 docker-loadable tar 로 뽑는다. docker-java 의
     * legacy builder 는 크로스빌드를 못 해(호스트 arch 로만) buildx(buildkit+QEMU)를 쓴다. 빌드는 데몬에
     * load 한 뒤 save→로컬 이미지 삭제로 tar 만 남긴다(EC2 는 이 tar 를 docker load 한다).
     */
    private void buildAndExportImage(Path contextTar, String imageTag, Path outTar) {
        runCli(new String[]{"docker", "buildx", "build", "--platform", TARGET_PLATFORM,
                "-t", imageTag, "--load", "-"}, contextTar, "이미지 빌드(buildx)");
        try {
            runCli(new String[]{"docker", "save", imageTag, "-o", outTar.toString()}, null, "이미지 save");
        } finally {
            try {
                runCli(new String[]{"docker", "rmi", "-f", imageTag}, null, "로컬 이미지 삭제");
            } catch (RuntimeException ignore) {
                log.warn("빌드 이미지 로컬 삭제 실패(무시): {}", imageTag);
            }
        }
    }

    /** CLI 실행. stdinFile 을 stdin 으로 스트리밍한다(빌드 컨텍스트 tar — 대용량이라 파일 리다이렉트). */
    private void runCli(String[] cmd, Path stdinFile, String what) {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (stdinFile != null) {
            pb.redirectInput(stdinFile.toFile());
        }
        try {
            Process p = pb.start();
            waitAndCheck(p, what);
        } catch (IOException e) {
            throw new BackendBuildException(what + " 실행 실패: " + e.getMessage());
        }
    }

    /** CLI 실행. stdin 바이트를 써 넣고 닫는다(작은 비밀 — 예: docker login 비밀번호). */
    private void runCliWithStdin(String[] cmd, byte[] stdin, String what) {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        try {
            Process p = pb.start();
            try (var os = p.getOutputStream()) {
                os.write(stdin);
            }
            waitAndCheck(p, what);
        } catch (IOException e) {
            throw new BackendBuildException(what + " 실행 실패: " + e.getMessage());
        }
    }

    /** best-effort CLI(정리용 — 예: docker logout). 실패해도 던지지 않고 경고만. */
    private void runCliQuiet(String[] cmd) {
        try {
            new ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor();
        } catch (IOException e) {
            log.warn("정리 명령 실패(무시): {}", String.join(" ", cmd));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 출력을 다 읽고 종료코드를 확인한다(출력 소비가 있어야 파이프가 안 막힌다). 비-0 이면 예외. */
    private void waitAndCheck(Process p, String what) {
        try {
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = p.waitFor();
            if (code != 0) {
                throw new BackendBuildException(what + " 실패(exit " + code + "): " + BackendSourceClone.tail(out));
            }
        } catch (IOException e) {
            throw new BackendBuildException(what + " 출력 읽기 실패: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackendBuildException(what + " 중단");
        }
    }

    private void deleteQuietly(Path p) {
        if (p != null) {
            try { Files.deleteIfExists(p); } catch (IOException ignored) { }
        }
    }

    /**
     * 앱 루트에 Dockerfile 이 있으면 그대로 쓴다(사용자 Dockerfile 우선). 없으면 스택을 감지해 기본
     * Dockerfile 을 만들어 넣는다(최선노력 폴백). 스택을 못 알아보면 명확한 에러로 실패시킨다 —
     * 조용히 깨진 이미지를 만들지 않는다.
     */
    private void ensureDockerfile(String containerId) {
        if (dockerService.execWithExitCode(containerId, "test -f " + APP_DIR + "/Dockerfile").succeeded()) {
            log.info("사용자 제공 Dockerfile 사용: dir={}", APP_DIR);
            return;
        }
        DefaultDockerfileFactory.Stack stack = detectStack(containerId);
        if (stack == null) {
            throw new BackendBuildException(
                    "저장소 루트에 Dockerfile 이 없고 스택도 감지하지 못했습니다(Gradle·Maven·Node·Next 아님). "
                            + "Dockerfile 을 추가해 주세요.");
        }
        String content = DefaultDockerfileFactory.dockerfileFor(stack);
        writeFile(containerId, APP_DIR + "/Dockerfile", content);
        log.info("Dockerfile 자동생성(폴백): stack={} dir={}", stack, APP_DIR);
    }

    /** 컨테이너 안 앱 루트의 마커 파일로 스택을 감지한다(없으면 null). */
    private DefaultDockerfileFactory.Stack detectStack(String containerId) {
        boolean gradle = exists(containerId, APP_DIR + "/build.gradle")
                || exists(containerId, APP_DIR + "/build.gradle.kts");
        boolean maven = exists(containerId, APP_DIR + "/pom.xml");
        boolean packageJson = exists(containerId, APP_DIR + "/package.json");
        boolean next = exists(containerId, APP_DIR + "/next.config.js")
                || exists(containerId, APP_DIR + "/next.config.mjs")
                || exists(containerId, APP_DIR + "/next.config.ts")
                || (packageJson && grepQuiet(containerId, "\\\"next\\\"", APP_DIR + "/package.json"));
        return DefaultDockerfileFactory.decide(gradle, maven, packageJson, next);
    }

    private boolean exists(String containerId, String path) {
        return dockerService.execWithExitCode(containerId, "test -f " + path).succeeded();
    }

    /** package.json 에 next 의존성이 있는지(따옴표째 매칭). 파일은 존재가 보장된 뒤에만 호출한다. */
    private boolean grepQuiet(String containerId, String pattern, String path) {
        return dockerService.execWithExitCode(containerId,
                "grep -q " + pattern + " " + path).succeeded();
    }

    /** 멀티라인 파일을 base64 로 안전하게 기록한다(따옴표·개행이 셸에서 깨지지 않게). */
    private void writeFile(String containerId, String path, String content) {
        String b64 = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        ExecResult w = dockerService.execWithExitCode(containerId,
                "echo " + b64 + " | base64 -d > " + path);
        if (!w.succeeded()) {
            throw new BackendBuildException("기본 Dockerfile 기록 실패: " + BackendSourceClone.tail(w.output()));
        }
    }

    /**
     * 이 프로젝트의 이미지 태그. save 할 때와 EC2 에서 {@code docker run} 할 때가 반드시 같아야 하므로
     * (docker load 가 이 태그로 복원한다) 한 곳에서 정한다.
     */
    public static String imageTagFor(Long projectId) {
        return "qeploy-app-" + projectId + ":latest";
    }

    private long sizeQuietly(Path p) {
        try { return Files.size(p); } catch (IOException e) { return -1; }
    }
}
