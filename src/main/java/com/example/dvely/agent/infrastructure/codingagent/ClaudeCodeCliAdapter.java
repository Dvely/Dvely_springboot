package com.example.dvely.agent.infrastructure.codingagent;

import com.example.dvely.agent.domain.value.AiProvider;
import java.util.List;
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
@Component
public class ClaudeCodeCliAdapter extends AbstractCliCodingAgentAdapter {

    private final CodingAgentProperties properties;

    public ClaudeCodeCliAdapter(CodingAgentContainerRunner runner, CodingAgentProperties properties) {
        super(runner, properties);
        this.properties = properties;
    }

    /**
     * Claude Code runs on the Anthropic key, so the credential store is asked for the vendor
     * rather than for an execution mode — one Anthropic key serves both this and direct API calls.
     */
    @Override
    public AiProvider vendor() {
        return AiProvider.ANTHROPIC;
    }

    /**
     * Claude Code reads its key straight from the environment — verified against 2.1.260, where
     * {@code claude -p} with {@code ANTHROPIC_API_KEY} set reaches the API and returns a real
     * account-level answer. No login step is needed.
     */
    @Override
    protected Credentialing credentialing(String apiKey) {
        return Credentialing.viaEnvironment("ANTHROPIC_API_KEY", apiKey);
    }

    @Override
    protected List<String> argvPrefix() {
        return properties.getClaude().getArgvPrefix();
    }

    @Override
    protected String model() {
        return properties.getClaude().getModel();
    }

    @Override
    protected String cliName() {
        return "Claude Code";
    }
}
