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
        when(runner.run(any(), any(), anyList(), any(), any()))
                .thenReturn(new ContainerRunOutcome(0, "done", "", false));

        adapter.run(command());

        ArgumentCaptor<List<String>> argv = ArgumentCaptor.captor();
        verify(runner).run(eq("/host/checkout"), any(), argv.capture(), eq(Duration.ofMinutes(3)), any());
        assertThat(argv.getValue()).containsExactly(
                "claude", "--bare", "-p",
                "--permission-mode", "acceptEdits",
                "--permission-prompts", "none",
                "--setting-sources", "user",
                "--max-turns", "40",
                "--no-session-persistence",
                "--output-format", "stream-json",
                "--verbose",
                "--max-budget-usd", "3",
                "빌드 로그를 분석해줘");
    }

    @Test
    void neverLetsTheRepositoryConfigureTheAgent() {
        // #596: the workspace is the user's repository. Without --bare a -p run loads its
        // .claude/settings.json hooks and .mcp.json servers; --bare alone still applies the project
        // settings' env block, which --setting-sources user stops; without a permission mode it
        // denies every edit. The real-CLI proof is ClaudeCodeCliAdapterIntegrationTest.
        when(runner.run(any(), any(), anyList(), any(), any()))
                .thenReturn(new ContainerRunOutcome(0, "done", "", false));

        adapter.run(command());

        ArgumentCaptor<List<String>> argv = ArgumentCaptor.captor();
        verify(runner).run(any(), any(), argv.capture(), any(), any());
        assertThat(argv.getValue()).contains("--bare");
        assertThat(argv.getValue()).containsSequence("--setting-sources", "user");
        assertThat(argv.getValue()).containsSequence("--permission-mode", "acceptEdits");
        assertThat(argv.getValue()).doesNotContain("--dangerously-skip-permissions", "bypassPermissions");
    }

    @Test
    void passesTheKeyThroughTheEnvironmentAndNeverInTheCommand() {
        // Claude Code does read ANTHROPIC_API_KEY — verified against 2.1.260, where `claude -p`
        // with it set reaches the API and returns a real account-level answer. So unlike Codex,
        // this adapter needs no login step.
        when(runner.run(any(), any(), anyList(), any(), any()))
                .thenReturn(new ContainerRunOutcome(0, "done", "", false));

        adapter.run(command());

        ArgumentCaptor<List<String>> argv = ArgumentCaptor.captor();
        ArgumentCaptor<CodingAgentContainerRunner.Credential> cred = ArgumentCaptor.captor();
        verify(runner).run(any(), cred.capture(), argv.capture(), any(), any());

        assertThat(cred.getValue())
                .isEqualTo(new CodingAgentContainerRunner.Credential.EnvVar("ANTHROPIC_API_KEY", API_KEY));
        // A key on the command line would be readable from /proc by anything else in the container.
        assertThat(argv.getValue()).noneMatch(arg -> arg.contains(API_KEY));
    }

    @Test
    void promptIsPassedAsASingleArgumentSoThereIsNoShellToInjectInto() {
        when(runner.run(any(), any(), anyList(), any(), any()))
                .thenReturn(new ContainerRunOutcome(0, "ok", "", false));

        CodingAgentCommand hostile = new CodingAgentCommand(
                "; rm -rf / #", "/host/checkout", API_KEY, Duration.ofMinutes(1));
        adapter.run(hostile);

        ArgumentCaptor<List<String>> argv = ArgumentCaptor.captor();
        verify(runner).run(any(), any(), argv.capture(), any(), any());
        // The metacharacters survive intact as one argv element — proof they were never parsed
        // by a shell rather than proof they were escaped correctly.
        assertThat(argv.getValue()).last().isEqualTo("; rm -rf / #");
        assertThat(argv.getValue()).filteredOn(arg -> arg.contains("rm -rf")).hasSize(1);
    }

    /**
     * #600: stdout is stream-json, one event per line. The runner hands each line to the adapter as
     * it arrives; tool calls become activity and the closing result line is the answer — the same
     * text the plain output used to be. The lines are the shapes printed by the pinned 2.1.316.
     */
    @Test
    void readsTheStreamAsActivityAndTakesTheResultLineAsTheAnswer() {
        List<String> stream = List.of(
                "{\"type\":\"system\",\"subtype\":\"init\",\"cwd\":\"/workspace\",\"tools\":[\"Bash\",\"Edit\",\"Read\"]}",
                "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{\"file_path\":\"/workspace/app/styles.css\"}}]}}",
                "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"content\":\".btn-primary { }\"}]}}",
                "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Edit\",\"input\":{\"file_path\":\"/workspace/app/styles.css\",\"old_string\":\"a\",\"new_string\":\"b\"}}]}}",
                "{\"duration_ms\":278,\"is_error\":false,\"subtype\":\"success\",\"result\":\"저장 버튼만 키웠습니다.\",\"type\":\"result\"}");
        when(runner.run(any(), any(), anyList(), any(), any())).thenAnswer(invocation -> {
            Consumer<String> lines = invocation.getArgument(4);
            stream.forEach(lines);
            return new ContainerRunOutcome(0, String.join("\n", stream), "", false);
        });
        List<CodingAgentActivity> seen = new ArrayList<>();

        CodingAgentResult result = adapter.run(new CodingAgentCommand(
                "버튼만 더 크게", "/host/checkout", API_KEY, Duration.ofMinutes(3), seen::add));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("저장 버튼만 키웠습니다.");
        assertThat(seen).containsExactly(
                new CodingAgentActivity(CodingAgentActivity.Kind.READ, "styles.css"),
                new CodingAgentActivity(CodingAgentActivity.Kind.EDIT, "styles.css"));
    }

    @Test
    void aFailedRunsMessageIsTheResultTextNotTheRawStream() {
        // describeFailure shows this to the user; a JSON transcript there would be unreadable.
        String error = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"result\":\"Invalid API key · Fix external API key\"}";
        when(runner.run(any(), any(), anyList(), any(), any())).thenAnswer(invocation -> {
            Consumer<String> lines = invocation.getArgument(4);
            lines.accept(error);
            return new ContainerRunOutcome(1, error, "", false);
        });

        CodingAgentResult result = adapter.run(command());

        assertThat(result.success()).isFalse();
        assertThat(result.output()).isEqualTo("Invalid API key · Fix external API key");
    }

    @Test
    void mapsACleanExitToSuccess() {
        when(runner.run(any(), any(), anyList(), any(), any()))
                .thenReturn(new ContainerRunOutcome(0, "분석 결과", "warn", false));

        CodingAgentResult result = adapter.run(command());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("분석 결과");
        assertThat(result.errorOutput()).isEqualTo("warn");
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    void mapsANonZeroExitToFailureKeepingTheExitCode() {
        when(runner.run(any(), any(), anyList(), any(), any()))
                .thenReturn(new ContainerRunOutcome(2, "partial", "boom", false));

        CodingAgentResult result = adapter.run(command());

        assertThat(result.success()).isFalse();
        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).isEqualTo(2);
    }

    @Test
    void mapsATimeoutToItsOwnOutcomeRatherThanAPlainFailure() {
        when(runner.run(any(), any(), anyList(), any(), any()))
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
        when(runner.run(any(), any(), anyList(), any(), any()))
                .thenThrow(new CodingAgentProvisionException("이미지 없음"))
                .thenThrow(new CodingAgentProvisionException("이미지 없음"))
                .thenReturn(new ContainerRunOutcome(0, "ok", "", false));

        CodingAgentResult result = adapter.run(command());

        assertThat(result.success()).isTrue();
        verify(runner, times(3)).run(any(), any(), anyList(), any(), any());
    }

    @Test
    void givesUpAfterTheConfiguredNumberOfProvisionAttempts() {
        properties.setMaxProvisionAttempts(2);
        when(runner.run(any(), any(), anyList(), any(), any()))
                .thenThrow(new CodingAgentProvisionException("이미지 없음"));

        assertThatThrownBy(() -> adapter.run(command()))
                .isInstanceOf(CodingAgentProvisionException.class);

        verify(runner, times(2)).run(any(), any(), anyList(), any(), any());
    }

    @Test
    void doesNotRetryAFailureRaisedOnceTheAgentWasAlreadyRunning() {
        properties.setMaxProvisionAttempts(3);
        when(runner.run(any(), any(), anyList(), any(), any()))
                .thenThrow(new IllegalStateException("실행 중 인터럽트"));

        assertThatThrownBy(() -> adapter.run(command()))
                .isInstanceOf(IllegalStateException.class);

        // Replaying a run that may have already edited the workspace would stack a second
        // partial change on top of the first.
        verify(runner, times(1)).run(any(), any(), anyList(), any(), any());
    }
}
