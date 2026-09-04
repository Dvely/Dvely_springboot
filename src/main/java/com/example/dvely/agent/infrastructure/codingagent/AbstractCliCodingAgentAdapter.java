package com.example.dvely.agent.infrastructure.codingagent;

import com.example.dvely.agent.application.port.out.CodingAgentCommand;
import com.example.dvely.agent.application.port.out.CodingAgentPort;
import com.example.dvely.agent.application.port.out.CodingAgentResult;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Shared machinery for running a vendor's official CLI headlessly with the end user's own key.
 *
 * <p>Claude Code and Codex differ only in three things — which executable and subcommand to invoke,
 * which environment variable carries the key, and which vendor's credential to look up. Everything
 * that actually needs care (retry that cannot replay a half-applied edit, keeping the key out of
 * argv, mapping a timeout to its own outcome) is identical, so it lives here once instead of being
 * copied per vendor where the two copies would drift.</p>
 */
@Slf4j
abstract class AbstractCliCodingAgentAdapter implements CodingAgentPort {

    private final CodingAgentContainerRunner runner;
    private final CodingAgentProperties properties;

    protected AbstractCliCodingAgentAdapter(CodingAgentContainerRunner runner,
                                            CodingAgentProperties properties) {
        this.runner = runner;
        this.properties = properties;
    }

    /** The env var name the vendor's CLI reads its key from. */
    protected abstract String apiKeyEnvName();

    /**
     * Executable plus non-interactive subcommand/flags, without the prompt. Configurable rather
     * than hard-coded because it is the one part of this integration owned by an external tool:
     * if a CLI release renames its non-interactive mode, an operator can correct it alongside the
     * image version pin instead of waiting for a code change.
     */
    protected abstract List<String> argvPrefix();

    /** Human-readable CLI name, used only in log lines. */
    protected abstract String cliName();

    @Override
    public final CodingAgentResult run(CodingAgentCommand command) {
        List<String> argv = new ArrayList<>(argvPrefix());
        // The prompt is always the final, separate argv element — never concatenated into a flag
        // and never passed through a shell, so its content cannot become syntax.
        argv.add(command.prompt());

        // The key travels only here, in the exec environment. Putting it on the command line would
        // expose it through /proc to anything else running in the container.
        List<String> env = List.of(apiKeyEnvName() + "=" + command.apiKey());

        CodingAgentContainerRunner.ContainerRunOutcome outcome =
                runWithProvisionRetry(command, List.copyOf(argv), env);

        if (outcome.timedOut()) {
            log.warn("{} 실행 시간 초과: workspace={} timeout={}",
                    cliName(), command.workspaceDir(), command.timeout());
            return CodingAgentResult.timedOut(outcome.stdout(), outcome.stderr());
        }
        if (outcome.exitCode() != 0) {
            log.warn("{} 비정상 종료: workspace={} exitCode={}",
                    cliName(), command.workspaceDir(), outcome.exitCode());
            return CodingAgentResult.failed(outcome.stdout(), outcome.stderr(), outcome.exitCode());
        }
        return CodingAgentResult.succeeded(outcome.stdout(), outcome.stderr());
    }

    /**
     * Retries only {@link CodingAgentProvisionException} — a failure raised before the CLI ran, so
     * the workspace is untouched and a replay is safe. Anything thrown once the agent is executing
     * propagates: it may already have edited files, and a second run would stack a partial change
     * on top of the first.
     */
    private CodingAgentContainerRunner.ContainerRunOutcome runWithProvisionRetry(
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
