package com.example.dvely.agent.infrastructure.codingagent;

import java.time.Duration;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the BYOK coding-agent execution path.
 *
 * <p>Deliberately a separate properties class from {@code AiProperties} rather than another nested
 * block inside it: {@code AiProperties} is actively edited by the provider/HTTP-client work, and
 * keeping this surface in its own file is what lets the coding-agent unit land without touching a
 * shared hot file. The {@code coding-agent} prefix is also far enough from
 * {@code qeploy.ai.code-agent} (the CODE step's LLM round budget) to not be mistaken for it.</p>
 */
@ConfigurationProperties(prefix = "qeploy.coding-agent")
@Getter
@Setter
public class CodingAgentProperties {

    /**
     * Locally built image (see {@code docker/coding-agent/Dockerfile}). It is never pulled from a
     * registry — the runner checks for it and fails with a clear message when it is missing,
     * because silently pulling a same-named public image would run unknown code against a user's
     * real API key.
     */
    private String image = "qeploy/coding-agent:local";

    /** Where the host checkout is bind-mounted inside the container. */
    private String workspaceMountPath = "/workspace";

    /** Hard wall-clock bound for one CLI run; the container is killed when it elapses. */
    private Duration timeout = Duration.ofMinutes(10);

    /** Memory (and swap) cap. Matches the JAVA_FULLSTACK preview ceiling — agent runs build too. */
    private long memoryBytes = 2L << 30;

    private long nanoCpus = 1_000_000_000L;

    private long pidsLimit = 512L;

    /**
     * How many times to retry <b>provisioning</b> (image check, container create/start) before
     * giving up. Deliberately does not cover a run that already reached the CLI: once the agent
     * has started it may have edited files in the workspace, so a blind retry would replay a
     * half-applied change. Failures after that point are surfaced, not retried.
     */
    private int maxProvisionAttempts = 3;

    private Duration provisionRetryDelay = Duration.ofSeconds(2);

    private Cli claude = Cli.of("claude", "-p");

    private Cli codex = Cli.of("codex", "exec");

    /**
     * How one vendor's CLI is invoked non-interactively.
     *
     * <p>Configurable because this is the part of the integration owned by an external tool. The
     * image pins a CLI version ({@code docker/coding-agent/Dockerfile}); if a later release renames
     * its non-interactive mode, the pin and this prefix move together — without a code change.</p>
     */
    @Getter
    @Setter
    public static class Cli {

        /** Executable plus subcommand/flags. The prompt is appended as the final argument. */
        private List<String> argvPrefix = List.of();

        static Cli of(String... prefix) {
            Cli cli = new Cli();
            cli.setArgvPrefix(List.of(prefix));
            return cli;
        }
    }
}
