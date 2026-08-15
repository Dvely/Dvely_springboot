package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.exception.AgentIterationLimitException;
import com.example.dvely.agent.application.exception.CodeAgentExecutionException;
import com.example.dvely.agent.application.port.out.LlmToolResponse;
import com.example.dvely.agent.application.port.out.ToolCall;
import com.example.dvely.agent.domain.value.AgentType;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.config.AiProperties;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.llm.ClaudeToolClient;
import com.example.dvely.agent.infrastructure.llm.OpenAiToolClient;
import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.preview.application.result.PreviewSessionInfo;
import com.example.dvely.preview.application.service.PreviewSessionService;
import com.example.dvely.preview.application.service.PreviewWorkspaceService;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Covers the tool loop's termination and transcript behavior — the failure mode reported from the
 * frontend, where a run that never finished came back as the assistant's chat reply
 * ("최대 반복 횟수 도달로 작업이 종료되었습니다.") with the task marked done.
 */
@ExtendWith(MockitoExtension.class)
class CodeAgentServiceTest {

    private static final String CONTAINER_ID = "container-1";
    private static final String TASK_ID = "task-1";
    private static final int MAX_ITERATIONS = 3;

    @Mock private ClaudeToolClient claudeToolClient;
    @Mock private OpenAiToolClient openAiToolClient;
    @Mock private DockerContainerService dockerService;
    @Mock private PreviewSessionService previewSessionService;
    @Mock private UserRepository userRepository;
    @Mock private AuthCommandService authCommandService;
    @Mock private ProjectRepository projectRepository;
    @Mock private BuildFailureAnalyzer buildFailureAnalyzer;

    private CodeAgentService service;

    @BeforeEach
    void setUp() {
        AiProperties aiProperties = new AiProperties();
        aiProperties.getCodeAgent().setMaxIterations(MAX_ITERATIONS);
        // 워크스페이스 서비스는 실물을 쓴다: 빌드 결과 디렉터리 판별과 serve 기동은 여기 옮겨졌을
        // 뿐 CODE 스텝의 관찰 가능한 동작 그대로이고(같은 dockerService.exec 명령), 이 테스트들이
        // 지키려는 것도 "빌드 안 된 워크스페이스를 서빙하지 않는다"는 그 동작이다.
        service = new CodeAgentService(
                claudeToolClient,
                openAiToolClient,
                dockerService,
                previewSessionService,
                new PreviewWorkspaceService(
                        dockerService,
                        projectRepository,
                        userRepository,
                        authCommandService
                ),
                buildFailureAnalyzer,
                aiProperties
        );
        when(previewSessionService.acquire(TASK_ID)).thenReturn(previewSession());
    }

    @Test
    void exhaustingTheRoundBudgetFailsTheStepInsteadOfReportingSuccess() {
        // Every round asks for another tool call and the model never sends a text-only turn, so
        // the loop runs out of rounds. Before the fix this returned a sentence that became the
        // step summary: AgentPlanExecutor marked the task done and posted it as the chat reply.
        when(claudeToolClient.completeWithTools(anyString(), anyList(), anyList(), any()))
                .thenReturn(toolResponse("end_turn", toolCall("call-1", "execute_command", Map.of("command", "ls"))));
        when(dockerService.exec(eq(CONTAINER_ID), anyString())).thenReturn("app");

        assertThatThrownBy(() -> execute(AiProvider.ANTHROPIC))
                .isInstanceOf(CodeAgentExecutionException.class)
                .hasMessageContaining("한 번의 실행 안에 끝나지 않았습니다")
                .hasMessageContaining(String.valueOf(MAX_ITERATIONS))
                .hasRootCauseInstanceOf(AgentIterationLimitException.class);

        verify(claudeToolClient, times(MAX_ITERATIONS)).completeWithTools(anyString(), anyList(), anyList(), any());
        // The preview server must not be started for a run that never reached a build: doing so is
        // what let an unbuilt workspace be served as a finished result.
        verify(dockerService, never()).exec(eq(CONTAINER_ID), contains("npx serve"));
    }

