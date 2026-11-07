package com.example.dvely.agent.infrastructure.docker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Date;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.springframework.stereotype.Component;

/**
 * 코드 에이전트의 {@code write_file} 을 컨테이너 안에 실제로 쓴다(#617).
 *
 * <p>예전에는 내용을 base64 로 바꿔 {@code node -e "...Buffer.from('<base64>')..."} 명령 하나에 넣었다. 그 명령이
 * {@code sh -c} 의 인자 하나라 리눅스의 인자 상한(128KiB, {@code MAX_ARG_STRLEN})에 걸려, 원문 96KiB 를 넘는 파일은
 * {@code argument list too long} 으로 실패했다. 종료 코드도 보지 않아 모델은 "파일 작성 완료" 를 받았다. 경로는
 * 셸·JS 문자열에 그대로 끼워 넣어졌다.</p>
 *
 * <p>지금은 메모리에서 파일 하나짜리 tar 를 만들어 도커 archive API 로 넣는다. 내용이 명령줄을 지나지 않으므로
 * 크기 상한이 없고, 명령 로그(DEBUG)에도 실리지 않는다. 그 전에 exec 한 번으로 디렉터리를 만들고 대상을 확인한다.</p>
 *
 * <ul>
 *   <li><b>디렉터리는 exec 로, 컨테이너 사용자(node)가 만든다.</b> tar 에 디렉터리 경로를 실으면 도커가 없는
 *       상위 디렉터리를 root 소유로 만든다. 프리뷰 컨테이너는 {@code DAC_OVERRIDE} 가 없어 그 뒤로 node 가 그
 *       디렉터리에 아무것도 못 쓴다.</li>
 *   <li><b>기존 파일의 권한을 지킨다.</b> 예전 {@code writeFileSync} 는 덮어써도 실행 비트를 남겼다. tar 는 헤더의
 *       mode 로 다시 만드므로, 기존 파일의 mode 를 읽어 그대로 싣는다. 새 파일은 0644 다.</li>
 *   <li><b>디렉터리를 파일로 덮지 않는다.</b> {@link DockerContainerService#copyArchiveToContainerNoDirReplace}
 *       참고. exec 단계에서도 먼저 확인해 모델이 읽을 수 있는 이유로 돌려준다.</li>
 *   <li><b>경로는 {@code /workspace} 아래 절대 경로만 받는다.</b> tool 정의가 절대 경로를 요구하고, 앱은
 *       {@link ContainerPaths#APP_DIR} 에 있어야 뒤 단계(push·diff·배포)가 찾는다. 보안 경계는 아니다 — 모델은
 *       {@code execute_command} 로 같은 사용자 권한의 어떤 명령이든 돌릴 수 있다. 실수로 엉뚱한 곳에 쓰는 것을 막는다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ContainerFileWriter {

    static final String WORKSPACE = "/workspace";
    static final int NEW_FILE_MODE = 0644;

    private static final Path WORKSPACE_PATH = Path.of(WORKSPACE);
    private static final int DIRECTORY_NOT_CREATED_EXIT = 20;
    private static final int TARGET_IS_DIRECTORY_EXIT = 21;
    private static final Pattern OCTAL_MODE = Pattern.compile("[0-7]{3,4}");
    private static final int MESSAGE_PATH_MAX_CHARS = 200;
    private static final int MESSAGE_OUTPUT_MAX_CHARS = 300;

    private final DockerContainerService dockerService;

    /**
     * {@code content} 를 {@code path} 에 쓴다. 이미 있으면 덮어쓴다.
     *
     * @return 실제로 쓴 경로(정규화된 절대 경로)
     * @throws ContainerFileWriteException 경로가 허용 범위 밖이거나, 디렉터리를 만들지 못했거나, 쓰기에 실패했을 때.
     *                                     메시지는 모델에게 그대로 돌아간다
     */
    public String write(String containerId, String path, String content) {
        String target = resolve(path);
        String directory = parentOf(target);
        String fileName = target.substring(directory.length() + 1);

        int mode = prepare(containerId, directory, target);
        byte[] tar = singleFileTar(fileName, content.getBytes(StandardCharsets.UTF_8), mode);
        try {
            dockerService.copyArchiveToContainerNoDirReplace(containerId, new ByteArrayInputStream(tar), directory);
        } catch (RuntimeException e) {
            throw new ContainerFileWriteException(
                    "컨테이너에 파일을 넣지 못했습니다(" + clip(target, MESSAGE_PATH_MAX_CHARS) + "): "
                            + clip(String.valueOf(e.getMessage()), MESSAGE_OUTPUT_MAX_CHARS), e);
        }
        return target;
    }

    /**
     * 모델이 준 경로를 정규화해 {@code /workspace} 아래인지 확인한다. {@code ..} 로 빠져나가는 경로,
     * {@code /workspace} 자신, 상대 경로, 제어 문자가 든 경로는 거절한다.
     */
    static String resolve(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new ContainerFileWriteException("경로가 비어 있습니다.");
        }
        if (rawPath.chars().anyMatch(Character::isISOControl)) {
            throw new ContainerFileWriteException("경로에 제어 문자가 들어 있습니다.");
        }
        if (!rawPath.startsWith("/")) {
            throw new ContainerFileWriteException("절대 경로가 아닙니다: " + clip(rawPath, MESSAGE_PATH_MAX_CHARS)
                    + ". " + ContainerPaths.APP_DIR + "/... 처럼 절대 경로로 다시 호출하세요.");
        }
        Path normalized = Path.of(rawPath).normalize();
        if (!normalized.startsWith(WORKSPACE_PATH) || normalized.equals(WORKSPACE_PATH)) {
            throw new ContainerFileWriteException("작업 공간(" + WORKSPACE + ") 밖에는 파일을 쓸 수 없습니다: "
                    + clip(rawPath, MESSAGE_PATH_MAX_CHARS) + ". 앱 파일은 " + ContainerPaths.APP_DIR + " 아래에 씁니다.");
        }
        return normalized.toString();
    }

    /** 디렉터리를 만들고, 대상이 디렉터리가 아닌지 확인하고, 기존 파일이면 그 mode 를 돌려준다. */
    private int prepare(String containerId, String directory, String target) {
        String quotedDir = shellQuote(directory);
        String quotedTarget = shellQuote(target);
        String script = "mkdir -p " + quotedDir + " || exit " + DIRECTORY_NOT_CREATED_EXIT + "; "
                + "[ -d " + quotedTarget + " ] && exit " + TARGET_IS_DIRECTORY_EXIT + "; "
                + "[ -e " + quotedTarget + " ] && stat -c %a " + quotedTarget + "; "
                + "exit 0";
        DockerContainerService.ExecResult result;
        try {
            result = dockerService.execWithExitCode(containerId, script);
        } catch (RuntimeException e) {
            throw new ContainerFileWriteException("컨테이너에서 쓰기 준비를 하지 못했습니다: "
                    + clip(String.valueOf(e.getMessage()), MESSAGE_OUTPUT_MAX_CHARS), e);
        }
        if (result.exitCode() == TARGET_IS_DIRECTORY_EXIT) {
            throw new ContainerFileWriteException("같은 경로에 디렉터리가 있어 파일로 덮어쓰지 않았습니다: "
                    + clip(target, MESSAGE_PATH_MAX_CHARS) + ". 디렉터리 안의 파일 경로를 주세요.");
        }
        if (!result.succeeded()) {
            throw new ContainerFileWriteException("디렉터리를 만들지 못했습니다(" + clip(directory, MESSAGE_PATH_MAX_CHARS)
                    + ", exit=" + result.exitCode() + "): " + clip(result.output().strip(), MESSAGE_OUTPUT_MAX_CHARS));
        }
        return existingMode(result.output());
    }

    /** {@code stat -c %a} 의 출력(예: {@code 755}). 없으면 새 파일 mode. setuid 같은 특수 비트는 싣지 않는다. */
    static int existingMode(String statOutput) {
        String firstLine = statOutput == null ? "" : statOutput.strip().lines().findFirst().orElse("");
        if (!OCTAL_MODE.matcher(firstLine).matches()) {
            return NEW_FILE_MODE;
        }
        return Integer.parseInt(firstLine, 8) & 0777;
    }

    static byte[] singleFileTar(String fileName, byte[] payload, int mode) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(payload.length + 2_048);
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(buffer, StandardCharsets.UTF_8.name())) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tar.setAddPaxHeadersForNonAsciiNames(true);
            TarArchiveEntry entry = new TarArchiveEntry(fileName);
            entry.setSize(payload.length);
            entry.setMode(mode);
            entry.setModTime(new Date());
            tar.putArchiveEntry(entry);
            tar.write(payload);
            tar.closeArchiveEntry();
            tar.finish();
        } catch (IOException e) {
            throw new ContainerFileWriteException("파일을 담을 tar 를 만들지 못했습니다.", e);
        }
        return buffer.toByteArray();
    }

    private static String parentOf(String absolutePath) {
        int slash = absolutePath.lastIndexOf('/');
        return absolutePath.substring(0, slash);
    }

    /** 작은따옴표로 감싸고, 안의 작은따옴표는 {@code '"'"'} 로 끊어 잇는다. 셸이 아무것도 해석하지 않는다. */
    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
