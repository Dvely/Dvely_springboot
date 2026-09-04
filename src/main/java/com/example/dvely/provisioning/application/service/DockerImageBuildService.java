package com.example.dvely.provisioning.application.service;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService.ExecResult;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
     * 소스를 clone 해 Dockerfile 로 이미지를 빌드·save 한 tar 경로를 돌려준다. 실패는 BackendBuildException.
     * 로컬 이미지·빌드 컨테이너는 정리한다(호출자는 tar 만 받아 S3 업로드 후 지운다).
     */
    public Path buildImageTar(Long ownerUserId, Long projectId) {
        Project project = projectRepository.findByIdAndOwnerUserId(projectId, ownerUserId)
                .orElseThrow(() -> new BackendBuildException("프로젝트를 찾을 수 없습니다: " + projectId));
        String sourceRepo = project.getSourceRepository();
        if (sourceRepo == null || sourceRepo.isBlank()) {
            throw new BackendBuildException("연결된 GitHub 저장소가 없어 빌드할 수 없습니다.");
        }

        String imageTag = imageTagFor(projectId);
        String sessionId = "img-" + projectId + "-" + System.currentTimeMillis();
        String containerId = dockerService.createAndStartContainer(
                ownerUserId, sessionId, projectId, null, null);
        Path contextTar = null;
        try {
            sourceClone.cloneInto(containerId, ownerUserId, sourceRepo);
            ensureDockerfile(containerId);

            // 컨텍스트 tar(루트에 Dockerfile) 를 컨테이너 안에서 만든 뒤 호스트로 꺼낸다.
            ExecResult tar = dockerService.execWithExitCode(containerId,
                    "tar -cf " + CONTEXT_TAR + " -C " + APP_DIR + " .");
            if (!tar.succeeded()) {
                throw new BackendBuildException("빌드 컨텍스트 tar 실패: " + BackendSourceClone.tail(tar.output()));
            }
            contextTar = Files.createTempFile("qeploy-ctx-", ".tar");
            dockerService.copyFileFromContainer(containerId, CONTEXT_TAR, contextTar);

            Path imageTar = Files.createTempFile("qeploy-image-", ".tar");
            buildAndExportImage(contextTar, imageTag, imageTar);
            log.info("백엔드 이미지 빌드 완료: projectId={} tag={} platform={} bytes={}",
                    projectId, imageTag, TARGET_PLATFORM, sizeQuietly(imageTar));
            return imageTar;
        } catch (IOException e) {
            throw new BackendBuildException("이미지 컨텍스트 처리 실패: " + e.getMessage());
        } finally {
            if (contextTar != null) {
                try { Files.deleteIfExists(contextTar); } catch (IOException ignored) { }
            }
            dockerService.removeContainer(containerId);   // 빌드 컨테이너는 일회용
        }
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

    /** CLI 실행. stdinFile 이 있으면 stdin 으로(빌드 컨텍스트 tar). 비-0 이면 BackendBuildException. */
    private void runCli(String[] cmd, Path stdinFile, String what) {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (stdinFile != null) {
            pb.redirectInput(stdinFile.toFile());
        }
        try {
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = p.waitFor();
            if (code != 0) {
                throw new BackendBuildException(what + " 실패(exit " + code + "): " + BackendSourceClone.tail(out));
            }
        } catch (IOException e) {
            throw new BackendBuildException(what + " 실행 실패: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackendBuildException(what + " 중단");
        }
    }

    /** 앱 루트에 Dockerfile 이 있어야 한다(없으면 스택 감지·기본 Dockerfile 생성은 후속 조각). */
    private void ensureDockerfile(String containerId) {
        ExecResult check = dockerService.execWithExitCode(containerId,
                "test -f " + APP_DIR + "/Dockerfile");
        if (!check.succeeded()) {
            throw new BackendBuildException(
                    "저장소 루트에 Dockerfile 이 없습니다. DOCKER 배포 모드는 Dockerfile 이 필요합니다.");
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