    @Test
    void iterationLimitFailureCarriesAContinueInstructionAndTheRecentToolTrace() {
        // suggestedFix is not cosmetic: AgentPlanExecutor#withSuggestedFix appends it to the
        // instruction on retry, and the retry reuses this container — that is what makes the retry
        // continue the work rather than scaffold it all over again.
        when(claudeToolClient.completeWithTools(anyString(), anyList(), anyList(), any()))
                .thenReturn(toolResponse("end_turn", toolCall("call-1", "execute_command", Map.of("command", "npm run build"))));
        when(dockerService.exec(eq(CONTAINER_ID), anyString())).thenReturn("built");

        assertThatThrownBy(() -> execute(AiProvider.ANTHROPIC))
                .isInstanceOfSatisfying(CodeAgentExecutionException.class, exception -> {
                    assertThat(exception.suggestedFix()).contains("이어서 진행");
                    assertThat(exception.logExcerpt())
                            .contains("execute_command")
                            .contains("round " + MAX_ITERATIONS);
                });
    }

    @Test
    void openAiLoopFailsTheSameWayWhenItRunsOutOfRounds() {
        when(openAiToolClient.completeWithTools(anyString(), anyList(), anyList(), any()))
                .thenReturn(openAiToolResponse("stop", toolCall("call-1", "execute_command", Map.of("command", "ls"))));
        when(dockerService.exec(eq(CONTAINER_ID), anyString())).thenReturn("app");

        assertThatThrownBy(() -> execute(AiProvider.OPENAI))
                .isInstanceOf(CodeAgentExecutionException.class)
                .hasRootCauseInstanceOf(AgentIterationLimitException.class);

        verify(openAiToolClient, times(MAX_ITERATIONS)).completeWithTools(anyString(), anyList(), anyList(), any());
    }

    @Test
    void keepsOnlyTheHeadAndTailOfAnOversizedToolResultInTheTranscript() {
        // An `npm install` result of this size, re-sent in full on every subsequent round, is what
        // drove the transcript growth that burned rounds in the first place.
        String head = "npm install 시작\n";
        String tail = "\nnpm ERR! build failed";
        String oversized = head + "x".repeat(30_000) + tail;
        when(claudeToolClient.completeWithTools(anyString(), anyList(), anyList(), any()))
                .thenReturn(toolResponse("end_turn", toolCall("call-1", "execute_command", Map.of("command", "npm install"))))
                .thenReturn(textResponse("완료했습니다."));
        when(dockerService.exec(eq(CONTAINER_ID), anyString())).thenAnswer(invocation -> {
            String command = invocation.getArgument(1);
            if (command.contains("serve_ready")) {
                return "serve_ready=yes";
            }
            return command.contains("npm install") ? oversized : "exists";
        });

        service.execute(step(), AiProvider.ANTHROPIC, 1L, null, TASK_ID);

        String toolResult = capturedToolResultContent();
        assertThat(toolResult).hasSizeLessThan(oversized.length());
        assertThat(toolResult).startsWith(head);
        assertThat(toolResult).endsWith(tail);
        assertThat(toolResult).contains("생략");
    }

    @Test
    void doesNotRunAToolCallThatWasCutOffByTheOutputLimit() {
        // stop_reason=max_tokens means the last block stopped mid-generation, so its arguments may
        // be half a file. Writing that truncated content is worse than not writing it.
        when(claudeToolClient.completeWithTools(anyString(), anyList(), anyList(), any()))
                .thenReturn(toolResponse("max_tokens",
                        toolCall("call-1", "write_file", Map.of("path", "/workspace/app/src/App.jsx", "content", "export default function App() {"))))
                .thenReturn(textResponse("완료했습니다."));
        when(dockerService.exec(eq(CONTAINER_ID), anyString())).thenAnswer(serveReadyOr("exists"));

        service.execute(step(), AiProvider.ANTHROPIC, 1L, null, TASK_ID);

        verify(dockerService, never()).exec(eq(CONTAINER_ID), contains("App.jsx"));
        assertThat(capturedToolResultContent()).contains("잘렸으므로 실행하지 않았습니다");
    }

