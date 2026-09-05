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

    private Egress egress = new Egress();

    /**
     * Network containment for the agent.
     *
     * <p>Needed because bypassing Codex's own sandbox (see {@link #codex}) gives up the thing that
     * sandbox was mostly there for: control over what the model's shell commands may reach. Without
     * this, an agent container sits on the default bridge with unrestricted egress, so anything the
     * agent runs — and Codex reads {@code AGENTS.md} out of the repository itself, so a hostile
     * repo is effectively an instruction source — could reach the host gateway's services or post
     * the user's key to an arbitrary address.</p>
     *
     * <p>The shape is an <b>internal</b> Docker network (no default route at all) plus a small
     * proxy sidecar that is the only member with a way out, filtering CONNECT by hostname. Measured
     * against a real daemon: the allowed API answers, while a non-allowlisted host, a
     * proxy-bypassing direct connection, and the host gateway all fail.</p>
     */
    @Getter
    @Setter
    public static class Egress {

        /** Turning this off puts the agent back on the default bridge with unrestricted egress. */
        private boolean enabled = true;

        private String networkName = "qeploy-coding-agent";

        /** DNS alias the agent reaches the proxy by; constant, so the proxy URL is constant too. */
        private String proxyAlias = "qeploy-egress";

        private int proxyPort = 8888;

        /**
         * Hostnames the agent may reach. Matched whole, not as substrings — an entry becomes an
         * anchored regex with its dots escaped, so {@code api.openai.com} cannot also permit
         * {@code api.openai.com.evil.test}.
         */
        private List<String> allowedHosts = List.of("api.anthropic.com", "api.openai.com");

        public String proxyUrl() {
            return "http://" + proxyAlias + ":" + proxyPort;
        }
    }

    /** {@code -p} is Claude Code's non-interactive print mode; the key comes from the environment. */
    private Cli claude = Cli.of("claude", "-p");

    /**
     * {@code codex exec} is Codex's non-interactive mode, preceded by a login step because the CLI
     * does not read {@code OPENAI_API_KEY}.
     *
     * <p>{@code --skip-git-repo-check} is deliberately <b>not</b> in the default: Codex refuses to
     * work outside a git repository so that its edits stay recoverable, and Qeploy's workspace is a
     * clone, so the check passes on its own and is worth keeping. A deployment that needs to run
     * against a non-repo directory can add the flag here.</p>
     */
    private Cli codex = Cli.withLogin(
            // --dangerously-bypass-approvals-and-sandbox is required, not a shortcut. Codex's own
            // sandbox runs on bubblewrap, which needs user namespaces; our container drops every
            // capability and sets no-new-privileges, so bubblewrap cannot start and every shell
            // tool call the agent makes dies with exit 1 — measured: the agent reported success
            // while creating no file at all. The flag's own documentation names this case
            // ("Intended solely for running in environments that are externally sandboxed"), and
            // that is exactly what the throwaway container is: no published port, capped memory
            // and pids, and nothing mounted but the one workspace. With the flag the same prompt
            // actually writes the file.
            List.of("codex", "exec", "--dangerously-bypass-approvals-and-sandbox"),
            List.of("codex", "login", "--with-api-key"),
            // The CLI would otherwise default to gpt-5.6-sol; the cheapest tier finishes the same
            // work, so paying for the largest one by default is a cost with nothing behind it.
            "gpt-5.6-luna");

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

        /**
         * Model to run, appended as {@code --model <value>}; blank leaves the CLI's own default.
         *
         * <p>Its own setting rather than something buried in {@link #argvPrefix} because this is
         * the cost knob an operator actually turns, and both CLIs happen to accept the same
         * {@code --model} flag. Measured on a trivial prompt: {@code gpt-5.6-luna} used 9,692
         * tokens where the CLI default {@code gpt-5.6-sol} used 11,203.</p>
         */
        private String model = "";

        /**
         * Login command run before the agent, receiving the key on stdin. Empty when the CLI takes
         * its key from the environment instead (Claude Code does; Codex does not).
         */
        private List<String> loginArgv = List.of();

        static Cli of(String... prefix) {
            Cli cli = new Cli();
            cli.setArgvPrefix(List.of(prefix));
            return cli;
        }

        static Cli withLogin(List<String> prefix, List<String> login, String model) {
            Cli cli = new Cli();
            cli.setArgvPrefix(prefix);
            cli.setLoginArgv(login);
            cli.setModel(model);
            return cli;
        }
    }
}
