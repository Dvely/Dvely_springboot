package com.example.dvely.provisioning.application.service;

import com.example.dvely.provisioning.infrastructure.EcrImageRegistry.EcrAuth;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 빌드 컨텍스트 tar 하나를 받아 <b>호스트 buildx 로 EC2 아키텍처(amd64) 이미지를 만든다.</b> 앱 이미지
 * ({@link DockerImageBuildService})와 웹 이미지({@link WebImageBuildService})가 공유하는, 컨테이너·소스
 * 지식이 없는 순수 빌드 단계다 — 컨텍스트 준비(clone·파일생성·tar)는 호출자가 하고 여기엔 tar 만 넘긴다.
 *
 * <p>docker-java 의 legacy builder 는 크로스빌드를 못 해(호스트 arch 로만) buildx(buildkit+QEMU)를 쓴다.
 * S3 전달은 {@code --load} 후 {@code save}(이미지 tar), ECR 전달은 {@code --push}(레지스트리로 직접).</p>
 */
@Slf4j
@Component
public class ContainerImageBuilder {

    // 배포 대상 EC2 아키텍처(t3.* = amd64). arm64 컨트롤 플레인에서도 이 플랫폼을 강제해야 EC2 에서 뜬다.
    static final String TARGET_PLATFORM = "linux/amd64";

    /** 컨텍스트 tar 를 amd64 로 빌드해 docker-loadable tar 로 뽑는다(S3 전달용). 반환 tar 는 호출자가 지운다. */
    public Path buildTar(Path contextTar, String imageTag) {
        Path outTar;
        try {
            outTar = Files.createTempFile("qeploy-image-", ".tar");
        } catch (IOException e) {
            throw new BackendBuildException("이미지 tar 생성 실패: " + e.getMessage());
        }
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

    /** 컨텍스트 tar 를 amd64 로 빌드해 ECR 로 직접 push 한다(ECR 전달용). login→push→logout. */
    public void buildAndPush(Path contextTar, String imageRef, EcrAuth auth) {
        runCliWithStdin(new String[]{"docker", "login", "--username", auth.username(), "--password-stdin",
                auth.registry()}, auth.password().getBytes(StandardCharsets.UTF_8), "ECR docker login");
        try {
            runCli(new String[]{"docker", "buildx", "build", "--platform", TARGET_PLATFORM,
                    "-t", imageRef, "--push", "-"}, contextTar, "이미지 빌드·푸시(buildx)");
        } finally {
            runCliQuiet(new String[]{"docker", "logout", auth.registry()});
        }
    }

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

    void deleteQuietly(Path p) {
        if (p != null) {
            try { Files.deleteIfExists(p); } catch (IOException ignored) { }
        }
    }
}
