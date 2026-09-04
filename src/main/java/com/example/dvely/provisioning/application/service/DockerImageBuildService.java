package com.example.dvely.provisioning.application.service;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService.ExecResult;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.provisioning.infrastructure.EcrImageRegistry.EcrAuth;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * DOCKER 배포 모드의 <b>앱(백엔드) 이미지</b>를 만든다 — 앱 소스를 격리 컨테이너로 clone 한 뒤 그 안의
 * {@code Dockerfile}(없으면 스택 감지로 자동생성)로 빌드 컨텍스트를 만들고, {@link ContainerImageBuilder}
 * 로 amd64 이미지를 뽑는다. S3 전달은 이미지 tar 를, ECR 전달은 레지스트리 push 를 한다.
 *
 * <p>native jar 경로({@link NativeBuildService})와 형제 — 스택 무관이 차이다. Dockerfile 만 있으면
 * Node·Java·Next 무엇이든 같은 경로로 이미지가 된다. 프론트(웹) 이미지는 {@link WebImageBuildService}.</p>
 *
 * <p><b>보안:</b> 신뢰할 수 없는 Dockerfile 이 호스트 데몬에서 빌드된다. 단일 테넌트·검증 단계 한정이며
 * 멀티테넌트 운영 전 kaniko/rootless 로 하드닝한다(docs/multi-stack-deploy-design.md).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DockerImageBuildService {

    private static final String APP_DIR = BackendSourceClone.APP_DIR;

    private final DockerContainerService dockerService;
    private final ProjectRepository projectRepository;
    private final BackendSourceClone sourceClone;
    private final ContainerImageBuilder imageBuilder;

    /** 소스를 clone 해 Dockerfile 로 이미지를 빌드·save 한 tar 경로를 돌려준다(S3 전달용). 호출자가 tar 를 지운다. */
    public Path buildImageTar(Long ownerUserId, Long projectId) {
        String imageTag = imageTagFor(projectId);
        Path contextTar = prepareContextTar(ownerUserId, projectId);
        try {
            Path imageTar = imageBuilder.buildTar(contextTar, imageTag);
            log.info("백엔드 이미지 빌드 완료(S3 전달): projectId={} tag={} bytes={}",
                    projectId, imageTag, sizeQuietly(imageTar));
            return imageTar;
        } finally {
            imageBuilder.deleteQuietly(contextTar);
        }
    }

    /** 소스를 clone 해 Dockerfile 로 이미지를 빌드해 ECR 로 직접 push 한다(ECR 전달용). */
    public void buildAndPushImage(Long ownerUserId, Long projectId, EcrAuth auth, String imageRef) {
        Path contextTar = prepareContextTar(ownerUserId, projectId);
        try {
            imageBuilder.buildAndPush(contextTar, imageRef, auth);
            log.info("백엔드 이미지 빌드·푸시 완료(ECR 전달): projectId={} ref={}", projectId, imageRef);
        } finally {
            imageBuilder.deleteQuietly(contextTar);
        }
    }

    /**
     * 앱 소스를 격리 컨테이너로 clone 하고 Dockerfile 을 보장한 뒤, 빌드 컨텍스트 tar 를 호스트로 꺼내
     * 그 경로를 돌려준다(컨테이너는 여기서 정리). S3·ECR 두 전달 경로가 공유. 호출자가 tar 를 지운다.
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
            return sourceClone.tarContextOut(containerId, APP_DIR);
        } finally {
            dockerService.removeContainer(containerId);   // 빌드 컨테이너는 일회용
        }
    }

    /**
     * 앱 루트에 Dockerfile 이 있으면 그대로 쓴다(사용자 Dockerfile 우선). 없으면 스택을 감지해 기본
     * Dockerfile 을 만들어 넣는다(최선노력 폴백). 스택을 못 알아보면 명확한 에러로 실패시킨다.
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
        sourceClone.writeFile(containerId, APP_DIR + "/Dockerfile", DefaultDockerfileFactory.dockerfileFor(stack));
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
        return dockerService.execWithExitCode(containerId, "grep -q " + pattern + " " + path).succeeded();
    }

    /**
     * 이 프로젝트의 앱 이미지 태그. save 할 때와 EC2 에서 {@code docker run} 할 때가 반드시 같아야 하므로
     * (docker load 가 이 태그로 복원한다) 한 곳에서 정한다.
     */
    public static String imageTagFor(Long projectId) {
        return "qeploy-app-" + projectId + ":latest";
    }

    private long sizeQuietly(Path p) {
        try { return Files.size(p); } catch (IOException e) { return -1; }
    }
}
