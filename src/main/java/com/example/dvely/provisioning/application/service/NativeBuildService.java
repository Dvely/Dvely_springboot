package com.example.dvely.provisioning.application.service;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService.ExecResult;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * NATIVE 배포(컨테이너 없이 EC2 에서 직접 실행)의 산출물을 만든다 — 소스를 격리 샌드박스에서 clone·감지해
 * 스택에 맞는 산출물을 낸다. Java(Gradle)는 컨트롤 플레인에서 jar 로 빌드하고, Node 는 소스 tar(node_modules
 * 제외)만 낸다. DOCKER 모드({@link DockerImageBuildService})와 다른 축 — EC2 에 Docker 를 안 쓴다.
 *
 * <p><b>Java vs Node 빌드 위치가 다른 이유(크로스아치):</b> jar 는 JVM 바이트코드라 arch 무관이므로
 * 컨트롤 플레인에서 빌드해 그대로 실행한다. 반면 Node 는 네이티브 애드온(node-gyp)이 <b>설치 arch 에
 * 묶이므로</b>, arm64 컨트롤 플레인에서 만든 node_modules 가 amd64 EC2 에서 안 돈다. 그래서 Node 는
 * 소스만 넘기고 EC2 부팅 때 {@code npm ci} 로 그 arch 에 맞게 설치한다(user-data 가 수행).</p>
 *
 * <p>빌드 컨테이너엔 클라우드 자격을 주지 않는다 — 산출물만 꺼내고 S3 업로드는 러너가 한다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NativeBuildService {

    private static final String APP_DIR = BackendSourceClone.APP_DIR;

    private final DockerContainerService dockerService;
    private final ProjectRepository projectRepository;
    private final BackendSourceClone sourceClone;

    /** NATIVE 실행 런타임. jar(java -jar) vs Node(npm start). */
    public enum NativeRuntime { JAVA, NODE }

    /** 산출물 경로와 그 런타임. runtime 으로 러너가 user-data(실행부)를 고른다. */
    public record NativeArtifact(Path path, NativeRuntime runtime) {}

    /** 소스를 clone·감지해 산출물을 호스트 임시파일로 꺼낸다. Java=jar, Node=소스 tar. 실패는 BackendBuildException. */
    public NativeArtifact build(Long ownerUserId, Long projectId) {
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
            sourceClone.cloneInto(containerId, ownerUserId, sourceRepo);
            String gradleDir = findFirst(containerId, "\\( -name build.gradle -o -name build.gradle.kts \\)");
            if (!gradleDir.isBlank()) {
                return new NativeArtifact(buildJar(containerId, projectId, dirOf(gradleDir)), NativeRuntime.JAVA);
            }
            String pkgJson = findFirst(containerId, "-name package.json");
            if (!pkgJson.isBlank()) {
                return new NativeArtifact(packageNodeSource(containerId, projectId, dirOf(pkgJson)), NativeRuntime.NODE);
            }
            if (!findFirst(containerId, "-name pom.xml").isBlank()) {
                throw new BackendBuildException("NATIVE 는 현재 Gradle·Node 만 지원합니다(Maven 미지원). DOCKER 모드를 쓰세요.");
            }
            throw new BackendBuildException("빌드 파일(build.gradle / package.json)을 찾지 못했습니다.");
        } catch (IOException e) {
            throw new BackendBuildException("산출물 추출 실패: " + e.getMessage());
        } finally {
            dockerService.removeContainer(containerId);   // 빌드 컨테이너는 일회용
        }
    }

    // ── Java: 컨트롤 플레인에서 gradle 빌드 → jar (기존 jar 빌드와 동일 커맨드) ──

    private Path buildJar(String containerId, Long projectId, String backendDir) throws IOException {
        ExecResult build = dockerService.execWithExitCode(containerId,
                "cd " + backendDir + " && chmod +x gradlew 2>/dev/null; "
                        + "./gradlew clean build -x test --no-daemon");
        if (!build.succeeded()) {
            throw new BackendBuildException("gradle 빌드 실패: " + BackendSourceClone.tail(build.output()));
        }
        // build/libs 의 실행가능 jar — -plain.jar(라이브러리)·sources·javadoc 은 제외.
        String jar = dockerService.exec(containerId,
                "find " + APP_DIR + " -path '*/build/libs/*.jar' ! -name '*-plain.jar' "
                        + "! -name '*-sources.jar' ! -name '*-javadoc.jar' | head -1").trim();
        if (jar.isBlank()) {
            throw new BackendBuildException("빌드 산출물 jar 를 찾지 못했습니다.");
        }
        Path dest = Files.createTempFile("qeploy-app-", ".jar");
        dockerService.copyFileFromContainer(containerId, jar, dest);
        log.info("백엔드 jar 빌드 완료(NATIVE/Java): projectId={} jar={} bytes={}", projectId, jar, sizeQuietly(dest));
        return dest;
    }

    // ── Node: 소스만 tar(node_modules 제외) → EC2 에서 npm ci ──

    private Path packageNodeSource(String containerId, Long projectId, String nodeDir) throws IOException {
        // 빌드 방법 조기 검증 — start 스크립트가 없으면 EC2 부팅이 조용히 실패하므로 여기서 명확히 막는다.
        if (!dockerService.execWithExitCode(containerId, "grep -q '\\\"start\\\"' " + nodeDir + "/package.json")
                .succeeded()) {
            throw new BackendBuildException(
                    "package.json 에 \"start\" 스크립트가 없습니다 — NATIVE Node 는 npm start 로 실행합니다.");
        }
        String tarPath = "/tmp/qeploy-app-src.tar";
        ExecResult tar = dockerService.execWithExitCode(containerId,
                "tar --exclude=node_modules --exclude=.git -cf " + tarPath + " -C " + nodeDir + " .");
        if (!tar.succeeded()) {
            throw new BackendBuildException("Node 소스 tar 실패: " + BackendSourceClone.tail(tar.output()));
        }
        Path dest = Files.createTempFile("qeploy-app-src-", ".tar");
        dockerService.copyFileFromContainer(containerId, tarPath, dest);
        log.info("백엔드 소스 패키징 완료(NATIVE/Node): projectId={} dir={} bytes={}", projectId, nodeDir, sizeQuietly(dest));
        return dest;
    }

    /** APP_DIR 아래에서 조건에 맞는 첫 파일 경로(없으면 빈 문자열). 서브디렉터리(maxdepth 3)까지 본다. */
    private String findFirst(String containerId, String predicate) {
        return dockerService.exec(containerId,
                "find " + APP_DIR + " -maxdepth 3 " + predicate + " | head -1").trim();
    }

    private String dirOf(String filePath) {
        return filePath.substring(0, filePath.lastIndexOf('/'));
    }

    private long sizeQuietly(Path p) {
        try { return Files.size(p); } catch (IOException e) { return -1; }
    }
}
