package com.example.dvely.agent.infrastructure.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService.ExecResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@code write_file} 이 내용을 명령줄에 싣지 않고, 실패를 숨기지 않으며, {@code /workspace} 밖에 쓰지 않는지 본다(#617).
 * 실제 데몬에서의 동작(200KiB·소유자·덮어쓰기)은 {@code ContainerFileWriterDockerIntegrationTest} 가 본다.
 */
class ContainerFileWriterTest {

    private static final String CONTAINER_ID = "container-1";

    private final DockerContainerService dockerService = mock(DockerContainerService.class);
    private final ContainerFileWriter writer = new ContainerFileWriter(dockerService);

    // ── 경로 ──────────────────────────────────────────────────────────────

    @Test
    void acceptsAbsolutePathsUnderTheWorkspaceAndNormalizesThem() {
        assertThat(ContainerFileWriter.resolve("/workspace/app/src/App.jsx")).isEqualTo("/workspace/app/src/App.jsx");
        assertThat(ContainerFileWriter.resolve("/workspace/app/./src/../index.html")).isEqualTo("/workspace/app/index.html");
        assertThat(ContainerFileWriter.resolve("/workspace/.env")).isEqualTo("/workspace/.env");
    }

    @Test
    void rejectsPathsThatLeaveTheWorkspace() {
        assertThatThrownBy(() -> ContainerFileWriter.resolve("/workspace/../etc/passwd"))
                .isInstanceOf(ContainerFileWriteException.class).hasMessageContaining("작업 공간");
        assertThatThrownBy(() -> ContainerFileWriter.resolve("/tmp/a.js"))
                .isInstanceOf(ContainerFileWriteException.class).hasMessageContaining("작업 공간");
        // 접두어만 같은 형제 디렉터리
        assertThatThrownBy(() -> ContainerFileWriter.resolve("/workspace2/a.js"))
                .isInstanceOf(ContainerFileWriteException.class).hasMessageContaining("작업 공간");
        // 작업 공간 자신은 파일이 될 수 없다
        assertThatThrownBy(() -> ContainerFileWriter.resolve("/workspace/"))
                .isInstanceOf(ContainerFileWriteException.class);
    }

    @Test
    void rejectsRelativeBlankAndControlCharacterPaths() {
        assertThatThrownBy(() -> ContainerFileWriter.resolve("src/App.jsx"))
                .isInstanceOf(ContainerFileWriteException.class).hasMessageContaining("절대 경로");
        assertThatThrownBy(() -> ContainerFileWriter.resolve(" "))
                .isInstanceOf(ContainerFileWriteException.class).hasMessageContaining("비어");
        assertThatThrownBy(() -> ContainerFileWriter.resolve("/workspace/app/a\nb.js"))
                .isInstanceOf(ContainerFileWriteException.class).hasMessageContaining("제어 문자");
    }

    @Test
    void aRejectedPathNeverReachesTheContainer() {
        assertThatThrownBy(() -> writer.write(CONTAINER_ID, "/etc/profile", "x"))
                .isInstanceOf(ContainerFileWriteException.class);

        verify(dockerService, never()).execWithExitCode(anyString(), anyString());
        verify(dockerService, never()).copyArchiveToContainerNoDirReplace(anyString(), any(), anyString());
    }

    // ── 쓰기 ──────────────────────────────────────────────────────────────

    @Test
    void writesTheContentAsATarIntoTheParentDirectoryWithoutPuttingItOnTheCommandLine() throws Exception {
        String content = "a".repeat(200 * 1024) + "\n한글 끝";
        when(dockerService.execWithExitCode(eq(CONTAINER_ID), anyString())).thenReturn(new ExecResult(0, ""));

        String written = writer.write(CONTAINER_ID, "/workspace/app/src/App.jsx", content);

        assertThat(written).isEqualTo("/workspace/app/src/App.jsx");
        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        verify(dockerService).execWithExitCode(eq(CONTAINER_ID), command.capture());
        assertThat(command.getValue())
                .startsWith("mkdir -p '/workspace/app/src'")
                .doesNotContain("aaaa")
                .hasSizeLessThan(400);

        TarEntry entry = uploadedEntry("/workspace/app/src");
        assertThat(entry.name()).isEqualTo("App.jsx");
        assertThat(entry.mode()).isEqualTo(0644);
        assertThat(new String(entry.content(), StandardCharsets.UTF_8)).isEqualTo(content);
    }

    @Test
    void overwritingKeepsTheExistingFilesPermissionBits() throws Exception {
        // writeFileSync 는 덮어써도 실행 비트를 남겼다. tar 는 헤더 mode 로 다시 만들므로 기존 mode 를 싣는다.
        when(dockerService.execWithExitCode(eq(CONTAINER_ID), anyString())).thenReturn(new ExecResult(0, "755\n"));

        writer.write(CONTAINER_ID, "/workspace/app/gradlew", "#!/bin/sh\n");

        assertThat(uploadedEntry("/workspace/app").mode()).isEqualTo(0755);
    }

    @Test
    void specialBitsFromTheExistingModeAreDropped() {
        assertThat(ContainerFileWriter.existingMode("4755")).isEqualTo(0755);
        assertThat(ContainerFileWriter.existingMode("")).isEqualTo(ContainerFileWriter.NEW_FILE_MODE);
        assertThat(ContainerFileWriter.existingMode("stat: not found")).isEqualTo(ContainerFileWriter.NEW_FILE_MODE);
        assertThat(ContainerFileWriter.existingMode("600\n[STDERR]\nwarning")).isEqualTo(0600);
    }

    @Test
    void quotesPathsSoTheShellInterpretsNothing() {
        when(dockerService.execWithExitCode(eq(CONTAINER_ID), anyString())).thenReturn(new ExecResult(0, ""));

        writer.write(CONTAINER_ID, "/workspace/app/it's $(rm -rf ~)/a.js", "x");

        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        verify(dockerService).execWithExitCode(eq(CONTAINER_ID), command.capture());
        assertThat(command.getValue()).startsWith("mkdir -p '/workspace/app/it'\"'\"'s $(rm -rf ~)'");
    }

    @Test
    void aLongOrNonAsciiFileNameSurvivesTheTar() throws Exception {
        String name = "페이지-" + "x".repeat(120) + ".jsx";
        when(dockerService.execWithExitCode(eq(CONTAINER_ID), anyString())).thenReturn(new ExecResult(0, ""));

        writer.write(CONTAINER_ID, "/workspace/app/" + name, "x");

        assertThat(uploadedEntry("/workspace/app").name()).isEqualTo(name);
    }

    // ── 실패 ──────────────────────────────────────────────────────────────

    @Test
    void aDirectoryThatCannotBeCreatedIsAFailureWithTheReason() {
        when(dockerService.execWithExitCode(eq(CONTAINER_ID), anyString()))
                .thenReturn(new ExecResult(20, "\n[STDERR]\nmkdir: can't create directory '/workspace/app/x': Permission denied"));

        assertThatThrownBy(() -> writer.write(CONTAINER_ID, "/workspace/app/x/a.js", "x"))
                .isInstanceOf(ContainerFileWriteException.class)
                .hasMessageContaining("디렉터리를 만들지 못했습니다")
                .hasMessageContaining("exit=20")
                .hasMessageContaining("Permission denied");
        verify(dockerService, never()).copyArchiveToContainerNoDirReplace(anyString(), any(), anyString());
    }

    @Test
    void neverReplacesAnExistingDirectoryWithAFile() {
        when(dockerService.execWithExitCode(eq(CONTAINER_ID), anyString())).thenReturn(new ExecResult(21, ""));

        assertThatThrownBy(() -> writer.write(CONTAINER_ID, "/workspace/app/src", "x"))
                .isInstanceOf(ContainerFileWriteException.class)
                .hasMessageContaining("디렉터리가 있어");
        verify(dockerService, never()).copyArchiveToContainerNoDirReplace(anyString(), any(), anyString());
    }

    @Test
    void aDaemonErrorDuringUploadIsAFailureThatDoesNotCarryTheContent() {
        when(dockerService.execWithExitCode(eq(CONTAINER_ID), anyString())).thenReturn(new ExecResult(0, ""));
        doThrow(new RuntimeException("Status 404: No such container"))
                .when(dockerService).copyArchiveToContainerNoDirReplace(eq(CONTAINER_ID), any(), anyString());

        assertThatThrownBy(() -> writer.write(CONTAINER_ID, "/workspace/app/.env", "API_KEY=do-not-echo"))
                .isInstanceOf(ContainerFileWriteException.class)
                .hasMessageContaining("No such container")
                .hasMessageNotContaining("do-not-echo");
    }

    @Test
    void aDaemonErrorDuringPreparationIsAFailure() {
        when(dockerService.execWithExitCode(eq(CONTAINER_ID), anyString()))
                .thenThrow(new RuntimeException("Status 409: container is not running"));

        assertThatThrownBy(() -> writer.write(CONTAINER_ID, "/workspace/app/a.js", "x"))
                .isInstanceOf(ContainerFileWriteException.class)
                .hasMessageContaining("not running");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private record TarEntry(String name, int mode, byte[] content) {}

    private TarEntry uploadedEntry(String expectedDirectory) throws IOException {
        ArgumentCaptor<InputStream> tar = ArgumentCaptor.forClass(InputStream.class);
        verify(dockerService).copyArchiveToContainerNoDirReplace(eq(CONTAINER_ID), tar.capture(), eq(expectedDirectory));
        try (TarArchiveInputStream in = new TarArchiveInputStream(
                new ByteArrayInputStream(tar.getValue().readAllBytes()), StandardCharsets.UTF_8.name())) {
            TarArchiveEntry entry = in.getNextEntry();
            assertThat(entry).isNotNull();
            TarEntry result = new TarEntry(entry.getName(), entry.getMode() & 0777, in.readAllBytes());
            assertThat(in.getNextEntry()).as("파일 하나만 담는다").isNull();
            return result;
        }
    }
}
