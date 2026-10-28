package com.example.dvely.provisioning.application.service;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService.ExecResult;
import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.deployment.application.port.out.FrontendStaticHostingPort;
import com.example.dvely.project.domain.repository.ProjectCloudConnectionSettingRepository;
import com.example.dvely.provisioning.infrastructure.S3StaticSiteStore;
import com.example.dvely.agent.infrastructure.docker.ContainerRole;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * {@link FrontendStaticHostingPort} 구현 — 프론트 소스를 격리 도커 샌드박스에서 빌드해(npm run build)
 * 그 정적 산출물을 사용자 S3 정적 웹호스팅 버킷에 올린다. {@link NativeBuildService} 와 같은 빌드
 * 컨테이너 수명주기(생성 → clone → 빌드 → tar 추출 → 제거)를 따르되, 산출물을 EC2 대신 S3 로 보낸다.
 *
 * <p>빌드 컨테이너엔 클라우드 자격을 주지 않는다 — dist 를 tar 로 꺼내고 S3 업로드는 컨트롤 플레인이
 * assume-role 로 한다({@link S3StaticSiteStore}). 백엔드 배포와 같은 BYOC 모델이다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FrontendStaticHostingAdapter implements FrontendStaticHostingPort {

    private static final String APP_DIR = BackendSourceClone.APP_DIR;

    private final DockerContainerService dockerService;
    private final BackendSourceClone sourceClone;
    private final S3StaticSiteStore siteStore;
    private final ProjectCloudConnectionSettingRepository cloudConnectionSettingRepository;
    private final CloudConnectionRepository cloudConnectionRepository;

    @Override
    public String publishToS3(PublishRequest request) {
        CloudConnection connection = resolveConnectedCloud(request.ownerUserId(), request.projectId());

        String sessionId = "site-build-" + request.projectId() + "-" + System.currentTimeMillis();
        // 프론트 번들러(vite/webpack)는 메모리를 꽤 쓴다 — 1GiB 기본으로는 큰 앱이 OOM 날 수 있어 2GiB.
        String containerId = dockerService.createAndStartContainer(
                ContainerRole.BUILD,
                request.ownerUserId(), sessionId, request.projectId(), null, null,
                DockerContainerService.JAVA_MEMORY_LIMIT_BYTES);
        try {
            sourceClone.cloneAt(containerId, request.ownerUserId(), request.sourceRepo(), request.checkoutRef());

            String feDir = detectFrontendDir(containerId);
            requireExec(containerId,
                    "cd " + feDir + " && if [ -f package-lock.json ]; then npm ci; else npm install; fi",
                    "프론트 의존성 설치");
            // S3 도 root(/)에서 서빙하므로 base 를 root 로 강제한다(하위경로 base 로 빌드된 앱의 빈 화면
            // 방지 — 실 e2e 확인). Vite=--base=/, 그 외(CRA 등)=PUBLIC_URL=/. package.json 으로 가른다.
            requireExec(containerId,
                    "cd " + feDir + " && if grep -q '\"vite\"' package.json; then npm run build -- --base=/; "
                            + "else PUBLIC_URL=/ npm run build; fi",
                    "프론트 빌드");
            String buildDir = detectBuildDir(containerId, feDir);

            Path tar = sourceClone.tarContextOut(containerId, buildDir);
            try {
                String bucket = siteStore.bucketNameFor(connection, request.projectId());
                siteStore.ensureWebsiteBucket(connection, bucket);
                siteStore.uploadSiteTar(connection, bucket, tar);
                String url = siteStore.websiteEndpoint(bucket, connection.getRegion());
                log.info("프론트 S3 배포 완료: projectId={} bucket={} url={}", request.projectId(), bucket, url);
                return url;
            } finally {
                deleteQuietly(tar);
            }
        } finally {
            dockerService.removeContainer(containerId);   // 빌드 컨테이너는 일회용
        }
    }

    private CloudConnection resolveConnectedCloud(Long ownerUserId, Long projectId) {
        CloudConnection connection = cloudConnectionSettingRepository.findByProjectId(projectId)
                .flatMap(setting -> cloudConnectionRepository
                        .findByIdAndOwnerUserId(setting.getCloudConnectionId(), ownerUserId))
                .orElseThrow(() -> new NotFoundException(
                        "S3 프론트 배포는 연결된 클라우드가 있어야 합니다. 인프라 탭에서 클라우드 연결을 먼저 선택해주세요."));
        if (connection.getStatus() != CloudConnectionStatus.CONNECTED) {
            throw new IllegalStateException("클라우드 연결이 CONNECTED 상태가 아닙니다. 연결을 확인한 뒤 다시 시도해주세요.");
        }
        return connection;
    }

    /**
     * 빌드 스크립트가 있는 프론트 디렉터리를 찾는다. 루트를 먼저 보고(단일 레포·프론트 루트가 흔함),
     * 없으면 하위(maxdepth 2)에서 {@code scripts.build} 있는 첫 package.json 의 디렉터리(모노레포).
     */
    private String detectFrontendDir(String containerId) {
        if (hasBuildScript(containerId, APP_DIR)) {
            return APP_DIR;
        }
        String found = dockerService.exec(containerId,
                "find " + APP_DIR + " -maxdepth 2 -name package.json -not -path '*/node_modules/*'").trim();
        for (String pkg : found.split("\\n")) {
            if (pkg.isBlank()) {
                continue;
            }
            String dir = pkg.substring(0, pkg.lastIndexOf('/'));
            if (hasBuildScript(containerId, dir)) {
                return dir;
            }
        }
        throw new BackendBuildException(
                "빌드 스크립트(scripts.build)가 있는 package.json 을 찾지 못했습니다 — 프론트 프로젝트가 맞는지 확인해주세요.");
    }

    private boolean hasBuildScript(String containerId, String dir) {
        return dockerService.execWithExitCode(containerId,
                "test -f " + dir + "/package.json && grep -q '\"build\"' " + dir + "/package.json").succeeded();
    }

    private String detectBuildDir(String containerId, String feDir) {
        for (String candidate : List.of(feDir + "/dist", feDir + "/build", feDir + "/out")) {
            if (dockerService.execWithExitCode(containerId, "[ -d " + candidate + " ]").succeeded()) {
                return candidate;
            }
        }
        throw new BackendBuildException(
                "빌드 산출물 디렉터리(dist/build/out)를 찾지 못했습니다 — 빌드가 산출물을 냈는지 확인해주세요.");
    }

    private void requireExec(String containerId, String command, String label) {
        ExecResult result = dockerService.execWithExitCode(containerId, command);
        if (!result.succeeded()) {
            throw new BackendBuildException(label + " 실패: " + BackendSourceClone.tail(result.output()));
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("정적 사이트 tar 임시파일 삭제 실패(무시): {}", path);
        }
    }
}