    @Test
    void answersAToolCallWithMissingArgumentsInsteadOfFailingTheWholeRun() {
        // A missing argument used to be a raw cast to null and an NPE out of the loop, failing the
        // task with an unrelated message; the model can simply be told what it left out.
        when(claudeToolClient.completeWithTools(anyString(), anyList(), anyList(), any()))
                .thenReturn(toolResponse("end_turn", toolCall("call-1", "execute_command", Map.of())))
                .thenReturn(textResponse("완료했습니다."));
        when(dockerService.exec(eq(CONTAINER_ID), anyString())).thenAnswer(serveReadyOr("exists"));

        CodeAgentService.CodeResult result = service.execute(step(), AiProvider.ANTHROPIC, 1L, null, TASK_ID);

        assertThat(result.summary()).isEqualTo("완료했습니다.");
        assertThat(capturedToolResultContent()).contains("command 인자가 없어");
    }

    @Test
    void servesTheBuildOutputDirectoryWhenTheBuildProducedOne() {
        when(claudeToolClient.completeWithTools(anyString(), anyList(), anyList(), any()))
                .thenReturn(textResponse("빌드까지 완료했습니다."));
        when(dockerService.exec(eq(CONTAINER_ID), anyString())).thenAnswer(containerWith(
                "/workspace/app/dist"
        ));

        service.execute(step(), AiProvider.ANTHROPIC, 1L, null, TASK_ID);

        verify(dockerService).exec(eq(CONTAINER_ID), contains("npx serve -s /workspace/app/dist"));
    }

    @Test
    void doesNotServeAProjectSourceRootAsIfItWereBuildOutput() {
        // A Vite project with no dist/: its /workspace/app/index.html is the source entry point,
        // and serving that is what made a failed build look like a finished task with a preview
        // that renders nothing. Failing here instead hands the build log to BuildFailureAnalyzer.
        when(claudeToolClient.completeWithTools(anyString(), anyList(), anyList(), any()))
                .thenReturn(textResponse("빌드까지 완료했습니다."));
        when(dockerService.exec(eq(CONTAINER_ID), anyString())).thenAnswer(containerWith(
                "/workspace/app"  // package.json 이 함께 있는 소스 루트
        ));
        // Reaching the analyzer at all is the point: a missing build is now diagnosed from the
        // build log and retried, instead of being served as a finished preview.
        when(buildFailureAnalyzer.analyze(anyString())).thenReturn(new BuildFailureAnalyzer.Analysis(
                "프로젝트 빌드가 완료되지 않았습니다.", "로그 일부", "build를 다시 실행합니다."
        ));

        assertThatThrownBy(() -> execute(AiProvider.ANTHROPIC))
                .isInstanceOf(CodeAgentExecutionException.class)
                .hasMessageContaining("빌드가 완료되지 않았습니다");

        verify(dockerService, never()).exec(eq(CONTAINER_ID), contains("npx serve"));
    }

    @Test
    void stillServesAStaticProjectThatHasNoBuildStep() {
        // The index.html fallback exists for exactly this: a plain static site, whose index.html
        // has no package.json beside it, is legitimately its own output.
        when(claudeToolClient.completeWithTools(anyString(), anyList(), anyList(), any()))
                .thenReturn(textResponse("정적 페이지를 만들었습니다."));
        when(dockerService.exec(eq(CONTAINER_ID), anyString())).thenAnswer(containerWith(
                "/workspace/site"
        ));

        service.execute(step(), AiProvider.ANTHROPIC, 1L, null, TASK_ID);

        verify(dockerService).exec(eq(CONTAINER_ID), contains("npx serve -s /workspace/site"));
    }

