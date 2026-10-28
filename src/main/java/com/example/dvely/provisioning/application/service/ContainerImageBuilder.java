package com.example.dvely.provisioning.application.service;

import com.example.dvely.provisioning.infrastructure.EcrImageRegistry.EcrAuth;
import com.example.dvely.provisioning.infrastructure.config.Ec2ProvisioningProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 빌드 컨텍스트 tar 하나를 받아 EC2 아키텍처(amd64) 이미지를 만든다. 앱 이미지
 * ({@link DockerImageBuildService})와 웹 이미지({@link WebImageBuildService})가 공유한다.
 *
 * <p><b>빌드 격리 방식</b>({@code qeploy.provisioning.ec2.build-isolation}):
 * <ul>
 *   <li><b>KANIKO</b>(기본, #507): 빌드가 <b>호스트 데몬이 아니라 격리된 kaniko 컨테이너 안</b>에서 돈다.
 *       컨트롤 플레인 arch 로만 빌드하므로 amd64 가 아닌 컨트롤 플레인에서는 거부한다.</li>
 *   <li><b>BUILDX</b>(명시할 때만): 호스트 buildkit(daemon)로 빌드. 크로스빌드(arm64 컨트롤 플레인→amd64) 지원.
 *       사용자 Dockerfile 의 빌드 스텝이 호스트 buildkit 에서 돈다 — 개발기용.</li>
 *   <li>buildx 가 없을 때의 호스트 {@code docker build} 는 {@code allow-native-build=true} 를 명시했을 때만.</li>
 * </ul>
 * 두 방식 모두 S3 전달은 이미지 tar 를, ECR 전달은 레지스트리 push 를 한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContainerImageBuilder {

    // 배포 대상 EC2 아키텍처(t3.* = amd64). BUILDX 는 이 플랫폼을 강제(크로스빌드), KANIKO 는 컨트롤 플레인 arch.
    static final String TARGET_PLATFORM = "linux/amd64";
    /**
     * kaniko executor. 원 프로젝트(GoogleContainerTools/kaniko)는 2025-06 아카이브돼 갱신이 끊겨, 유지되는 포크
     * osscontainertools/kaniko 를 digest 로 고정한다(#507). 태그가 아니라 digest 라 같은 이름에 다른 이미지가
     * 올라와도 바뀌지 않는다. v1.29.0(2026-10-25), 멀티 아키텍처 인덱스 digest. 올릴 때는 새 digest 와 cosign
     * 서명 검증 결과를 함께 남긴다.
     */
    static final String KANIKO_IMAGE =
            "ghcr.io/osscontainertools/kaniko@sha256:0c0b6c02991bba44090fad918ae177d193e1bd3fd0ccc643e7cc1f9f1a5a373d";

    private final Ec2ProvisioningProperties properties;

    /** 컨텍스트 tar 를 빌드해 docker-loadable tar 로 뽑는다(S3 전달용). 반환 tar 는 호출자가 지운다. */
    public Path buildTar(Path contextTar, String imageTag) {
        return switch (mode()) {
            case KANIKO -> kanikoBuildTar(contextTar, imageTag);
            case BUILDX -> buildxBuildTar(contextTar, imageTag);
            case NATIVE -> nativeBuildTar(contextTar, imageTag);
        };
    }

    /** 컨텍스트 tar 를 빌드해 ECR 로 직접 push 한다(ECR 전달용). */
    public void buildAndPush(Path contextTar, String imageRef, EcrAuth auth) {
        Mode mode = mode();
        switch (mode) {
            case KANIKO -> kanikoBuildAndPush(contextTar, imageRef, auth);
            case BUILDX, NATIVE -> buildxBuildAndPush(contextTar, imageRef, auth, mode);
        }
    }

    enum Mode { KANIKO, BUILDX, NATIVE }

    private Mode mode() {
        boolean kaniko = properties.useKaniko();
        return resolveMode(kaniko, properties.allowNativeBuildFallback(), !kaniko && buildxAvailable(),
                controlPlaneMatchesTarget(), System.getProperty("os.arch"));
    }

    /**
     * 어떤 방식으로 빌드할지(#507). 호스트 데몬에서 사용자 Dockerfile 을 도는 길(BUILDX·NATIVE)은 명시 설정으로만 연다.
     *
     * @throws BackendBuildException 이 환경에서 안전하게 amd64 이미지를 만들 방법이 없다
     */
    static Mode resolveMode(boolean kaniko, boolean allowNative, boolean buildx, boolean amd64, String arch) {
        if (kaniko) {
            if (!amd64) {
                throw new BackendBuildException("이미지 빌드 실패: kaniko 는 컨트롤 플레인 아키텍처(" + arch
                        + ")로만 빌드해 배포 대상(amd64)용 이미지를 만들 수 없습니다. 이 환경에서는 "
                        + "build-isolation=BUILDX 와 docker-buildx-plugin 을 설정하세요.");
            }
            return Mode.KANIKO;
        }
        if (buildx) {
            return Mode.BUILDX;
        }
        if (!amd64) {
            throw new BackendBuildException(requireBuildxMessage(arch));
        }
        if (!allowNative) {
            throw new BackendBuildException("이미지 빌드 실패: buildx 가 없습니다. docker-buildx-plugin 을 설치하거나, "
                    + "호스트 데몬의 docker build 를 쓰려면 allow-native-build=true 를 명시하세요(기본은 kaniko 격리 빌드).");
        }
        return Mode.NATIVE;
    }

    // ── BUILDX(호스트 buildkit, 크로스빌드) ─────────────────────────────────────────────

    private Path buildxBuildTar(Path contextTar, String imageTag) {
        return saveBuilt(imageTag, () -> runCli(new String[]{"docker", "buildx", "build", "--platform", TARGET_PLATFORM,
                "-t", imageTag, "--load", "-"}, contextTar, "이미지 빌드(buildx)"));
    }

    /**
     * buildx 없이 amd64 컨트롤 플레인에서 BuildKit 기본 docker build 로 네이티브 빌드한다(빌드 후 로컬 이미지 스토어에
     * 자동 load 되어 save 가능). 호스트 데몬에서 사용자 Dockerfile 이 돌므로 allow-native-build 를 명시했을 때만 온다.
     */
    private Path nativeBuildTar(Path contextTar, String imageTag) {
        log.info("buildx 미설치 — 명시 허용에 따라 amd64 네이티브 docker build: {}", imageTag);
        return saveBuilt(imageTag, () -> runCli(new String[]{"docker", "build", "--platform", TARGET_PLATFORM,
                "-t", imageTag, "-"}, contextTar, "이미지 빌드(docker build)"));
    }

    private Path saveBuilt(String imageTag, Runnable build) {
        Path outTar = createTempFile("qeploy-image-", ".tar");
        build.run();
        try {
            runCli(new String[]{"docker", "save", imageTag, "-o", outTar.toString()}, null, "이미지 save");
        } catch (RuntimeException e) {
            deleteQuietly(outTar);
            throw e;
        } finally {
            try {
                runCli(new String[]{"docker", "rmi", "-f", imageTag}, null, "로컬 이미지 삭제");
            } catch (RuntimeException ignore) {
                log.warn("빌드 이미지 로컬 삭제 실패(무시): {}", imageTag);
            }
        }
        return outTar;
    }

    private void buildxBuildAndPush(Path contextTar, String imageRef, EcrAuth auth, Mode mode) {
        runCliWithStdin(new String[]{"docker", "login", "--username", auth.username(), "--password-stdin",
                auth.registry()}, auth.password().getBytes(StandardCharsets.UTF_8), "ECR docker login");
        try {
            if (mode == Mode.BUILDX) {
                runCli(new String[]{"docker", "buildx", "build", "--platform", TARGET_PLATFORM,
                        "-t", imageRef, "--push", "-"}, contextTar, "이미지 빌드·푸시(buildx)");
            } else {
                // NATIVE(명시 허용): amd64 네이티브 빌드 후 별도 push.
                log.info("buildx 미설치 — 명시 허용에 따라 amd64 네이티브 docker build+push: {}", imageRef);
                runCli(new String[]{"docker", "build", "--platform", TARGET_PLATFORM,
                        "-t", imageRef, "-"}, contextTar, "이미지 빌드(docker build)");
                runCli(new String[]{"docker", "push", imageRef}, null, "이미지 push");
            }
        } finally {
            runCliQuiet(new String[]{"docker", "logout", auth.registry()});
        }
    }

    /**
     * 이미지 빌드에 buildx 를 쓸 수 있는지(플러그인 설치 여부). 없으면 컨트롤 플레인이 타깃과 같은
     * arch 일 때만 {@code docker build} 로 폴백한다. 한 번만 확인해 캐시한다(빌드마다 재확인 불필요).
     */
    private volatile Boolean buildxAvailable;

    private boolean buildxAvailable() {
        Boolean cached = buildxAvailable;
        if (cached != null) {
            return cached;
        }
        boolean ok;
        try {
            ok = BoundedProcess.await(new ProcessBuilder("docker", "buildx", "version").redirectErrorStream(true).start(),
                    SHORT_COMMAND_TIMEOUT, "buildx 확인", () -> { }).exitCode() == 0;
        } catch (IOException | BackendBuildException e) {
            ok = false;
        }
        buildxAvailable = ok;
        return ok;
    }

    /** 컨트롤 플레인 arch 가 배포 타깃(amd64)과 같은가 — 같으면 buildx 없이도 네이티브 빌드 가능. */
    private static boolean controlPlaneMatchesTarget() {
        String arch = System.getProperty("os.arch", "");
        return arch.equals("amd64") || arch.equals("x86_64");
    }

    private static String requireBuildxMessage(String arch) {
        return "이미지 빌드 실패: buildx 가 없고 컨트롤 플레인 arch(" + arch
                + ")가 배포 타깃(amd64)과 달라 크로스빌드가 필요합니다 — docker-buildx-plugin 을 설치하세요.";
    }

    // ── KANIKO(격리 컨테이너 빌드, 컨트롤 플레인 arch) ────────────────────────────────────

    /**
     * kaniko executor 컨테이너로 이미지를 빌드해 tar 로 뽑는다. 컨텍스트 tar 를 호스트 임시 디렉터리로
     * 풀어 read-only 로 마운트하고, 빌드는 kaniko 컨테이너 안에서 수행된다(호스트 데몬 빌드 아님).
     */
    private Path kanikoBuildTar(Path contextTar, String imageTag) {
        Path ctxDir = createTempDir("qeploy-ctx-");
        Path outDir = createTempDir("qeploy-out-");
        try {
            extractContext(contextTar, ctxDir);
            String name = kanikoContainerName();
            List<String> cmd = kanikoBase(ctxDir, name);
            cmd.add("-v");
            cmd.add(outDir + ":/out");
            cmd.add(KANIKO_IMAGE);
            kanikoArgs(cmd);
            cmd.add("--no-push");
            cmd.add("--tar-path=/out/image.tar");
            cmd.add("--destination=" + imageTag);   // tar 안 이미지 태그(load 시 이 이름으로 복원)
            runCli(cmd.toArray(String[]::new), null, "이미지 빌드(kaniko)", () -> removeContainerQuietly(name));
            Path result = createTempFile("qeploy-image-", ".tar");
            Files.move(outDir.resolve("image.tar"), result,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return result;
        } catch (IOException e) {
            throw new BackendBuildException("kaniko 이미지 tar 처리 실패: " + e.getMessage());
        } finally {
            deleteDirQuietly(ctxDir);
            deleteDirQuietly(outDir);
        }
    }

    /**
     * kaniko executor 로 빌드해 ECR 로 push 한다. ECR 자격은 docker config.json 으로 써서 kaniko 컨테이너에
     * read-only 마운트한다(비밀은 임시파일에만, 명령줄·로그에 안 남긴다).
     */
    private void kanikoBuildAndPush(Path contextTar, String imageRef, EcrAuth auth) {
        Path ctxDir = createTempDir("qeploy-ctx-");
        Path cfg = createTempFile("qeploy-dockercfg-", ".json");
        try {
            extractContext(contextTar, ctxDir);
            writeDockerConfig(cfg, auth);
            String name = kanikoContainerName();
            List<String> cmd = kanikoBase(ctxDir, name);
            cmd.add("-v");
            cmd.add(cfg + ":/kaniko/.docker/config.json:ro");
            cmd.add(KANIKO_IMAGE);
            kanikoArgs(cmd);
            cmd.add("--destination=" + imageRef);
            runCli(cmd.toArray(String[]::new), null, "이미지 빌드·푸시(kaniko)", () -> removeContainerQuietly(name));
        } catch (IOException e) {
            throw new BackendBuildException("kaniko 이미지 push 처리 실패: " + e.getMessage());
        } finally {
            deleteQuietly(cfg);
            deleteDirQuietly(ctxDir);
        }
    }

    /**
     * kaniko 컨테이너 공통 앞부분: docker run --rm + 이름 + 컨텍스트 디렉터리 read-only 마운트. 이름을 붙이는 것은
     * 시간 초과·중단 때 지우기 위해서다 — docker CLI 를 죽여도 데몬이 띄운 컨테이너는 계속 돈다(#507).
     */
    private List<String> kanikoBase(Path ctxDir, String name) {
        List<String> cmd = new ArrayList<>(List.of("docker", "run", "--rm", "--name", name,
                "-v", ctxDir + ":/workspace:ro"));
        return cmd;
    }

    private static String kanikoContainerName() {
        return "qeploy-kaniko-" + java.util.UUID.randomUUID();
    }

    private void removeContainerQuietly(String name) {
        runCliQuiet(new String[]{"docker", "rm", "-f", name});
    }

    /** kaniko executor 공통 인자: 컨텍스트·Dockerfile. */
    private void kanikoArgs(List<String> cmd) {
        cmd.add("--context=dir:///workspace");
        cmd.add("--dockerfile=Dockerfile");
    }

    private void writeDockerConfig(Path cfg, EcrAuth auth) throws IOException {
        String basic = Base64.getEncoder().encodeToString(
                (auth.username() + ":" + auth.password()).getBytes(StandardCharsets.UTF_8));
        String json = "{\"auths\":{\"" + auth.registry() + "\":{\"auth\":\"" + basic + "\"}}}";
        Files.writeString(cfg, json);
    }

    private void extractContext(Path contextTar, Path destDir) {
        runCli(new String[]{"tar", "-xf", contextTar.toString(), "-C", destDir.toString()}, null,
                "빌드 컨텍스트 추출");
    }

    // ── 공용 프로세스 실행 ────────────────────────────────────────────────────────────

    /** 정리용·짧은 CLI(로그인·로그아웃·rm·buildx 확인)를 기다리는 상한. */
    static final Duration SHORT_COMMAND_TIMEOUT = Duration.ofMinutes(2);

    /** CLI 실행. stdinFile 을 stdin 으로 스트리밍한다(빌드 컨텍스트 tar — 대용량이라 파일 리다이렉트). */
    private void runCli(String[] cmd, Path stdinFile, String what) {
        runCli(cmd, stdinFile, what, () -> { });
    }

    /** @param onAbort 시간 초과·중단으로 프로세스를 죽인 뒤 부른다(프로세스 밖에 남는 자원 정리) */
    private void runCli(String[] cmd, Path stdinFile, String what, Runnable onAbort) {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (stdinFile != null) {
            pb.redirectInput(stdinFile.toFile());
        }
        try {
            check(BoundedProcess.await(pb.start(), properties.imageBuildTimeoutOrDefault(), what, onAbort), what);
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
            check(BoundedProcess.await(p, SHORT_COMMAND_TIMEOUT, what, () -> { }), what);
        } catch (IOException e) {
            throw new BackendBuildException(what + " 실행 실패: " + e.getMessage());
        }
    }

    /** best-effort CLI(정리용 — 예: docker logout). 실패·시간 초과해도 던지지 않고 경고만. */
    private void runCliQuiet(String[] cmd) {
        try {
            BoundedProcess.await(new ProcessBuilder(cmd).redirectErrorStream(true).start(),
                    SHORT_COMMAND_TIMEOUT, "정리 명령", () -> { });
        } catch (IOException | BackendBuildException e) {
            log.warn("정리 명령 실패(무시): {}", String.join(" ", cmd));
        }
    }

    /** 비-0 종료면 출력 끝부분과 함께 실패로 만든다. */
    private static void check(BoundedProcess.Result result, String what) {
        if (result.exitCode() != 0) {
            throw new BackendBuildException(what + " 실패(exit " + result.exitCode() + "): "
                    + BackendSourceClone.tail(result.output()));
        }
    }

    private Path createTempFile(String prefix, String suffix) {
        try {
            return Files.createTempFile(prefix, suffix);
        } catch (IOException e) {
            throw new BackendBuildException("임시파일 생성 실패: " + e.getMessage());
        }
    }

    private Path createTempDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new BackendBuildException("임시 디렉터리 생성 실패: " + e.getMessage());
        }
    }

    void deleteQuietly(Path p) {
        if (p != null) {
            try { Files.deleteIfExists(p); } catch (IOException ignored) { }
        }
    }

    private void deleteDirQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) {
        }
    }
}
