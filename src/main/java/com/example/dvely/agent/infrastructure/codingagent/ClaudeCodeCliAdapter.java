package com.example.dvely.agent.infrastructure.codingagent;

import com.example.dvely.agent.application.port.out.CodingAgentCommand;
import com.example.dvely.agent.application.port.out.CodingAgentPort;
import com.example.dvely.agent.application.port.out.CodingAgentResult;
import com.example.dvely.agent.domain.value.AiProvider;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Drives Anthropic's official Claude Code CLI headlessly, authenticated by the end user's own
 * Anthropic API key (BYOK).
 *
 * <p>This is the sanctioned integration path: the CLI talks to the official API with a key the
 * user supplied and is billed for. It does not touch subscription credentials, browser sessions,
 * or unofficial endpoints — see {@code docs/byok-coding-agent-design.md} for why those are out of
 * scope permanently rather than merely unimplemented.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaudeCodeCliAdapter implements CodingAgentPort {

    /**
     * The env var the official CLI reads. Passing the key this way (rather than a {@code --key}
     * flag) also keeps it out of the container's process list, where any other process in the
     * container could read it from {@code /proc}.
     */
    private static final String API_KEY_ENV = "ANTHROPIC_API_KEY";

    private final CodingAgentContainerRunner runner;
    private final CodingAgentProperties properties;

    @Override
    public AiProvider vendor() {
        return AiProvider.ANTHROPIC;
    }

    @Override
    public CodingAgentResult run(CodingAgentCommand command) {
        // -p is Claude Code's print (non-interactive) mode: it runs the task and exits instead of
        // opening a session, which is the only shape that works with no TTY attached.
        List<String> argv = List.of("claude", "-p", command.prompt());
        List<String> env = List.of(API_KEY_ENV + "=" + command.apiKey());

        CodingAgentContainerRunner.ContainerRunOutcome outcome =
                provisionWithRetry(command, argv, env);

        if (outcome.timedOut()) {
            log.warn("Claude Code 실행 시간 초과: workspace={} timeout={}",
                    command.workspaceDir(), command.timeout());
            return CodingAgentResult.timedOut(outcome.stdout(), outcome.stderr());
        }
        if (outcome.exitCode() != 0) {
            log.warn("Claude Code 비정상 종료: workspace={} exitCode={}",
                    command.workspaceDir(), outcome.exitCode());
            return CodingAgentResult.failed(outcome.stdout(), outcome.stderr(), outcome.exitCode());
        }
        return CodingAgentResult.succeeded(outcome.stdout(), outcome.stderr());
    }

    /**
     * Retries only {@link CodingAgentProvisionException} — a failure that happened before the CLI
     * ran, so the workspace is untouched and a replay is safe. Anything thrown once the agent is
     * executing propagates: it may have already edited files, and running it again would stack a
     * second partial change on top of the first.
     */
    private CodingAgentContainerRunner.ContainerRunOutcome provisionWithRetry(
            CodingAgentCommand command, List<String> argv, List<String> env) {

        int attempts = Math.max(1, properties.getMaxProvisionAttempts());
        CodingAgentProvisionException last = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return runner.run(command.workspaceDir(), argv, env, command.timeout());
            } catch (CodingAgentProvisionException e) {
                last = e;
                log.warn("코딩 에이전트 컨테이너 준비 실패({}/{}): {}", attempt, attempts, e.getMessage());
                if (attempt < attempts) {
                    sleepBeforeRetry();
                }
            }
        }
        throw last;
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(properties.getProvisionRetryDelay().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("코딩 에이전트 재시도 대기가 인터럽트되었습니다.", e);
        }
    }
}
