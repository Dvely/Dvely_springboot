package com.example.dvely.agent.application.port.out;

import java.time.Duration;
import java.util.Objects;

/**
 * One headless run of an official coding-agent CLI (Claude Code, Codex).
 *
 * <p><b>Why {@code toString()} is overridden:</b> records generate a {@code toString()} that prints
 * every component, which would put {@link #apiKey} — a user's real credential — into any log line
 * that formats this object. The override below redacts it. The accompanying test pins that
 * behaviour so a later "let's use the default record toString" cleanup cannot silently undo it.</p>
 *
 * @param prompt       what the agent is asked to do
 * @param workspaceDir absolute path of the checkout the CLI runs against, inside the container
 * @param apiKey       the end user's own vendor key (BYOK), injected as an env var by the adapter
 * @param timeout      hard wall-clock bound; the adapter kills the process when it elapses
 */
public record CodingAgentCommand(
        String prompt,
        String workspaceDir,
        String apiKey,
        Duration timeout
) {

    public CodingAgentCommand {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt는 비어 있을 수 없습니다.");
        }
        if (workspaceDir == null || workspaceDir.isBlank()) {
            throw new IllegalArgumentException("workspaceDir는 비어 있을 수 없습니다.");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey는 비어 있을 수 없습니다.");
        }
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout은 0보다 커야 합니다.");
        }
    }

    @Override
    public String toString() {
        return "CodingAgentCommand[workspaceDir=%s, promptLength=%d, apiKey=***, timeout=%s]"
                .formatted(workspaceDir, prompt.length(), timeout);
    }
}
