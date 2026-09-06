package com.example.dvely.provisioning.application.service;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.provisioning.infrastructure.EcrImageRegistry.EcrAuth;
import java.nio.file.Path;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 웹(프론트) 컨테이너 이미지를 만든다 — 프론트 소스를 clone 해 nginx 로 정적 서빙 + {@code /api} 프록시하는
 * 이미지를 {@link ContainerImageBuilder} 로 amd64 로 뽑는다. 앱 이미지({@link DockerImageBuildService})의
 * 형제 — 소스가 프론트고 산출물이 nginx 이미지인 점이 다르다.
 *
 * <p>프론트 소스는 두 형태를 다 받는다: <b>split</b>(별도 frontendRepo) / <b>모노</b>(백엔드 레포의
 * frontendDir 하위폴더). 프론트에 자체 Dockerfile 이 있으면 그걸 우선하고, 없으면 nginx.conf·Dockerfile 을
 * 자동생성한다({@link WebAssetsFactory}). SSR(런타임 node)은 대상이 아니다 — 그건 백엔드 DOCKER 경로로.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebImageBuildService {

    private static final String APP_DIR = BackendSourceClone.APP_DIR;

    private final DockerContainerService dockerService;
    private final ProjectRepository projectRepository;
    private final BackendSourceClone sourceClone;
    private final ContainerImageBuilder imageBuilder;

    /** 웹 이미지를 빌드·save 한 tar 경로를 돌려준다(S3 전달용). 호출자가 tar 를 지운다. */
    public Path buildWebImageTar(Long ownerUserId, Long projectId, String frontendRepo,
                                 String frontendDir, String apiPathPrefix, boolean webOnly) {
        String tag = webImageTagFor(projectId);
        Path contextTar = prepareWebContextTar(ownerUserId, projectId, frontendRepo, frontendDir, apiPathPrefix, webOnly);
        try {
            Path imageTar = imageBuilder.buildTar(contextTar, tag);
            log.info("웹 이미지 빌드 완료(S3 전달): projectId={} tag={} webOnly={}", projectId, tag, webOnly);
            return imageTar;
        } finally {
            imageBuilder.deleteQuietly(contextTar);
        }
    }

    /** 웹 이미지를 빌드해 ECR 로 push 한다(ECR 전달용). */
    public void buildAndPushWebImage(Long ownerUserId, Long projectId, String frontendRepo,
                                     String frontendDir, String apiPathPrefix, EcrAuth auth, String imageRef,
                                     boolean webOnly) {
        Path contextTar = prepareWebContextTar(ownerUserId, projectId, frontendRepo, frontendDir, apiPathPrefix, webOnly);
        try {
            imageBuilder.buildAndPush(contextTar, imageRef, auth);
            log.info("웹 이미지 빌드·푸시 완료(ECR 전달): projectId={} ref={} webOnly={}", projectId, imageRef, webOnly);
        } finally {
            imageBuilder.deleteQuietly(contextTar);
        }
    }

    /**
     * 프론트 소스를 clone 하고 nginx 에셋을 보장한 뒤, 프론트 루트를 빌드 컨텍스트 tar 로 꺼낸다. split 은
     * frontendRepo 를, 모노는 백엔드 레포의 frontendDir 를 프론트 루트로 삼는다. webOnly 면 백엔드 프록시
     * 없는 정적 nginx.conf 를 심는다.
     */
    private Path prepareWebContextTar(Long ownerUserId, Long projectId, String frontendRepo,
                                      String frontendDir, String apiPathPrefix, boolean webOnly) {
        // frontendRepo·frontendDir 은 사용자 입력이고 컨테이너 셸 명령(clone·test·tar)에 들어가므로
        // 진입점에서 엄격 검증한다(주입 차단 + 명확한 안내). 통과한 값만 아래로 흘려보낸다.
        validateFrontendRepo(frontendRepo);
        validateFrontendDir(frontendDir);
        Project project = projectRepository.findByIdAndOwnerUserId(projectId, ownerUserId)
                .orElseThrow(() -> new BackendBuildException("프로젝트를 찾을 수 없습니다: " + projectId));
        boolean split = frontendRepo != null && !frontendRepo.isBlank();
        String repo = split ? frontendRepo : project.getSourceRepository();
        if (repo == null || repo.isBlank()) {
            throw new BackendBuildException("프론트 소스 저장소가 없어 웹 이미지를 만들 수 없습니다.");
        }
        String sessionId = "web-" + projectId + "-" + System.currentTimeMillis();
        String containerId = dockerService.createAndStartContainer(
                ownerUserId, sessionId, projectId, null, null);
        try {
            sourceClone.cloneInto(containerId, ownerUserId, repo);
            String frontendRoot = (frontendDir == null || frontendDir.isBlank())
                    ? APP_DIR : APP_DIR + "/" + trimSlashes(frontendDir);
            if (!dockerService.execWithExitCode(containerId, "test -d " + frontendRoot).succeeded()) {
                throw new BackendBuildException(
                        "프론트 디렉터리를 찾을 수 없습니다: " + (frontendDir == null ? "(레포 루트)" : frontendDir));
            }
            ensureWebAssets(containerId, frontendRoot, apiPathPrefix, webOnly);
            return sourceClone.tarContextOut(containerId, frontendRoot);
        } finally {
            dockerService.removeContainer(containerId);
        }
    }

    /**
     * 프론트에 자체 Dockerfile 이 있으면 그대로 쓴다(사용자가 nginx·프록시까지 책임). 없으면 build 스크립트
     * (package.json)가 있어야 nginx.conf + 기본 Dockerfile 을 자동생성한다. 둘 다 없으면 명확히 실패.
     */
    private void ensureWebAssets(String containerId, String frontendRoot, String apiPathPrefix, boolean webOnly) {
        if (dockerService.execWithExitCode(containerId, "test -f " + frontendRoot + "/Dockerfile").succeeded()) {
            log.info("프론트 제공 Dockerfile 사용: dir={}", frontendRoot);
            return;
        }
        if (!dockerService.execWithExitCode(containerId, "test -f " + frontendRoot + "/package.json").succeeded()) {
            throw new BackendBuildException(
                    "프론트 빌드 방법을 찾지 못했습니다 — 프론트 루트에 package.json(build 스크립트) 또는 Dockerfile 이 필요합니다.");
        }
        // 웹 전용은 백엔드 app 이 없어 프록시가 무의미하므로 정적 전용 nginx.conf 를 심는다.
        String nginxConf = webOnly
                ? WebAssetsFactory.nginxConfStaticOnly()
                : WebAssetsFactory.nginxConf(WebAssetsFactory.parsePrefixes(apiPathPrefix));
        sourceClone.writeFile(containerId, frontendRoot + "/nginx.conf", nginxConf);
        sourceClone.writeFile(containerId, frontendRoot + "/Dockerfile", WebAssetsFactory.webDockerfile());
        log.info("웹 Dockerfile·nginx.conf 자동생성: dir={} webOnly={}", frontendRoot, webOnly);
    }

    // 프론트 소스는 사용자 입력이라 clone/test/tar 셸 명령에 들어가기 전에 형식을 못박는다.
    private static final java.util.regex.Pattern REPO_SLUG =
            java.util.regex.Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");   // owner/repo
    private static final java.util.regex.Pattern SAFE_SUBPATH =
            java.util.regex.Pattern.compile("[A-Za-z0-9_./-]+");                   // 레포 하위경로

    static void validateFrontendRepo(String frontendRepo) {
        if (frontendRepo == null || frontendRepo.isBlank()) {
            return;   // 미지정(모노) — 검증 대상 아님
        }
        if (!REPO_SLUG.matcher(frontendRepo).matches() || frontendRepo.contains("..")) {
            throw new BackendBuildException("프론트 저장소 형식이 올바르지 않습니다(owner/repo 만): " + frontendRepo);
        }
    }

    static void validateFrontendDir(String frontendDir) {
        if (frontendDir == null || frontendDir.isBlank()) {
            return;   // 미지정(레포 루트)
        }
        if (!SAFE_SUBPATH.matcher(frontendDir).matches() || frontendDir.contains("..")) {
            throw new BackendBuildException(
                    "프론트 디렉터리 경로가 올바르지 않습니다(영숫자·_/-.만, .. 불가): " + frontendDir);
        }
    }

    /** 이 프로젝트의 웹 이미지 태그. save/pull 과 EC2 의 compose {@code image} 가 반드시 같아야 하므로 한 곳에서 정한다. */
    public static String webImageTagFor(Long projectId) {
        return "qeploy-web-" + projectId + ":latest";
    }

    private static String trimSlashes(String s) {
        String t = s.trim();
        while (t.startsWith("/")) {
            t = t.substring(1);
        }
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }
}
