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

class CodexCliAdapterTest {

    private static final String API_KEY = "sk-proj-secretvalue";

    private CodingAgentContainerRunner runner;
    private CodingAgentProperties properties;
    private CodexCliAdapter adapter;

    @BeforeEach
    void setUp() {
        runner = mock(CodingAgentContainerRunner.class);
        properties = new CodingAgentProperties();
        properties.setProvisionRetryDelay(Duration.ZERO);
        adapter = new CodexCliAdapter(runner, properties);
    }

    private static CodingAgentCommand command() {
        return new CodingAgentCommand("Dockerfile 을 최적화해줘", "/host/checkout", API_KEY, Duration.ofMinutes(4));
    }

    @Test
    void reportsOpenAiAsItsVendorSoTheOpenAiKeyIsLookedUp() {
        // Codex runs on the same OpenAI key a direct API call would use — the credential store is
        // keyed by vendor, not by execution mode, so the user pastes that key once.
        assertThat(adapter.vendor()).isEqualTo(AiProvider.OPENAI);
    }

    @Test
    void runsTheOfficialCliInItsNonInteractiveMode() {
        when(runner.run(any(), any(), anyList(), anyList(), any()))
                .thenReturn(new ContainerRunOutcome(0, "done", "", false));

        adapter.run(command());

        ArgumentCaptor<List<String>> argv = ArgumentCaptor.captor();
        verify(runner).run(eq("/host/checkout"), any(), argv.capture(), anyList(), eq(Duration.ofMinutes(4)));
        assertThat(argv.getValue()).containsExactly("codex", "exec", "Dockerfile 을 최적화해줘");
    }

    @Test
    void authenticatesThroughALoginStepBecauseCodexIgnoresTheEnvironmentVariable() {
        // Measured against codex-cli 0.153.2: `codex exec` with OPENAI_API_KEY set fails with
        // "401 Missing bearer or basic authentication in header". The CLI only accepts a key
        // through `codex login --with-api-key`, which reads it from stdin.
        when(runner.run(any(), any(), anyList(), anyList(), any()))
                .thenReturn(new ContainerRunOutcome(0, "done", "", false));

        adapter.run(command());

        ArgumentCaptor<CodingAgentContainerRunner.AuthStep> auth = ArgumentCaptor.captor();
        ArgumentCaptor<List<String>> env = ArgumentCaptor.captor();
        verify(runner).run(any(), auth.capture(), anyList(), env.capture(), any());

        assertThat(auth.getValue().argv()).containsExactly("codex", "login", "--with-api-key");
        assertThat(auth.getValue().stdin()).isEqualTo(API_KEY);
        assertThat(env.getValue()).isEmpty();
    }

    @Test
    void keepsTheKeyOutOfArgvEntirely() {
        when(runner.run(any(), any(), anyList(), anyList(), any()))
                .thenReturn(new ContainerRunOutcome(0, "done", "", false));

        adapter.run(command());

        ArgumentCaptor<List<String>> argv = ArgumentCaptor.captor();
        ArgumentCaptor<CodingAgentContainerRunner.AuthStep> auth = ArgumentCaptor.captor();
        verify(runner).run(any(), auth.capture(), argv.capture(), anyList(), any());

        // stdin rather than argv or env: a key on the command line is readable from /proc, and an
        // environment variable is readable from /proc/<pid>/environ.
        assertThat(argv.getValue()).noneMatch(arg -> arg.contains(API_KEY));
        assertThat(auth.getValue().argv()).noneMatch(arg -> arg.contains(API_KEY));
    }

    @Test
    void honoursAConfiguredArgvPrefixWhenTheCliInterfaceChanges() {
        // The CLI is an external tool; a renamed non-interactive mode must be fixable in config
        // alongside the image pin rather than requiring a code change.
        properties.setCodex(CodingAgentProperties.Cli.of("codex", "run", "--quiet"));
        when(runner.run(any(), any(), anyList(), anyList(), any()))
                .thenReturn(new ContainerRunOutcome(0, "ok", "", false));

        adapter.run(command());

        ArgumentCaptor<List<String>> argv = ArgumentCaptor.captor();
        verify(runner).run(any(), any(), argv.capture(), anyList(), any());
        assertThat(argv.getValue())
                .containsExactly("codex", "run", "--quiet", "Dockerfile 을 최적화해줘");
    }

    @Test
    void promptStaysASingleArgumentSoThereIsNoShellToInjectInto() {
        when(runner.run(any(), any(), anyList(), anyList(), any()))
                .thenReturn(new ContainerRunOutcome(0, "ok", "", false));

        adapter.run(new CodingAgentCommand("a; shutdown -h now && echo $HOME",
                "/host/checkout", API_KEY, Duration.ofMinutes(1)));

        ArgumentCaptor<List<String>> argv = ArgumentCaptor.captor();
        verify(runner).run(any(), any(), argv.capture(), anyList(), any());
        assertThat(argv.getValue()).last().isEqualTo("a; shutdown -h now && echo $HOME");
    }

    @Test
    void mapsACleanExitToSuccess() {
        when(runner.run(any(), any(), anyList(), anyList(), any()))
                .thenReturn(new ContainerRunOutcome(0, "결과", "note", false));

        CodingAgentResult result = adapter.run(command());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("결과");
        assertThat(result.errorOutput()).isEqualTo("note");
    }

    @Test
    void mapsANonZeroExitToFailureKeepingTheExitCode() {
        when(runner.run(any(), any(), anyList(), anyList(), any()))
                .thenReturn(new ContainerRunOutcome(7, "partial", "boom", false));

        CodingAgentResult result = adapter.run(command());

        assertThat(result.success()).isFalse();
        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).isEqualTo(7);
    }

    @Test
    void mapsATimeoutToItsOwnOutcome() {
        when(runner.run(any(), any(), anyList(), anyList(), any()))
                .thenReturn(new ContainerRunOutcome(-1, "so far", "", true));

        CodingAgentResult result = adapter.run(command());

        assertThat(result.timedOut()).isTrue();
        assertThat(result.success()).isFalse();
    }

    @Test
    void retriesProvisioningFailuresButNotFailuresFromARunningAgent() {
        properties.setMaxProvisionAttempts(2);
        when(runner.run(any(), any(), anyList(), anyList(), any()))
                .thenThrow(new CodingAgentProvisionException("이미지 없음"))
                .thenReturn(new ContainerRunOutcome(0, "ok", "", false));

        assertThat(adapter.run(command()).success()).isTrue();
        verify(runner, times(2)).run(any(), any(), anyList(), anyList(), any());
    }

    @Test
    void doesNotRetryOnceTheAgentHasStarted() {
        properties.setMaxProvisionAttempts(3);
        when(runner.run(any(), any(), anyList(), anyList(), any()))
                .thenThrow(new IllegalStateException("실행 중 폭발"));

        assertThatThrownBy(() -> adapter.run(command())).isInstanceOf(IllegalStateException.class);

        verify(runner, times(1)).run(any(), any(), anyList(), anyList(), any());
    }
}
