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

import com.example.dvely.agent.application.port.out.CodingAgentActivity;
import com.example.dvely.agent.application.port.out.CodingAgentCommand;
import com.example.dvely.agent.application.port.out.CodingAgentResult;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.codingagent.CodingAgentContainerRunner.ContainerRunOutcome;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
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
        when(runner.run(any(), any(), anyList(), any(), any()))
                .thenReturn(new ContainerRunOutcome(0, "done", "", false));

        adapter.run(command());

        ArgumentCaptor<List<String>> argv = ArgumentCaptor.captor();
        verify(runner).run(eq("/host/checkout"), any(), argv.capture(), eq(Duration.ofMinutes(4)), any());
        assertThat(argv.getValue()).containsExactly("codex", "exec", "--dangerously-bypass-approvals-and-sandbox", "--json", "--model", "gpt-5.6-luna", "Dockerfile 을 최적화해줘");
    }

    @Test
    void authenticatesThroughALoginStepBecauseCodexIgnoresTheEnvironmentVariable() {
        // Measured against codex-cli 0.153.2: `codex exec` with OPENAI_API_KEY set fails with
        // "401 Missing bearer or basic authentication in header". The CLI only accepts a key
        // through `codex login --with-api-key`, which reads it from stdin.
        when(runner.run(any(), any(), anyList(), any(), any()))
                .thenReturn(new ContainerRunOutcome(0, "done", "", false));

        adapter.run(command());

        ArgumentCaptor<CodingAgentContainerRunner.Credential> cred = ArgumentCaptor.captor();
        verify(runner).run(any(), cred.capture(), anyList(), any(), any());

        assertThat(cred.getValue())
                .isEqualTo(new CodingAgentContainerRunner.Credential.LoginCommand(
                        List.of("codex", "login", "--with-api-key"), API_KEY));
    }

    @Test
    void keepsTheKeyOutOfArgvEntirely() {
        when(runner.run(any(), any(), anyList(), any(), any()))
                .thenReturn(new ContainerRunOutcome(0, "done", "", false));

        adapter.run(command());

        ArgumentCaptor<List<String>> argv = ArgumentCaptor.captor();
        ArgumentCaptor<CodingAgentContainerRunner.Credential> cred = ArgumentCaptor.captor();
        verify(runner).run(any(), cred.capture(), argv.capture(), any(), any());

        // A staged file rather than argv or env: a key on the command line is readable from /proc,
        // and an environment variable is both readable from /proc/<pid>/environ and dumped into
        // docker-java's DEBUG log line.
        assertThat(argv.getValue()).noneMatch(arg -> arg.contains(API_KEY));
        var login = (CodingAgentContainerRunner.Credential.LoginCommand) cred.getValue();
        assertThat(login.argv()).noneMatch(arg -> arg.contains(API_KEY));
    }

    @Test
    void honoursAConfiguredArgvPrefixWhenTheCliInterfaceChanges() {
        // The CLI is an external tool; a renamed non-interactive mode must be fixable in config
        // alongside the image pin rather than requiring a code change.
        // Cli.of leaves the model blank, so this also shows the two settings are independent:
        // overriding how the CLI is invoked does not drag a model choice along with it.
        properties.setCodex(CodingAgentProperties.Cli.of("codex", "run", "--quiet"));
        when(runner.run(any(), any(), anyList(), any(), any()))
                .thenReturn(new ContainerRunOutcome(0, "ok", "", false));

        adapter.run(command());

        ArgumentCaptor<List<String>> argv = ArgumentCaptor.captor();
        verify(runner).run(any(), any(), argv.capture(), any(), any());
        assertThat(argv.getValue())
                .containsExactly("codex", "run", "--quiet", "Dockerfile 을 최적화해줘");
    }

    @Test
    void promptStaysASingleArgumentSoThereIsNoShellToInjectInto() {
        when(runner.run(any(), any(), anyList(), any(), any()))
                .thenReturn(new ContainerRunOutcome(0, "ok", "", false));

        adapter.run(new CodingAgentCommand("a; shutdown -h now && echo $HOME",
                "/host/checkout", API_KEY, Duration.ofMinutes(1)));

        ArgumentCaptor<List<String>> argv = ArgumentCaptor.captor();
        verify(runner).run(any(), any(), argv.capture(), any(), any());
        assertThat(argv.getValue()).last().isEqualTo("a; shutdown -h now && echo $HOME");
    }

    @Test
    void runsTheCheapestModelTierByDefault() {
        // The CLI would otherwise pick gpt-5.6-sol. Measured on the same trivial prompt, luna used
        // 9,692 tokens against sol's 11,203 for an identical answer, so the larger tier is cost
        // with nothing behind it until a task actually needs it.
        when(runner.run(any(), any(), anyList(), any(), any()))
                .thenReturn(new ContainerRunOutcome(0, "ok", "", false));

        adapter.run(command());

        ArgumentCaptor<List<String>> argv = ArgumentCaptor.captor();
        verify(runner).run(any(), any(), argv.capture(), any(), any());
        assertThat(argv.getValue())
                .containsExactly("codex", "exec", "--dangerously-bypass-approvals-and-sandbox", "--json", "--model", "gpt-5.6-luna", "Dockerfile 을 최적화해줘");
    }

    @Test
    void leavesTheCliDefaultAloneWhenNoModelIsConfigured() {
        properties.getCodex().setModel("");
        when(runner.run(any(), any(), anyList(), any(), any()))
                .thenReturn(new ContainerRunOutcome(0, "ok", "", false));

        adapter.run(command());

        ArgumentCaptor<List<String>> argv = ArgumentCaptor.captor();
        verify(runner).run(any(), any(), argv.capture(), any(), any());
        assertThat(argv.getValue()).doesNotContain("--model");
    }

    /**
     * #600: stdout is {@code exec --json}, one event per line, handed to the adapter as it arrives.
     * Commands and patches become activity, and the last agent message is the answer — the same
     * text the plain output used to be. The lines are the shapes printed by the pinned 0.153.2.
     */
    @Test
    void readsTheJsonStreamAsActivityAndTakesTheLastMessageAsTheAnswer() {
        List<String> stream = List.of(
                "{\"type\":\"thread.started\",\"thread_id\":\"01a21e67-00a1-72a3-8835-867a88a8ebf9\"}",
                "{\"type\":\"turn.started\"}",
                "{\"type\":\"item.started\",\"item\":{\"id\":\"item_2\",\"type\":\"command_execution\",\"command\":\"/bin/sh -lc 'cat index.html'\",\"aggregated_output\":\"\",\"exit_code\":null,\"status\":\"in_progress\"}}",
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_2\",\"type\":\"command_execution\",\"command\":\"/bin/sh -lc 'cat index.html'\",\"aggregated_output\":\"<h1>hi</h1>\\n\",\"exit_code\":0,\"status\":\"completed\"}}",
                "{\"type\":\"item.started\",\"item\":{\"id\":\"item_3\",\"type\":\"file_change\",\"changes\":[{\"path\":\"/workspace/app/index.html\",\"kind\":\"update\"}],\"status\":\"in_progress\"}}",
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_8\",\"type\":\"agent_message\",\"text\":\"제목을 바꿨습니다.\"}}",
                "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":400,\"cached_input_tokens\":0,\"output_tokens\":40}}");
        when(runner.run(any(), any(), anyList(), any(), any())).thenAnswer(invocation -> {
            Consumer<String> lines = invocation.getArgument(4);
            stream.forEach(lines);
            return new ContainerRunOutcome(0, String.join("\n", stream), "Reading additional input from stdin...", false);
        });
        List<CodingAgentActivity> seen = new ArrayList<>();

        CodingAgentResult result = adapter.run(new CodingAgentCommand(
                "제목을 바꿔줘", "/host/checkout", API_KEY, Duration.ofMinutes(3), seen::add));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("제목을 바꿨습니다.");
        assertThat(seen).containsExactly(
                new CodingAgentActivity(CodingAgentActivity.Kind.COMMAND, "cat index.html"),
                new CodingAgentActivity(CodingAgentActivity.Kind.EDIT, "index.html"));
    }

    @Test
    void aFailedRunCarriesTheErrorFromTheStreamNotStderrNoise() {
        // Measured on 0.153.2: with --json a rejected key ends in a turn.failed line on stdout and
        // stderr holds only "Reading additional input from stdin..." — describeFailure prefers the
        // error output, so without this the user would see that line instead of the reason.
        String failed = "{\"type\":\"turn.failed\",\"error\":{\"message\":\"unexpected status 401 Unauthorized: Incorrect API key provided\"}}";
        when(runner.run(any(), any(), anyList(), any(), any())).thenAnswer(invocation -> {
            Consumer<String> lines = invocation.getArgument(4);
            lines.accept(failed);
            return new ContainerRunOutcome(1, failed, "Reading additional input from stdin...", false);
        });

        CodingAgentResult result = adapter.run(command());

        assertThat(result.success()).isFalse();
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.errorOutput()).isEqualTo("unexpected status 401 Unauthorized: Incorrect API key provided");
    }

    @Test
    void aFailedRunWithoutAStreamErrorKeepsStderr() {
        when(runner.run(any(), any(), anyList(), any(), any()))
                .thenReturn(new ContainerRunOutcome(2, "", "codex: unknown flag", false));

        CodingAgentResult result = adapter.run(command());

        assertThat(result.errorOutput()).isEqualTo("codex: unknown flag");
    }

    @Test
    void mapsACleanExitToSuccess() {
        when(runner.run(any(), any(), anyList(), any(), any()))
                .thenReturn(new ContainerRunOutcome(0, "결과", "note", false));

        CodingAgentResult result = adapter.run(command());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("결과");
        assertThat(result.errorOutput()).isEqualTo("note");
    }

    @Test
    void mapsANonZeroExitToFailureKeepingTheExitCode() {
        when(runner.run(any(), any(), anyList(), any(), any()))
                .thenReturn(new ContainerRunOutcome(7, "partial", "boom", false));

        CodingAgentResult result = adapter.run(command());

        assertThat(result.success()).isFalse();
        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).isEqualTo(7);
    }

    @Test
    void mapsATimeoutToItsOwnOutcome() {
        when(runner.run(any(), any(), anyList(), any(), any()))
                .thenReturn(new ContainerRunOutcome(-1, "so far", "", true));

        CodingAgentResult result = adapter.run(command());

        assertThat(result.timedOut()).isTrue();
        assertThat(result.success()).isFalse();
    }

    @Test
    void retriesProvisioningFailuresButNotFailuresFromARunningAgent() {
        properties.setMaxProvisionAttempts(2);
        when(runner.run(any(), any(), anyList(), any(), any()))
                .thenThrow(new CodingAgentProvisionException("이미지 없음"))
                .thenReturn(new ContainerRunOutcome(0, "ok", "", false));

        assertThat(adapter.run(command()).success()).isTrue();
        verify(runner, times(2)).run(any(), any(), anyList(), any(), any());
    }

    @Test
    void doesNotRetryOnceTheAgentHasStarted() {
        properties.setMaxProvisionAttempts(3);
        when(runner.run(any(), any(), anyList(), any(), any()))
                .thenThrow(new IllegalStateException("실행 중 폭발"));

        assertThatThrownBy(() -> adapter.run(command())).isInstanceOf(IllegalStateException.class);

        verify(runner, times(1)).run(any(), any(), anyList(), any(), any());
    }
}
