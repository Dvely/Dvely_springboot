package com.example.dvely.agent.infrastructure.codingagent;

import com.example.dvely.agent.application.port.out.CodingAgentResult;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.docker.ContainerPaths;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.aiaccount.application.service.CodingAgentExecutionService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Bridges the two workspace models a CODE step can run under.
 *
 * <p>The tool loop drives commands <b>inside an already-running preview container</b>, where the
 * project lives at {@link ContainerPaths#APP_DIR} on the container's own writable layer. A coding
 * agent brings <b>its own container</b> and edits a host directory bind-mounted into it. Neither
 * can see the other's files: the preview container has no bind mount, so there is no host path to
 * hand the agent, and the agent's container is gone by the time the preview serves anything.</p>
 *
 * <p>So the project is carried across: out to a host directory, edited there, and back in. The copy
 * is the price of keeping the preview container unmounted — giving it a host bind mount would put
 * user code on the host filesystem for every session, not just the ones that use a coding agent.</p>
 *
 * <h2>What is deliberately not carried</h2>
 *
 * <p>{@code node_modules} crosses in neither direction. It is tens of thousands of files, it is
 * reproducible from {@code package.json}, and the preview reinstalls after the copy back. Carrying
 * it would make every coding-agent run pay minutes of tar for something both sides can rebuild.</p>
 *
 * <p>{@code .git} likewise stays behind. The preview branch push reads history from
 * {@link ContainerPaths#DIFF_GIT_DIR}, which is outside the work tree on purpose, and an agent that
 * found a repository in its workspace might commit into it and confuse that flow.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CodingAgentWorkspaceBridge {

    /**
     * Entries that never cross. Both are reconstructible on the far side, and both are large enough
     * that carrying them would dominate the run.
     */
    private static final Set<String> NOT_CARRIED = Set.of("node_modules", ".git");

    private static final String APP_PARENT = ContainerPaths.APP_DIR.substring(
            0, ContainerPaths.APP_DIR.lastIndexOf('/'));
    private static final String APP_NAME = ContainerPaths.APP_DIR.substring(
            ContainerPaths.APP_DIR.lastIndexOf('/') + 1);

    private final DockerContainerService dockerService;
    private final CodingAgentExecutionService executionService;

    /**
     * Runs a coding agent against the preview container's project and leaves the result in place.
     *
     * @return the agent's closing text, which the CODE step shows the user
     */
    public String run(String containerId, Long userId, AiProvider provider, String instruction) {
        Path hostWorkspace = createHostWorkspace();
        try {
            copyOut(containerId, hostWorkspace);

            // The agent's container mounts this directory at /workspace, so the project sits at
            // /workspace/app for it too — the same path every other step in the pipeline uses.
            CodingAgentResult result = executionService.run(
                    userId, provider, prompt(instruction), hostWorkspace.toString());

            if (!result.success()) {
                // Nothing is copied back on failure, timeout included. A timeout in particular can
                // leave the host checkout half-edited, and carrying that in would hand the preview
                // a project the agent never finished — built, served, and shown as if it were the
                // result. Leaving the container untouched makes the failure clean to retry.
                throw new IllegalStateException(describeFailure(result));
            }

            copyBack(containerId, hostWorkspace);
            reinstallDependencies(containerId);
            return result.output();
        } finally {
            deleteRecursively(hostWorkspace);
        }
    }

    /**
     * A timeout is worth naming separately: it is the one failure where the run may have been
     * doing the right thing and simply needed longer, so "raise the limit or narrow the request" is
     * useful advice and "it failed" is not.
     */
    private String describeFailure(CodingAgentResult result) {
        if (result.timedOut()) {
            return "코딩 에이전트가 제한 시간 안에 끝나지 않았습니다. 변경은 반영하지 않았습니다.";
        }
        String detail = result.errorOutput() == null || result.errorOutput().isBlank()
                ? result.output()
                : result.errorOutput();
        return "코딩 에이전트가 실패했습니다(exit " + result.exitCode() + "). 변경은 반영하지 않았습니다."
                + (detail == null || detail.isBlank() ? "" : " " + detail.strip());
    }

    /**
     * The agent gets a free-form prompt rather than the tool loop's system prompt, so the two
     * conventions the rest of the pipeline depends on have to be stated here or they do not hold.
     */
    private String prompt(String instruction) {
        return """
                The project is at %s. Work only inside that directory — everything after you (the
                preview, the change diff, the repository push, the deploy) looks there and nowhere
                else, so files written elsewhere are silently lost.

                Implement what is requested, completely. Install dependencies if you need them.
                Do NOT start a preview or development server: serving is handled after you finish.

                When you are done, reply with a short summary for the person who asked — the owner
                of the app, not a developer. Say what the app can do now, in plain terms. Do not
                mention file paths, frameworks, containers, or build output.

                Request:
                %s
                """.formatted(ContainerPaths.APP_DIR, instruction);
    }

    private Path createHostWorkspace() {
        try {
            // 0700: the checkout is the user's code, and on a shared host the default would let any
            // account read it for as long as the agent runs.
            Path dir = Files.createTempDirectory("qeploy-coding-agent-");
            dir.toFile().setReadable(false, false);
            dir.toFile().setReadable(true, true);
            dir.toFile().setExecutable(false, false);
            dir.toFile().setExecutable(true, true);
            return dir;
        } catch (IOException e) {
            throw new IllegalStateException("코딩 에이전트 작업 디렉터리를 만들지 못했습니다", e);
        }
    }

    /** Container {@code /workspace/app} → {@code <host>/app}. */
    private void copyOut(String containerId, Path hostWorkspace) {
        long files = dockerService.copyDirectoryFromContainer(
                containerId, ContainerPaths.APP_DIR, hostWorkspace, NOT_CARRIED);
        log.info("[CodingAgentBridge] 워크스페이스 반출 완료 | 파일={}개", files);
    }

    /**
     * {@code <host>/app} → container {@code /workspace/app}.
     *
     * <p>The container's copy of the directory is emptied first. Docker's copy overlays rather than
     * replaces, so a file the agent deleted would otherwise survive on the container side and the
     * project would build with code the agent thought it had removed.</p>
     */
    private void copyBack(String containerId, Path hostWorkspace) {
        // node_modules stays: it is the one thing not carried out, so it is also the one thing that
        // must not be cleared here — clearing it would strand the container without dependencies.
        dockerService.exec(containerId,
                "find " + ContainerPaths.APP_DIR + " -mindepth 1 -maxdepth 1 "
                        + "-not -name node_modules -exec rm -rf {} +");

        dockerService.copyDirectoryToContainer(
                containerId, hostWorkspace.resolve(APP_NAME), APP_PARENT);
        log.info("[CodingAgentBridge] 워크스페이스 반입 완료");
    }

    /**
     * The agent installed into its own container, which is gone; and it may have added a dependency
     * to package.json. Without this the preview build fails on an import that was never installed
     * on this side.
     */
    private void reinstallDependencies(String containerId) {
        String hasPackageJson = dockerService.exec(containerId,
                "[ -f " + ContainerPaths.APP_DIR + "/package.json ] && echo yes || echo no").trim();
        if (!"yes".equals(hasPackageJson)) {
            return;
        }
        log.info("[CodingAgentBridge] npm install 실행");
        dockerService.exec(containerId, "cd " + ContainerPaths.APP_DIR + " && npm install");
    }


    /**
     * Resolves a tar entry under the destination, refusing anything that would land outside it.
     * A tar can name {@code ../../etc/passwd}, and this one comes from a container running the
     * user's code.
     */

    private void deleteRecursively(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("[CodingAgentBridge] 임시 파일 삭제 실패: {}", path, e);
                }
            });
        } catch (IOException e) {
            // The checkout is a copy; failing to clean it up must not fail a run that succeeded.
            log.warn("[CodingAgentBridge] 작업 디렉터리 정리 실패: {}", dir, e);
        }
    }
}
