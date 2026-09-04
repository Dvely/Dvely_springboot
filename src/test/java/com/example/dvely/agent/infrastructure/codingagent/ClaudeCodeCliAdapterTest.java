package com.example.dvely.agent.infrastructure.codingagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.port.out.CodingAgentCommand;
import com.example.dvely.agent.application.port.out.CodingAgentResult;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.codingagent.CodingAgentContainerRunner.ContainerRunOutcome;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ClaudeCodeCliAdapterTest {

    private static final String API_KEY = "sk-ant-api03-secretvalue";

    private CodingAgentContainerRunner runner;
    private CodingAgentProperties properties;
    private ClaudeCodeCliAdapter adapter;

    @BeforeEach
    void setUp() {
        runner = mock(CodingAgentContainerRunner.class);
        properties = new CodingAgentProperties();
        properties.setProvisionRetryDelay(Duration.ZERO);
        adapter = new ClaudeCodeCliAdapter(runner, properties);
    }

    private static CodingAgentCommand command() {
        return new CodingAgentCommand("빌드 로그를 분석해줘", "/host/checkout", API_KEY, Duration.ofMinutes(3));
    }

    @Test
    void reportsAnthropicAsItsVendor() {
        // The credential store is keyed by vendor, so the adapter has to declare which key it wants.
        assertThat(adapter.vendor()).isEqualTo(AiProvider.ANTHROPIC);
    }

    @Test
    void runsTheOfficialCliInNonInteractivePrintMode() {
        when(runner.run(any(), anyList(), anyList(), any()))
                .thenReturn(new ContainerRunOutcome(0, "done", "", false));

        adapter.run(command());

        ArgumentCaptor<List<String>> argv = ArgumentCaptor.captor();
        verify(runner).run(eq("/host/checkout"), argv.capture(), anyList(), eq(Duration.ofMinutes(3)));
        assertThat(argv.getValue()).containsExactly("claude", "-p", "빌드 로그를 분석해줘");
    }

    @Test
    void passesTheKeyThroughTheEnvironmentAndNeverInTheCommand() {
        when(runner.run(any(), anyList(), anyList(), any()))
                .thenReturn(new ContainerRunOutcome(0, "done", "", false));

        adapter.run(command());

        ArgumentCaptor<List<String>> argv = ArgumentCaptor.captor();
        ArgumentCaptor<List<String>> env = ArgumentCaptor.captor();
        verify(runner).run(any(), argv.capture(), env.capture(), any());

        assertThat(env.getValue()).containsExactly("ANTHROPIC_API_KEY=" + API_KEY);
        // A key on the command line would be readable from /proc by anything else in the container.
        assertThat(argv.getValue()).noneMatch(arg -> arg.contains(API_KEY));
    }

    @Test
    void promptIsPassedAsASingleArgumentSoThereIsNoShellToInjectInto() {
        when(runner.run(any(), anyList(), anyList(), any()))
                .thenReturn(new ContainerRunOutcome(0, "ok", "", false));

        CodingAgentCommand hostile = new CodingAgentCommand(
                "; rm -rf / #", "/host/checkout", API_KEY, Duration.ofMinutes(1));
        adapter.run(hostile);

        ArgumentCaptor<List<String>> argv = ArgumentCaptor.captor();
        verify(runner).run(any(), argv.capture(), anyList(), any());
        // The metacharacters survive intact as one argv element — proof they were never parsed
        // by a shell rather than proof they were escaped correctly.
        assertThat(argv.getValue()).containsExactly("claude", "-p", "; rm -rf / #");
    }

    @Test
    void mapsACleanExitToSuccess() {
        when(runner.run(any(), anyList(), anyList(), any()))
                .thenReturn(new ContainerRunOutcome(0, "분석 결과", "warn", false));

        CodingAgentResult result = adapter.run(command());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("분석 결과");
        assertThat(result.errorOutput()).isEqualTo("warn");
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    void mapsANonZeroExitToFailureKeepingTheExitCode() {
        when(runner.run(any(), anyList(), anyList(), any()))
                .thenReturn(new ContainerRunOutcome(2, "partial", "boom", false));

        CodingAgentResult result = adapter.run(command());

        assertThat(result.success()).isFalse();
        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).isEqualTo(2);
    }

    @Test
    void mapsATimeoutToItsOwnOutcomeRatherThanAPlainFailure() {
        when(runner.run(any(), anyList(), anyList(), any()))
                .thenReturn(new ContainerRunOutcome(-1, "so far", "", true));

        CodingAgentResult result = adapter.run(command());

        // A caller deciding whether a retry is safe needs to tell "killed mid-edit" apart from
        // "exited cleanly with an error".
        assertThat(result.timedOut()).isTrue();
        assertThat(result.success()).isFalse();
    }

    @Test
    void retriesWhenTheContainerCouldNotBeProvisioned() {
        properties.setMaxProvisionAttempts(3);
        when(runner.run(any(), anyList(), anyList(), any()))
                .thenThrow(new CodingAgentProvisionException("이미지 없음"))
                .thenThrow(new CodingAgentProvisionException("이미지 없음"))
                .thenReturn(new ContainerRunOutcome(0, "ok", "", false));

        CodingAgentResult result = adapter.run(command());

        assertThat(result.success()).isTrue();
        verify(runner, times(3)).run(any(), anyList(), anyList(), any());
    }

    @Test
    void givesUpAfterTheConfiguredNumberOfProvisionAttempts() {
        properties.setMaxProvisionAttempts(2);
        when(runner.run(any(), anyList(), anyList(), any()))
                .thenThrow(new CodingAgentProvisionException("이미지 없음"));

        assertThatThrownBy(() -> adapter.run(command()))
                .isInstanceOf(CodingAgentProvisionException.class);

        verify(runner, times(2)).run(any(), anyList(), anyList(), any());
    }

    @Test
    void doesNotRetryAFailureRaisedOnceTheAgentWasAlreadyRunning() {
        properties.setMaxProvisionAttempts(3);
        when(runner.run(any(), anyList(), anyList(), any()))
                .thenThrow(new IllegalStateException("실행 중 인터럽트"));

        assertThatThrownBy(() -> adapter.run(command()))
                .isInstanceOf(IllegalStateException.class);

        // Replaying a run that may have already edited the workspace would stack a second
        // partial change on top of the first.
        verify(runner, times(1)).run(any(), anyList(), anyList(), any());
    }
}
