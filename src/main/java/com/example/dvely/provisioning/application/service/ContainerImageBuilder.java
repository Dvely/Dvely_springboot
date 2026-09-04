package com.example.dvely.provisioning.application.service;

import com.example.dvely.provisioning.infrastructure.EcrImageRegistry.EcrAuth;
import com.example.dvely.provisioning.infrastructure.config.Ec2ProvisioningProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * <p><b>두 빌드 격리 방식</b>({@code qeploy.provisioning.ec2.build-isolation}):
 * <ul>
 *   <li><b>BUILDX</b>(기본): 호스트 buildkit(daemon)로 빌드. 크로스빌드(arm64 컨트롤 플레인→amd64) 지원.
 *       신뢰할 수 없는 Dockerfile 의 빌드 스텝이 호스트 buildkit 에서 돈다 — 단일 테넌트 한정.</li>
 *   <li><b>KANIKO</b>(하드닝): 빌드가 <b>호스트 데몬이 아니라 격리된 kaniko 컨테이너 안</b>에서 돈다
 *       (멀티테넌트 안전). 단 컨트롤 플레인 arch 로만 빌드하므로 amd64 컨트롤 플레인에서만 켠다.</li>
 * </ul>
 * 두 방식 모두 S3 전달은 이미지 tar 를, ECR 전달은 레지스트리 push 를 한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContainerImageBuilder {

    // 배포 대상 EC2 아키텍처(t3.* = amd64). BUILDX 는 이 플랫폼을 강제(크로스빌드), KANIKO 는 컨트롤 플레인 arch.
    static final String TARGET_PLATFORM = "linux/amd64";
    private static final String KANIKO_IMAGE = "gcr.io/kaniko-project/executor:latest";

    private final Ec2ProvisioningProperties properties;

    /** 컨텍스트 tar 를 빌드해 docker-loadable tar 로 뽑는다(S3 전달용). 반환 tar 는 호출자가 지운다. */
    public Path buildTar(Path contextTar, String imageTag) {
        return properties.useKaniko() ? kanikoBuildTar(contextTar, imageTag)
                : buildxBuildTar(contextTar, imageTag);
    }

    /** 컨텍스트 tar 를 빌드해 ECR 로 직접 push 한다(ECR 전달용). */
    public void buildAndPush(Path contextTar, String imageRef, EcrAuth auth) {
        if (properties.useKaniko()) {
            kanikoBuildAndPush(contextTar, imageRef, auth);
        } else {
            buildxBuildAndPush(contextTar, imageRef, auth);
        }
    }

    // ── BUILDX(호스트 buildkit, 크로스빌드) ─────────────────────────────────────────────

    private Path buildxBuildTar(Path contextTar, String imageTag) {
        Path outTar = createTempFile("qeploy-image-", ".tar");
        runCli(new String[]{"docker", "buildx", "build", "--platform", TARGET_PLATFORM,
                "-t", imageTag, "--load", "-"}, contextTar, "이미지 빌드(buildx)");
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

    private void buildxBuildAndPush(Path contextTar, String imageRef, EcrAuth auth) {
        runCliWithStdin(new String[]{"docker", "login", "--username", auth.username(), "--password-stdin",
                auth.registry()}, auth.password().getBytes(StandardCharsets.UTF_8), "ECR docker login");
        try {
            runCli(new String[]{"docker", "buildx", "build", "--platform", TARGET_PLATFORM,
                    "-t", imageRef, "--push", "-"}, contextTar, "이미지 빌드·푸시(buildx)");
        } finally {
            runCliQuiet(new String[]{"docker", "logout", auth.registry()});
        }
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
            List<String> cmd = kanikoBase(ctxDir);
            cmd.add("-v");
            cmd.add(outDir + ":/out");
            cmd.add(KANIKO_IMAGE);
            kanikoArgs(cmd);
            cmd.add("--no-push");
            cmd.add("--tar-path=/out/image.tar");
            cmd.add("--destination=" + imageTag);   // tar 안 이미지 태그(load 시 이 이름으로 복원)
            runCli(cmd.toArray(String[]::new), null, "이미지 빌드(kaniko)");
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
            List<String> cmd = kanikoBase(ctxDir);
            cmd.add("-v");
            cmd.add(cfg + ":/kaniko/.docker/config.json:ro");
            cmd.add(KANIKO_IMAGE);
            kanikoArgs(cmd);
            cmd.add("--destination=" + imageRef);
            runCli(cmd.toArray(String[]::new), null, "이미지 빌드·푸시(kaniko)");
        } catch (IOException e) {
            throw new BackendBuildException("kaniko 이미지 push 처리 실패: " + e.getMessage());
        } finally {
            deleteQuietly(cfg);
            deleteDirQuietly(ctxDir);
        }
    }

    /** kaniko 컨테이너 공통 앞부분: docker run --rm + 컨텍스트 디렉터리 read-only 마운트. */
    private List<String> kanikoBase(Path ctxDir) {
        List<String> cmd = new ArrayList<>(List.of("docker", "run", "--rm",
                "-v", ctxDir + ":/workspace:ro"));
        return cmd;
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

    /** CLI 실행. stdinFile 을 stdin 으로 스트리밍한다(빌드 컨텍스트 tar — 대용량이라 파일 리다이렉트). */
    private void runCli(String[] cmd, Path stdinFile, String what) {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (stdinFile != null) {
            pb.redirectInput(stdinFile.toFile());
        }
        try {
            waitAndCheck(pb.start(), what);
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
