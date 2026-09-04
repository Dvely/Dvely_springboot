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

    /**
     * How this vendor's CLI is handed the user's key.
     *
     * <p>Not one shared mechanism, because the two CLIs genuinely differ (verified against
     * Claude Code 2.1.260 and codex-cli 0.153.2): Claude Code reads {@code ANTHROPIC_API_KEY} from
     * the environment, while Codex ignores {@code OPENAI_API_KEY} and requires a
     * {@code codex login --with-api-key} step that takes the key on stdin.</p>
     */
    protected abstract Credentialing credentialing(String apiKey);

    /**
     * @param env      {@code KEY=VALUE} entries for the agent's own exec
     * @param authStep a login command to run first, or {@code null} when {@code env} is enough
     */
    protected record Credentialing(List<String> env, CodingAgentContainerRunner.AuthStep authStep) {

        static Credentialing viaEnvironment(String name, String apiKey) {
            return new Credentialing(List.of(name + "=" + apiKey), null);
        }

        static Credentialing viaLoginCommand(List<String> argv, String apiKey) {
            return new Credentialing(List.of(), new CodingAgentContainerRunner.AuthStep(argv, apiKey));
        }
    }

    /**
     * Executable plus non-interactive subcommand/flags, without the prompt. Configurable rather
     * than hard-coded because it is the one part of this integration owned by an external tool:
     * if a CLI release renames its non-interactive mode, an operator can correct it alongside the
     * image version pin instead of waiting for a code change.
     */
    protected abstract List<String> argvPrefix();

    /** Model to run, or blank to leave the CLI's own default. */
    protected abstract String model();

    /** Human-readable CLI name, used only in log lines. */
    protected abstract String cliName();

    @Override
    public final CodingAgentResult run(CodingAgentCommand command) {
        List<String> argv = new ArrayList<>(argvPrefix());
        // Both CLIs accept --model; blank means "leave the CLI's own default alone".
        if (!model().isBlank()) {
            argv.add("--model");
            argv.add(model());
        }
        // The prompt is always the final, separate argv element — never concatenated into a flag
        // and never passed through a shell, so its content cannot become syntax.
        argv.add(command.prompt());

        // The key travels only through the environment or a login step's stdin — never on the
        // command line, where anything else in the container could read it from /proc.
        Credentialing credentialing = credentialing(command.apiKey());

        CodingAgentContainerRunner.ContainerRunOutcome outcome =
                runWithProvisionRetry(command, List.copyOf(argv), credentialing);

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
            CodingAgentCommand command, List<String> argv, Credentialing credentialing) {

        int attempts = Math.max(1, properties.getMaxProvisionAttempts());
        CodingAgentProvisionException last = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return runner.run(command.workspaceDir(), credentialing.authStep(), argv,
                        credentialing.env(), command.timeout());
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
