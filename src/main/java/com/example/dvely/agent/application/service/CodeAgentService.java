package com.example.dvely.agent.application.service;

import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.exception.AgentIterationLimitException;
import com.example.dvely.agent.application.exception.CodeAgentExecutionException;
import com.example.dvely.agent.application.port.out.LlmToolPort;
import com.example.dvely.agent.application.port.out.LlmToolResponse;
import com.example.dvely.agent.application.port.out.ToolCall;
import com.example.dvely.agent.application.port.out.ToolDefinition;
import com.example.dvely.agent.domain.value.AiModelOptions;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.config.AiProperties;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.llm.ClaudeToolClient;
import com.example.dvely.agent.infrastructure.llm.GlmToolClient;
import com.example.dvely.agent.infrastructure.llm.OpenAiToolClient;
import com.example.dvely.common.exception.LlmProviderException;
import com.example.dvely.preview.application.result.PreviewSessionInfo;
import com.example.dvely.preview.application.service.PreviewRuntimeLauncher;
import com.example.dvely.preview.application.service.PreviewServeException;
import com.example.dvely.preview.application.service.PreviewSessionService;
import com.example.dvely.preview.application.service.PreviewWorkspaceService;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CodeAgentService {

    // A single container command's output — `npm install`, `npm create vite`, a failing build —
    // routinely runs to tens of thousands of characters, and every tool result stays in `messages`
    // and is re-sent on every subsequent round. Left untrimmed, the transcript grows fast enough
    // that a run spends its whole round budget re-reading its own logs: latency and cost climb per
    // round, and the model loses the earlier instructions it needs to actually finish. Head and
    // tail are what carry signal (the command that ran, and the error it ended on), so the middle
    // is what gets dropped. The untruncated output still reaches BuildFailureAnalyzer — that reads
    // /tmp/qeploy-build.log from the container, not this transcript.
    private static final int MAX_TOOL_RESULT_CHARS  = 8_000;
    private static final int TOOL_RESULT_HEAD_CHARS = 2_000;

    // Stop reasons meaning "the model ran out of output budget mid-generation" — Anthropic's
    // max_tokens, and the "length" that every provider speaking the OpenAI chat-completions format
    // reports (OpenAI itself, and GLM through OpenRouter). The final tool call of such a response
    // can be cut off mid-arguments, so it must not be executed (see truncatedToolCallIndex).
    private static final String CLAUDE_TRUNCATED_STOP_REASON = "max_tokens";
    private static final String OPENAI_TRUNCATED_STOP_REASON = "length";

    private static final String TRUNCATED_TOOL_CALL_MESSAGE =
            "[qeploy] 직전 tool 호출이 모델 출력 한도에 걸려 잘렸으므로 실행하지 않았습니다. "
                    + "파일을 더 작은 단위로 나눠 다시 호출하세요.";

    // How many recent tool calls to keep as the failure's log excerpt when the round budget runs out.
    private static final int PROGRESS_TRACE_TAIL = 12;

    private final ClaudeToolClient        claudeToolClient;
    private final OpenAiToolClient        openAiToolClient;
    private final GlmToolClient           glmToolClient;
    private final DockerContainerService  dockerService;
    private final PreviewSessionService   previewSessionService;
    private final PreviewRuntimeLauncher   previewRuntimeLauncher;
    private final PreviewWorkspaceService previewWorkspaceService;
    private final BuildFailureAnalyzer    buildFailureAnalyzer;
    private final AiProperties            aiProperties;

    private static final String SYSTEM_PROMPT = """
            You are an expert full-stack developer working inside a Docker container (node:20-alpine).
            Your job is to fully implement what the user requests inside /workspace.

            ## Workflow — New Project
            1. Check /workspace first (execute_command: ls /workspace).
            2. Scaffold only if no project exists yet:
               - Vite + React (preferred): npm create vite@latest app -- --template react
               - CRA:                      npx create-react-app app
               - Next.js:                  npx create-next-app@latest app --no-git
               - Vue:                      npm create vue@latest app
            3. !! IMPLEMENT THE REQUESTED FEATURE — THIS IS MANDATORY !!
               - Read the scaffolded source files first (read_file src/App.jsx etc.).
               - Rewrite or create ALL necessary source files to implement the feature.
               - Do NOT stop here — the scaffold is just a blank slate, not the result.
            4. Install any additional dependencies if needed.
            5. Build ONLY after implementation is complete:
               cd /workspace/app && npm run build

            ## Workflow — Modifying Existing Project
            1. ls /workspace to find the project directory.
            2. Read relevant source files to understand the structure.
            3. Implement the requested changes completely.
            4. Rebuild: cd /workspace/<project> && npm run build

            ## Rules
            - STOP after the build completes. Do NOT start a preview server — it is handled externally.
            - CRITICAL: scaffold → implement feature → build. Never build before implementing.
            - If a command fails, read the error and fix it before continuing.
            - Each execute_command runs independently; chain with: cd /path && command
            - When the build succeeds, respond with TEXT ONLY (no tool calls) containing:
              1. What was created or modified (project name, framework, key files changed)
              2. Result: success or any issues encountered
              3. Suggestions for improvement or next steps
            """;

    private static final List<ToolDefinition> TOOLS = List.of(
            new ToolDefinition(
                    "execute_command",
                    "Execute a shell command inside the Docker container. Returns stdout and stderr.",
                    Map.of(
                            "type",       "object",
                            "properties", Map.of(
                                    "command", Map.of("type", "string", "description", "Shell command to run")
                            ),
                            "required", List.of("command")
                    )
            ),
            new ToolDefinition(
                    "write_file",
                    "Write content to a file inside the Docker container.",
                    Map.of(
                            "type",       "object",
                            "properties", Map.of(
                                    "path",    Map.of("type", "string", "description", "Absolute file path"),
                                    "content", Map.of("type", "string", "description", "File content")
                            ),
                            "required", List.of("path", "content")
                    )
            ),
            new ToolDefinition(
                    "read_file",
                    "Read the content of a file inside the Docker container.",
                    Map.of(
                            "type",       "object",
                            "properties", Map.of(
                                    "path", Map.of("type", "string", "description", "Absolute file path")
                            ),
                            "required", List.of("path")
                    )
            )
    );

    public CodeResult execute(AgentStep step,
                              AiProvider provider,
                              Long userId,
                              Long projectId,
                              String taskId) {
        return execute(step, provider, userId, projectId, taskId, AiModelOptions.defaults());
    }

    public CodeResult execute(AgentStep step,
                              AiProvider provider,
                              Long userId,
                              Long projectId,
                              String taskId,
                              AiModelOptions modelOptions) {
        String instruction = step.parameters().getOrDefault("instruction", "");
        log.info("[CodeAgent] 실행 시작 | userId={} provider={} projectId={} instruction={}", userId, provider, projectId, instruction);

        PreviewSessionInfo previewSession = previewSessionService.acquire(taskId);
        String containerId = previewSession.containerId();

        // 기존 프로젝트인 경우 GitHub 저장소를 컨테이너에 clone/pull
        if (projectId != null) {
            previewWorkspaceService.prepareProject(containerId, userId, projectId);
        }

        try {
            // GLM shares OpenAI's loop rather than getting its own: OpenRouter returns
            // OpenAI-shaped tool_calls, so the transcript built here is identical down to the
            // message keys. A second copy would only be a copy that has to stay in step.
            String summary = switch (provider) {
                case ANTHROPIC -> runClaudeLoop(instruction, containerId, modelOptions);
                case OPENAI -> runOpenAiCompatibleLoop(
                        openAiToolClient, "OpenAI", instruction, containerId, modelOptions);
                case GLM -> runOpenAiCompatibleLoop(
                        glmToolClient, "GLM", instruction, containerId, modelOptions);
                // Not wired into this step yet, and the gap is structural rather than missing code:
                // this loop drives tools inside an already-running preview container, while a
                // coding agent brings its own container and works on a bind-mounted host checkout.
                // Reconciling those two workspace models is its own unit; until then a CODE step
                // must refuse the provider rather than silently run a different engine.
                case CLAUDE_CODE, CODEX -> throw new IllegalArgumentException(
                        "코딩 에이전트 제공자는 아직 CODE 스텝에 배선되지 않았습니다: " + provider);
            };

            // 세션은 PROVISIONING 으로 만들어져 있다. 서버가 실제로 뜬 뒤에만 ACTIVE 로 올려야
            // FE 가 "열면 보인다"는 계약대로 iframe 을 붙일 수 있다. 실패하면 PROVISIONING 인 채
            // 두지 않고 사유와 함께 FAILED 로 닫는다 — 안 그러면 FE 가 준비 중 화면을 무한히 돈다.
            try {
                // 에이전트가 만든 코드도 런타임 타입에 맞춰 서빙한다(정적/NODE_SERVER/JAVA).
                previewRuntimeLauncher.launch(projectId, containerId);
            } catch (RuntimeException exception) {
                // 사유는 FE 가 사용자에게 그대로 보여주므로 내부 예외 문구를 넣지 않는다.
                // 원인은 아래 PreviewServeFailedException 의 cause 로 전파되며 로그에 남는다.
                previewSessionService.markServeFailed(
                        taskId,
                        "빌드는 끝났지만 프리뷰 서버를 시작하지 못했습니다. 다시 시도해주세요.");
                // 그대로 다시 던진다. 서버가 안 뜬/안 응답한 런타임 실패는 PreviewServeException
                // 이라 아래 전용 catch 가 빌드실패 분석·재시도를 건너뛴다. 빌드 산출물 자체가 없는
                // 실패는 IllegalStateException 이라 아래 catch(Exception) 의 빌드실패 경로로 간다
                // (그건 LLM 이 빌드를 고칠 여지가 있어 재시도가 맞다).
                throw exception;
            }
            previewSessionService.markServing(taskId);

            String previewUrl = previewSession.publicUrl();
            log.info("[CodeAgent] 완료 | previewUrl={}", previewUrl);
            return new CodeResult(previewUrl, summary);
        } catch (LlmProviderException e) {
            // Not a build failure, so it must not go through the branch below: an exhausted credit
            // balance analyzed as a build log produces "프로젝트 빌드가 완료되지 않았습니다" — a
            // verdict on the user's project for a problem in the provider account — and then burns
            // the task's retry budget on a call that cannot start succeeding between attempts.
            log.error("[CodeAgent] AI 제공자 호출 실패 | userId={} provider={} reason={}",
                    userId, e.providerName(), e.reason());
            throw e;
        } catch (AgentIterationLimitException e) {
            // Deliberately not routed through BuildFailureAnalyzer like the branch below: nothing
            // here says the *build* failed — the run simply did not reach the end of its work — so
            // analyzing a build log would attach a diagnosis the run never produced. The
            // suggestedFix matters beyond its wording: AgentPlanExecutor#withSuggestedFix appends
            // it to the instruction on retry, and the retry reuses this same container, so it is
            // what turns the retry into "continue" instead of "start over".
            log.warn("[CodeAgent] 반복 한도 도달로 미완료 | userId={} containerId={} maxIterations={}",
                    userId, containerId, e.maxIterations(), e);
            throw new CodeAgentExecutionException(
                    "요청한 작업이 한 번의 실행 안에 끝나지 않았습니다(LLM 반복 " + e.maxIterations() + "회 한도 도달).",
                    e.progressLog(),
                    "이미 생성된 파일은 그대로 두고 아직 끝내지 못한 작업만 이어서 진행한 뒤, 마지막에 build를 실행합니다.",
                    e
            );
        } catch (PreviewServeException e) {
            // 서버 시작/응답 실패는 빌드 실패가 아니다(코드 재생성으로 안 고쳐진다) — 빌드실패
            // 분석/재시도로 흘려보내지 않고 그대로 올려, AgentPlanExecutor 가 태스크를 한 번에
            // 실패로 닫게 한다(재시도로 예산을 태우지 않는다).
            throw e;
        } catch (Exception e) {
            log.error("[CodeAgent] 실패 | userId={} containerId={}", userId, containerId, e);
            String buildLog = dockerService.exec(
                    containerId,
                    "tail -n 80 /tmp/qeploy-build.log 2>/dev/null || true"
            );
            BuildFailureAnalyzer.Analysis analysis = buildFailureAnalyzer.analyze(
                    buildLog + "\n" + (e.getMessage() == null ? "" : e.getMessage())
            );
            throw new CodeAgentExecutionException(
                    analysis.userMessage(),
                    analysis.logExcerpt(),
                    analysis.suggestedFix(),
                    e
            );
        }
    }

    public record CodeResult(String previewUrl, String summary) {}

    // ── Claude 루프 ──────────────────────────────────────────────────────────
    private String runClaudeLoop(String instruction, String containerId, AiModelOptions modelOptions) {
        int maxIterations = maxIterations();
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", instruction));
        List<String> trace = new ArrayList<>();

        for (int i = 0; i < maxIterations; i++) {
            log.info("[CodeAgent/Claude] LLM 호출 (round {}/{})", i + 1, maxIterations);
            LlmToolResponse response = claudeToolClient.completeWithTools(SYSTEM_PROMPT, messages, TOOLS, modelOptions);

            messages.add(Map.of("role", "assistant", "content", response.contentBlocks()));

            if (!response.hasToolCalls()) {
                log.info("[CodeAgent/Claude] 완료 신호 수신 (round {})", i + 1);
                return extractFinalText(response.contentBlocks());
            }

            List<ToolCall> toolCalls = response.toolCalls();
            int truncatedIndex = truncatedToolCallIndex(response, CLAUDE_TRUNCATED_STOP_REASON);
            List<Map<String, Object>> toolResults = new ArrayList<>();
            for (int t = 0; t < toolCalls.size(); t++) {
                ToolCall tc = toolCalls.get(t);
                // Every tool_use block still needs a matching tool_result or the next request is
                // rejected, so a truncated call is answered with an explanation rather than skipped.
                String result = t == truncatedIndex
                        ? TRUNCATED_TOOL_CALL_MESSAGE
                        : truncateForModel(executeTool(tc, containerId));
                logToolCallResult(tc, result);
                trace.add(traceEntry(i + 1, tc));
                toolResults.add(Map.of(
                        "type",        "tool_result",
                        "tool_use_id", tc.id(),
                        "content",     result
                ));
            }
            messages.add(Map.of("role", "user", "content", toolResults));
        }
        log.warn("[CodeAgent/Claude] 최대 반복 횟수({}) 도달", maxIterations);
        throw new AgentIterationLimitException(maxIterations, recentTrace(trace));
    }

    // ── OpenAI 호환 루프 (OpenAI, OpenRouter 경유 GLM) ────────────────────────
    private String runOpenAiCompatibleLoop(LlmToolPort toolClient,
                                           String providerLabel,
                                           String instruction,
                                           String containerId,
                                           AiModelOptions modelOptions) {
        int maxIterations = maxIterations();
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", instruction));
        List<String> trace = new ArrayList<>();

        for (int i = 0; i < maxIterations; i++) {
            log.info("[CodeAgent/{}] LLM 호출 (round {}/{})", providerLabel, i + 1, maxIterations);
            LlmToolResponse response = toolClient.completeWithTools(SYSTEM_PROMPT, messages, TOOLS, modelOptions);

            messages.add(response.contentBlocks().get(0));

            if (!response.hasToolCalls()) {
                log.info("[CodeAgent/{}] 완료 신호 수신 (round {})", providerLabel, i + 1);
                return (String) response.contentBlocks().get(0).getOrDefault("content", "");
            }

            List<ToolCall> toolCalls = response.toolCalls();
            int truncatedIndex = truncatedToolCallIndex(response, OPENAI_TRUNCATED_STOP_REASON);
            for (int t = 0; t < toolCalls.size(); t++) {
                ToolCall tc = toolCalls.get(t);
                String result = t == truncatedIndex
                        ? TRUNCATED_TOOL_CALL_MESSAGE
                        : truncateForModel(executeTool(tc, containerId));
                logToolCallResult(tc, result);
                trace.add(traceEntry(i + 1, tc));
                messages.add(Map.of(
                        "role",         "tool",
                        "tool_call_id", tc.id(),
                        "content",      result
                ));
            }
        }
        log.warn("[CodeAgent/{}] 최대 반복 횟수({}) 도달", providerLabel, maxIterations);
        throw new AgentIterationLimitException(maxIterations, recentTrace(trace));
    }

    private int maxIterations() {
        return aiProperties.getCodeAgent().getMaxIterations();
    }

    /**
     * Index of the one tool call in this response that may have been cut off mid-generation, or -1
     * if none was. Only the last block can be partial: everything before it was already emitted in
     * full before the output budget ran out. Executing a truncated call is worse than not running
     * it — a {@code write_file} whose content stopped halfway writes a broken source file that the
     * model then has to discover and repair, spending rounds on damage this loop caused.
     */
    private int truncatedToolCallIndex(LlmToolResponse response, String truncatedStopReason) {
        return truncatedStopReason.equals(response.stopReason())
                ? response.toolCalls().size() - 1
                : -1;
    }

    private String truncateForModel(String result) {
        if (result == null) {
            return "";
        }
        if (result.length() <= MAX_TOOL_RESULT_CHARS) {
            return result;
        }
        int omitted = result.length() - MAX_TOOL_RESULT_CHARS;
        return result.substring(0, TOOL_RESULT_HEAD_CHARS)
                + "\n...[qeploy: 출력 " + result.length() + "자 중 가운데 " + omitted + "자 생략]...\n"
                + result.substring(result.length() - (MAX_TOOL_RESULT_CHARS - TOOL_RESULT_HEAD_CHARS));
    }

    private String traceEntry(int round, ToolCall tc) {
        String input = String.valueOf(tc.input());
        return "round " + round + ": " + tc.name() + " "
                + (input.length() > 120 ? input.substring(0, 120) + "..." : input);
    }

    private String recentTrace(List<String> trace) {
        if (trace.isEmpty()) {
            return "실행된 tool 호출이 없습니다.";
        }
        List<String> recent = trace.size() <= PROGRESS_TRACE_TAIL
                ? trace
                : trace.subList(trace.size() - PROGRESS_TRACE_TAIL, trace.size());
        return String.join("\n", recent);
    }

    @SuppressWarnings("unchecked")
    private String extractFinalText(List<Map<String, Object>> contentBlocks) {
        return contentBlocks.stream()
                .filter(b -> "text".equals(b.get("type")))
                .map(b -> (String) b.get("text"))
                .findFirst()
                .orElse("");
    }

    // ── Tool 실행 ─────────────────────────────────────────────────────────────
    /**
     * Arguments are validated before use rather than cast straight out of the map: a tool call can
     * arrive with a missing or non-string argument (a model mistake, or a call the provider parsed
     * out of a response that stopped mid-arguments). Casting blindly turned that into an NPE that
     * propagated out of the loop and failed the whole task with an unrelated message, when the
     * recoverable answer is to tell the model what was missing and let it call again.
     */
    private String executeTool(ToolCall tc, String containerId) {
        Map<String, Object> input = tc.input() == null ? Map.of() : tc.input();
        return switch (tc.name()) {
            case "execute_command" -> {
                String command = stringArg(input, "command");
                yield command == null
                        ? missingArgument("execute_command", "command")
                        : executeCommand(containerId, command);
            }
            case "write_file" -> {
                String path = stringArg(input, "path");
                // An empty file is a legitimate write, so content is only checked for presence.
                Object content = input.get("content");
                yield path == null || !(content instanceof String text)
                        ? missingArgument("write_file", "path 또는 content")
                        : writeFile(containerId, path, text);
            }
            case "read_file" -> {
                String path = stringArg(input, "path");
                yield path == null
                        ? missingArgument("read_file", "path")
                        : dockerService.exec(containerId, "cat " + path);
            }
            default -> "알 수 없는 tool: " + tc.name();
        };
    }

    private String stringArg(Map<String, Object> input, String key) {
        return input.get(key) instanceof String value && !value.isBlank() ? value : null;
    }

    private String missingArgument(String toolName, String argumentName) {
        return "[qeploy] " + toolName + " 호출에 " + argumentName
                + " 인자가 없어 실행하지 못했습니다. 인자를 채워 다시 호출하세요.";
    }

    private String executeCommand(String containerId, String command) {
        if (isBuildCommand(command)) {
            return dockerService.exec(
                    containerId,
                    "set -o pipefail; (" + command + ") 2>&1 | tee /tmp/qeploy-build.log"
            );
        }
        return dockerService.exec(containerId, command);
    }

    private boolean isBuildCommand(String command) {
        String normalized = command.toLowerCase();
        return normalized.contains("npm run build")
                || normalized.contains("pnpm build")
                || normalized.contains("yarn build")
                || normalized.contains("bun run build");
    }

    private String writeFile(String containerId, String path, String content) {
        try {
            String base64 = Base64.getEncoder().encodeToString(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "/";
            dockerService.exec(containerId, "mkdir -p '" + dir + "'");
            dockerService.exec(containerId,
                    "node -e \"require('fs').writeFileSync('" + path + "', Buffer.from('" + base64 + "', 'base64').toString('utf8'))\"");
            return "파일 작성 완료: " + path;
        } catch (Exception e) {
            return "파일 작성 실패: " + e.getMessage();
        }
    }

    private void logToolCallResult(ToolCall tc, String result) {
        String truncated = result.length() > 300 ? result.substring(0, 300) + "..." : result;
        log.info("[CodeAgent] tool_call={} input={} → result={}", tc.name(), tc.input(), truncated);
    }
}
