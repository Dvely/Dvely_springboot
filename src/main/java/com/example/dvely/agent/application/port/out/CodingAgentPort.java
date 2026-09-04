package com.example.dvely.agent.application.port.out;

import com.example.dvely.agent.domain.value.AiProvider;

/**
 * Runs an <b>official</b> coding-agent CLI (Claude Code, Codex) headlessly against a workspace,
 * authenticated by the end user's own API key (BYOK).
 *
 * <p>Separate from {@link LlmToolPort}: that one drives our own tool loop over a chat-completions
 * endpoint, whereas an implementation of this port hands the whole agentic loop — planning, file
 * edits, tool use — to the vendor's own CLI and only collects the outcome. Keeping it behind its
 * own port is also what lets the CLI adapters live entirely in
 * {@code agent.infrastructure.codingagent} without touching the existing HTTP clients.</p>
 *
 * <p>Implementations must inject the key as a process environment variable only
 * ({@code ANTHROPIC_API_KEY} / {@code OPENAI_API_KEY}) — never write it to disk, a command line
 * (visible in {@code ps}), or a log — and must call only the vendors' official API endpoints. See
 * {@code docs/byok-coding-agent-design.md} for the compliance boundary this port exists to
 * honour.</p>
 */
public interface CodingAgentPort {

    /** The vendor whose credential and CLI this implementation drives. */
    AiProvider vendor();

    CodingAgentResult run(CodingAgentCommand command);
}