    @Test
    void skipsTheSourceRootAndTakesTheBuildOutputWhenBothCarryAnIndexHtml() {
        // The realistic Vite layout once a build has run under a directory the known-name check
        // does not cover: both /workspace/web and /workspace/web/output hold an index.html, and
        // only the former has a package.json beside it.
        when(claudeToolClient.completeWithTools(anyString(), anyList(), anyList(), any()))
                .thenReturn(textResponse("빌드까지 완료했습니다."));
        when(dockerService.exec(eq(CONTAINER_ID), anyString())).thenAnswer(invocation -> {
            String command = invocation.getArgument(1);
            if (command.startsWith("find /workspace -name 'index.html'")) {
                return "/workspace/web/index.html\n/workspace/web/output/index.html\n";
            }
            if (command.contains("serve_ready")) {
                return "serve_ready=yes";
            }
            if (command.contains("/workspace/web/package.json")) {
                return "exists";
            }
            return "missing";
        });

        service.execute(step(), AiProvider.ANTHROPIC, 1L, null, TASK_ID);

        verify(dockerService).exec(eq(CONTAINER_ID), contains("npx serve -s /workspace/web/output"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Container whose only index.html sits in {@code indexHtmlDir}, and where a package.json
     * exists beside it only when that directory is a project root ({@code /workspace/app} in
     * these tests). Known output directories (dist/build/out) report as present only when
     * {@code indexHtmlDir} names one.
     */
    /**
     * 프리뷰 서버 준비 확인에만 다르게 답하고 나머지는 고정값을 돌려주는 스텁.
     * startPreviewServer 가 포트 응답을 기다린 뒤에야 반환하므로, 서빙과 무관한 테스트도
     * 준비됐다고 답해줘야 execute 가 끝까지 간다.
     */
    private org.mockito.stubbing.Answer<String> serveReadyOr(String defaultValue) {
        return invocation -> {
            String command = invocation.getArgument(1);
            return command.contains("serve_ready") ? "serve_ready=yes" : defaultValue;
        };
    }

    private org.mockito.stubbing.Answer<String> containerWith(String indexHtmlDir) {
        return invocation -> {
            String command = invocation.getArgument(1);
            // 프리뷰 서버 준비 확인. startPreviewServer 는 포트가 응답할 때까지 폴링한 뒤에만
            // 반환하므로, 여기서 준비됐다고 답하지 않으면 서빙 경로 테스트가 전부 실패한다.
            if (command.contains("serve_ready")) {
                return "serve_ready=yes";
            }
            if (command.startsWith("find /workspace -name 'index.html'")) {
                return indexHtmlDir + "/index.html\n";
            }
            if (command.startsWith("[ -d ")) {
                return command.contains("[ -d " + indexHtmlDir + " ]") ? "exists" : "missing";
            }
            if (command.startsWith("[ -f ")) {
                return command.contains("/workspace/app/package.json") ? "exists" : "missing";
            }
            return "";
        };
    }

    private CodeAgentService.CodeResult execute(AiProvider provider) {
        return service.execute(step(), provider, 1L, null, TASK_ID);
    }

    private AgentStep step() {
        return new AgentStep(AgentType.CODE, Map.of("instruction", "투두 앱을 만들어줘"));
    }

    private PreviewSessionInfo previewSession() {
        return new PreviewSessionInfo(
                "session-1", 1L, null, 21L, TASK_ID, CONTAINER_ID, 30001,
                "http://localhost:8080/api/v1/previews/session-1/token/", LocalDateTime.now().plusMinutes(30)
        );
    }

    private ToolCall toolCall(String id, String name, Map<String, Object> input) {
        return new ToolCall(id, name, input);
    }

    private LlmToolResponse toolResponse(String stopReason, ToolCall call) {
        Map<String, Object> block = Map.of(
                "type", "tool_use", "id", call.id(), "name", call.name(), "input", call.input()
        );
        return new LlmToolResponse(List.of(call), List.of(block), stopReason);
    }

    private LlmToolResponse openAiToolResponse(String finishReason, ToolCall call) {
        Map<String, Object> message = Map.of("role", "assistant", "tool_calls", List.of(Map.of("id", call.id())));
        return new LlmToolResponse(List.of(call), List.of(message), finishReason);
    }

    private LlmToolResponse textResponse(String text) {
        return new LlmToolResponse(
                List.of(), List.of(Map.of("type", "text", "text", text)), "end_turn"
        );
    }

    /**
     * Content of the tool_result the loop fed back for the tool call it made in round 1 — i.e.
     * what the model actually gets to see about that call.
     *
     * <p>Found by scanning rather than by call index: the loop appends to and re-sends a single
     * list instance, so every captured argument aliases the same (final) transcript.</p>
     */
    private String capturedToolResultContent() {
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(claudeToolClient, times(2))
                .completeWithTools(anyString(), captor.capture(), any(), any());
        List<Map<String, Object>> transcript = captor.getValue();
        for (Map<String, Object> message : transcript) {
            if (!(message.get("content") instanceof List<?> blocks)) {
                continue;
            }
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> map && "tool_result".equals(map.get("type"))) {
                    return (String) map.get("content");
                }
            }
        }
        throw new AssertionError("tool_result 메시지를 찾지 못했습니다: " + transcript);
    }
}
