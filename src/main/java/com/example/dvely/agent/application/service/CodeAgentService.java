package com.example.dvely.agent.application.service;

import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.exception.AgentIterationLimitException;
import com.example.dvely.agent.application.exception.CodeAgentExecutionException;
import com.example.dvely.agent.application.port.out.LlmToolResponse;
import com.example.dvely.agent.application.port.out.ToolCall;
import com.example.dvely.agent.application.port.out.ToolDefinition;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.config.AiProperties;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.llm.ClaudeToolClient;
import com.example.dvely.agent.infrastructure.llm.OpenAiToolClient;
import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.preview.application.result.PreviewSessionInfo;
import com.example.dvely.preview.application.service.PreviewSessionService;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
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
    // max_tokens and OpenAI's length. The final tool call of such a response can be cut off
    // mid-arguments, so it must not be executed (see truncatedToolCallIndex).
    private static final String CLAUDE_TRUNCATED_STOP_REASON = "max_tokens";
    private static final String OPENAI_TRUNCATED_STOP_REASON = "length";

    private static final String TRUNCATED_TOOL_CALL_MESSAGE =
            "[qeploy] 직전 tool 호출이 모델 출력 한도에 걸려 잘렸으므로 실행하지 않았습니다. "
                    + "파일을 더 작은 단위로 나눠 다시 호출하세요.";

    // How many recent tool calls to keep as the failure's log excerpt when the round budget runs out.
    private static final int PROGRESS_TRACE_TAIL = 12;

    private final ClaudeToolClient       claudeToolClient;
    private final OpenAiToolClient       openAiToolClient;
    private final DockerContainerService dockerService;
    private final PreviewSessionService  previewSessionService;
    private final UserRepository         userRepository;
    private final AuthCommandService     authCommandService;
    private final ProjectRepository      projectRepository;
    private final BuildFailureAnalyzer   buildFailureAnalyzer;
    private final AiProperties           aiProperties;

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
        String instruction = step.parameters().getOrDefault("instruction", "");
        log.info("[CodeAgent] 실행 시작 | userId={} provider={} projectId={} instruction={}", userId, provider, projectId, instruction);

        PreviewSessionInfo previewSession = previewSessionService.acquire(taskId);
        String containerId = previewSession.containerId();

        // 기존 프로젝트인 경우 GitHub 저장소를 컨테이너에 clone/pull
        if (projectId != null) {
            prepareProjectInContainer(containerId, userId, projectId);
        }

        try {
            String summary = (provider == AiProvider.OPENAI)
                    ? runOpenAiLoop(instruction, containerId)
                    : runClaudeLoop(instruction, containerId);

            startPreviewServer(containerId);

            String previewUrl = previewSession.publicUrl();
            log.info("[CodeAgent] 완료 | previewUrl={}", previewUrl);
            return new CodeResult(previewUrl, summary);
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
    private String runClaudeLoop(String instruction, String containerId) {
        int maxIterations = maxIterations();
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", instruction));
        List<String> trace = new ArrayList<>();

        for (int i = 0; i < maxIterations; i++) {
            log.info("[CodeAgent/Claude] LLM 호출 (round {}/{})", i + 1, maxIterations);
            LlmToolResponse response = claudeToolClient.completeWithTools(SYSTEM_PROMPT, messages, TOOLS);

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

    // ── OpenAI 루프 ──────────────────────────────────────────────────────────
    private String runOpenAiLoop(String instruction, String containerId) {
        int maxIterations = maxIterations();
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", instruction));
        List<String> trace = new ArrayList<>();

        for (int i = 0; i < maxIterations; i++) {
            log.info("[CodeAgent/OpenAI] LLM 호출 (round {}/{})", i + 1, maxIterations);
            LlmToolResponse response = openAiToolClient.completeWithTools(SYSTEM_PROMPT, messages, TOOLS);

            messages.add(response.contentBlocks().get(0));

            if (!response.hasToolCalls()) {
                log.info("[CodeAgent/OpenAI] 완료 신호 수신 (round {})", i + 1);
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
        log.warn("[CodeAgent/OpenAI] 최대 반복 횟수({}) 도달", maxIterations);
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

    // ── 기존 프로젝트 준비 (clone or pull) ──────────────────────────────────────
    private void prepareProjectInContainer(String containerId, Long userId, Long projectId) {
        Project project = projectRepository.findByIdAndOwnerUserId(projectId, userId)
                .orElseThrow(() -> new RuntimeException("프로젝트를 찾을 수 없거나 접근 권한이 없습니다: projectId=" + projectId));

        String sourceRepo = project.getSourceRepository();
        if (sourceRepo == null || sourceRepo.isBlank()) {
            log.warn("[CodeAgent] projectId={} 에 연결된 GitHub 저장소 없음, 신규 프로젝트로 진행", projectId);
            return;
        }

        // 유저 토큰 조회 (만료 시 갱신)
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("유저를 찾을 수 없습니다: " + userId));
        if (user.isUserAccessTokenExpired()) {
            authCommandService.refreshGithubUserToken(userId);
            user = userRepository.findById(userId).orElseThrow();
        }
        String userToken = user.getGithubUserAccessToken();
        String username  = user.getUsername();

        // 인증 포함 clone URL
        String cloneUrl = "https://" + username + ":" + userToken + "@github.com/" + sourceRepo + ".git";

        // git credential 파일 작성 (토큰 로그 노출 최소화)
        dockerService.exec(containerId, "apk add --no-cache git 2>/dev/null || true");
        String cred = "https://" + username + ":" + userToken + "@github.com";
        String credB64 = Base64.getEncoder().encodeToString(cred.getBytes(StandardCharsets.UTF_8));
        dockerService.exec(containerId,
                "node -e \"require('fs').writeFileSync('/tmp/.git-credentials', Buffer.from('" + credB64 + "', 'base64').toString('utf8'))\"");
        dockerService.exec(containerId, "git config --global credential.helper 'store --file /tmp/.git-credentials'");
        dockerService.exec(containerId, "git config --global user.email 'agent@qeploy.com'");
        dockerService.exec(containerId, "git config --global user.name 'Qeploy Agent'");

        String appExists = dockerService.exec(containerId, "[ -d /workspace/app/.git ] && echo yes || echo no").trim();

        if ("yes".equals(appExists)) {
            // 이미 clone됨 → pull로 최신화
            String currentRemote = dockerService.exec(containerId,
                    "git -C /workspace/app remote get-url origin 2>/dev/null || echo __none__").trim();
            if (!currentRemote.contains(sourceRepo)) {
                // 다른 repo → 삭제 후 재clone
                dockerService.exec(containerId, "rm -rf /workspace/app");
                dockerService.exec(containerId, "git clone " + cloneUrl + " /workspace/app");
                log.info("[CodeAgent] 다른 repo 감지, 재clone: {}", sourceRepo);
            } else {
                dockerService.exec(containerId, "cd /workspace/app && git fetch origin");
                log.info("[CodeAgent] 기존 repo fetch: {}", sourceRepo);
            }
        } else {
            // 처음 clone
            dockerService.exec(containerId, "mkdir -p /workspace");
            dockerService.exec(containerId, "git clone " + cloneUrl + " /workspace/app");
            log.info("[CodeAgent] 저장소 clone 완료: {}", sourceRepo);
        }

        dockerService.exec(containerId,
                "cd /workspace/app && git fetch origin preview 2>/dev/null || true");
        dockerService.exec(containerId,
                "cd /workspace/app && "
                        + "(git show-ref --verify --quiet refs/remotes/origin/preview "
                        + "&& git checkout -B preview origin/preview || git checkout -B preview)");

        // clone 후 의존성 설치
        String pkgJson = dockerService.exec(containerId, "[ -f /workspace/app/package.json ] && echo yes || echo no").trim();
        if ("yes".equals(pkgJson)) {
            log.info("[CodeAgent] npm install 실행");
            dockerService.exec(containerId, "cd /workspace/app && npm install");
        }
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

    // ── Preview 서버 (loop 종료 후 서버가 직접 실행) ───────────────────────────
    private void startPreviewServer(String containerId) {
        String buildDir = detectBuildOutputDir(containerId);
        dockerService.exec(containerId, "pkill -f 'npx serve' 2>/dev/null || true");
        dockerService.exec(containerId,
                "nohup npx serve -s " + buildDir + " -l 3000 > /tmp/serve.log 2>&1 &");
        String serveLog = dockerService.exec(containerId, "sleep 3 && cat /tmp/serve.log");
        log.info("[CodeAgent] 프리뷰 서버 시작 | buildDir={} | log={}", buildDir, serveLog);
    }

    /**
     * Resolves the directory to serve as the preview.
     *
     * <p>The known output directories are checked first; the index.html search below them exists
     * for projects that have no build step at all (a plain static site), and for build output
     * under a directory this list does not name. That search used to accept the first index.html
     * it found anywhere under /workspace, which for a Vite or CRA project is the <em>source</em>
     * entry point — so a run whose build never happened or failed still resolved to a directory,
     * started a preview over unbuilt sources, and reported success. That was the second half of
     * the frontend's report: a task marked complete whose preview does not work.</p>
     *
     * <p>What separates the two is a sibling package.json: a project root has one next to its
     * index.html, a build output directory does not. Skipping those roots means a missing build
     * now reaches the caller as a failure, where the build log drives {@link BuildFailureAnalyzer}
     * and the retry — which is what should have happened all along.</p>
     */
    private String detectBuildOutputDir(String containerId) {
        for (String candidate : List.of(
                "/workspace/app/dist",
                "/workspace/app/build",
                "/workspace/app/out")) {
            String result = dockerService.exec(containerId,
                    "[ -d " + candidate + " ] && echo exists || echo missing");
            if ("exists".equals(result.trim())) {
                log.info("[CodeAgent] 빌드 결과물 감지: {}", candidate);
                return candidate;
            }
        }
        for (String candidate : findIndexHtmlDirectories(containerId)) {
            if (isProjectSourceRoot(containerId, candidate)) {
                log.info("[CodeAgent] 프로젝트 소스 루트이므로 빌드 결과물에서 제외: {}", candidate);
                continue;
            }
            log.info("[CodeAgent] index.html 기반 빌드 경로 감지: {}", candidate);
            return candidate;
        }
        throw new IllegalStateException(
                "빌드 결과 디렉터리를 찾지 못했습니다. build가 실행되지 않았거나 실패했습니다.");
    }

    /**
     * Directories holding an index.html, nearest-first. {@code public/} is excluded because CRA
     * keeps its source template there; node_modules because a dependency's own index.html is never
     * this project's output. More than one candidate is read so that a skipped source root does not
     * exhaust the search — a Vite project has its source index.html and its dist/index.html both.
     */
    private List<String> findIndexHtmlDirectories(String containerId) {
        String found = dockerService.exec(containerId,
                "find /workspace -name 'index.html' -not -path '*/node_modules/*' "
                        + "-not -path '*/public/*' 2>/dev/null | head -5");
        return found.lines()
                .map(String::trim)
                .filter(line -> line.endsWith("/index.html"))
                .map(line -> line.substring(0, line.lastIndexOf('/')))
                .distinct()
                .toList();
    }

    private boolean isProjectSourceRoot(String containerId, String directory) {
        String result = dockerService.exec(containerId,
                "[ -f " + directory + "/package.json ] && echo exists || echo missing");
        return "exists".equals(result.trim());
    }

    private void logToolCallResult(ToolCall tc, String result) {
        String truncated = result.length() > 300 ? result.substring(0, 300) + "..." : result;
        log.info("[CodeAgent] tool_call={} input={} → result={}", tc.name(), tc.input(), truncated);
    }
}
