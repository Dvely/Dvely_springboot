package com.example.dvely.agent.infrastructure.codingagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.infrastructure.codingagent.CodingAgentContainerRunner.ContainerRunOutcome;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
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
        return runner.run("/host/checkout", List.of("claude", "-p", "hi"),
                List.of("ANTHROPIC_API_KEY=sk-ant-secret"), Duration.ofSeconds(5));
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
    void execCarriesTheKeyInTheEnvironment() {
        stubImagePresent();
        stubContainerLifecycle();
        stubExec(true, 0, "ok", "");

        run();

        ArgumentCaptor<List<String>> env = ArgumentCaptor.captor();
        verify(execCreateCmd).withEnv(env.capture());
        assertThat(env.getValue()).containsExactly("ANTHROPIC_API_KEY=sk-ant-secret");
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

        ContainerRunOutcome outcome = runner.run("/host/checkout", List.of("claude", "-p", "hi"),
                List.of(), Duration.ofMillis(50));

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

        runner.run("/host/checkout", List.of("claude"), List.of(), Duration.ofMillis(50));

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
    void wrapsAContainerCreateFailureAsARetryableProvisionFailure() {
        stubImagePresent();
        createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd(anyString())).thenReturn(createCmd);
        when(createCmd.exec()).thenThrow(new IllegalStateException("daemon busy"));

        // Nothing has run yet, so the caller is allowed to retry this one.
        assertThatThrownBy(this::run).isInstanceOf(CodingAgentProvisionException.class);
    }
}
