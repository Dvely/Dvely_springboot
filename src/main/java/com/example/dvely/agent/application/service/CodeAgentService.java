package com.example.dvely.agent.application.service;

import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.port.out.LlmToolResponse;
import com.example.dvely.agent.application.port.out.ToolCall;
import com.example.dvely.agent.application.port.out.ToolDefinition;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.docker.UserContainerInfo;
import com.example.dvely.agent.infrastructure.docker.UserContainerRegistry;
import com.example.dvely.agent.infrastructure.llm.ClaudeToolClient;
import com.example.dvely.agent.infrastructure.llm.OpenAiToolClient;
import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
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

    private static final int MAX_ITERATIONS = 20;

    private final ClaudeToolClient       claudeToolClient;
    private final OpenAiToolClient       openAiToolClient;
    private final DockerContainerService dockerService;
    private final UserContainerRegistry  containerRegistry;
    private final UserRepository         userRepository;
    private final AuthCommandService     authCommandService;
    private final ProjectRepository      projectRepository;

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

    public CodeResult execute(AgentStep step, AiProvider provider, Long userId, Long projectId) {
        String instruction = step.parameters().getOrDefault("instruction", "");
        log.info("[CodeAgent] 실행 시작 | userId={} provider={} projectId={} instruction={}", userId, provider, projectId, instruction);

        boolean           isNew      = containerRegistry.find(userId).isEmpty();
        String            containerId;
        int               hostPort;

        if (isNew) {
            containerId = dockerService.createAndStartContainer(userId);
            hostPort    = dockerService.getMappedPort(containerId);
            containerRegistry.register(userId, new UserContainerInfo(containerId, hostPort, java.time.Instant.now()));
            log.info("[CodeAgent] 신규 컨테이너 생성 | userId={} containerId={}", userId, containerId);
        } else {
            UserContainerInfo info = containerRegistry.find(userId).get();
            containerId = info.containerId();
            hostPort    = info.previewPort();
            containerRegistry.touch(userId);
            log.info("[CodeAgent] 기존 컨테이너 재사용 | userId={} containerId={}", userId, containerId);
        }

        // 기존 프로젝트인 경우 GitHub 저장소를 컨테이너에 clone/pull
        if (projectId != null) {
            prepareProjectInContainer(containerId, userId, projectId);
        }

        try {
            String summary = (provider == AiProvider.OPENAI)
                    ? runOpenAiLoop(instruction, containerId)
                    : runClaudeLoop(instruction, containerId);

            startPreviewServer(containerId);

            String previewUrl = "http://localhost:" + hostPort;
            log.info("[CodeAgent] 완료 | previewUrl={}", previewUrl);
            return new CodeResult(previewUrl, summary);
        } catch (Exception e) {
            log.error("[CodeAgent] 실패 | userId={} containerId={}", userId, containerId, e);
            throw new RuntimeException("코드 에이전트 실행 실패: " + e.getMessage(), e);
        }
    }

    public record CodeResult(String previewUrl, String summary) {}

    // ── Claude 루프 ──────────────────────────────────────────────────────────
    private String runClaudeLoop(String instruction, String containerId) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", instruction));

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            log.info("[CodeAgent/Claude] LLM 호출 (round {})", i + 1);
            LlmToolResponse response = claudeToolClient.completeWithTools(SYSTEM_PROMPT, messages, TOOLS);

            messages.add(Map.of("role", "assistant", "content", response.contentBlocks()));

            if (!response.hasToolCalls()) {
                log.info("[CodeAgent/Claude] 완료 신호 수신 (round {})", i + 1);
                return extractFinalText(response.contentBlocks());
            }

            List<Map<String, Object>> toolResults = new ArrayList<>();
            for (ToolCall tc : response.toolCalls()) {
                String result = executeTool(tc, containerId);
                logToolCallResult(tc, result);
                toolResults.add(Map.of(
                        "type",        "tool_result",
                        "tool_use_id", tc.id(),
                        "content",     result
                ));
            }
            messages.add(Map.of("role", "user", "content", toolResults));
        }
        log.warn("[CodeAgent/Claude] 최대 반복 횟수({}) 도달", MAX_ITERATIONS);
        return "최대 반복 횟수 도달로 작업이 종료되었습니다.";
    }

    // ── OpenAI 루프 ──────────────────────────────────────────────────────────
    private String runOpenAiLoop(String instruction, String containerId) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", instruction));

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            log.info("[CodeAgent/OpenAI] LLM 호출 (round {})", i + 1);
            LlmToolResponse response = openAiToolClient.completeWithTools(SYSTEM_PROMPT, messages, TOOLS);

            messages.add(response.contentBlocks().get(0));

            if (!response.hasToolCalls()) {
                log.info("[CodeAgent/OpenAI] 완료 신호 수신 (round {})", i + 1);
                return (String) response.contentBlocks().get(0).getOrDefault("content", "");
            }

            for (ToolCall tc : response.toolCalls()) {
                String result = executeTool(tc, containerId);
                logToolCallResult(tc, result);
                messages.add(Map.of(
                        "role",         "tool",
                        "tool_call_id", tc.id(),
                        "content",      result
                ));
            }
        }
        log.warn("[CodeAgent/OpenAI] 최대 반복 횟수({}) 도달", MAX_ITERATIONS);
        return "최대 반복 횟수 도달로 작업이 종료되었습니다.";
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
    private String executeTool(ToolCall tc, String containerId) {
        return switch (tc.name()) {
            case "execute_command" -> dockerService.exec(containerId, (String) tc.input().get("command"));
            case "write_file"      -> writeFile(containerId,
                                            (String) tc.input().get("path"),
                                            (String) tc.input().get("content"));
            case "read_file"       -> dockerService.exec(containerId, "cat " + tc.input().get("path"));
            default                -> "알 수 없는 tool: " + tc.name();
        };
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
        dockerService.exec(containerId, "git config --global user.email 'agent@dvely.app'");
        dockerService.exec(containerId, "git config --global user.name 'Dvely Agent'");

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
                dockerService.exec(containerId, "cd /workspace/app && git pull origin HEAD --rebase");
                log.info("[CodeAgent] 기존 repo pull: {}", sourceRepo);
            }
        } else {
            // 처음 clone
            dockerService.exec(containerId, "mkdir -p /workspace");
            dockerService.exec(containerId, "git clone " + cloneUrl + " /workspace/app");
            log.info("[CodeAgent] 저장소 clone 완료: {}", sourceRepo);
        }

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
        // 폴백: index.html 위치로 추론
        String found = dockerService.exec(containerId,
                "find /workspace -name 'index.html' -not -path '*/node_modules/*' -not -path '*/public/*' 2>/dev/null | head -1");
        if (!found.isBlank()) {
            String dir = found.trim().replace("/index.html", "");
            log.info("[CodeAgent] index.html 기반 빌드 경로 감지: {}", dir);
            return dir;
        }
        log.warn("[CodeAgent] 빌드 결과물 감지 실패, 기본값 사용: /workspace/app/dist");
        return "/workspace/app/dist";
    }

    private void logToolCallResult(ToolCall tc, String result) {
        String truncated = result.length() > 300 ? result.substring(0, 300) + "..." : result;
        log.info("[CodeAgent] tool_call={} input={} → result={}", tc.name(), tc.input(), truncated);
    }
}
