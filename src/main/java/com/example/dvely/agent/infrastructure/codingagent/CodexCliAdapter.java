package com.example.dvely.agent.infrastructure.codingagent;

import com.example.dvely.agent.domain.value.AiProvider;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Drives OpenAI's official Codex CLI headlessly, authenticated by the end user's own OpenAI API
 * key (BYOK).
 *
 * <p>Deliberately API-key auth, not "Sign in with ChatGPT": OpenAI does not support routing a
 * consumer ChatGPT subscription through a third-party product, and the proxies that attempt it
 * break whenever the vendor changes its verification. The key path is the supported one and bills
 * the user directly.</p>
 */
@Component
public class CodexCliAdapter extends AbstractCliCodingAgentAdapter {

    private final CodingAgentProperties properties;

    public CodexCliAdapter(CodingAgentContainerRunner runner, CodingAgentProperties properties) {
        super(runner, properties);
        this.properties = properties;
    }

    /**
     * Codex runs on the OpenAI key — the same credential a direct OpenAI API call would use, which
     * is why the store is keyed by vendor rather than by execution mode.
     */
    @Override
    public AiProvider vendor() {
        return AiProvider.OPENAI;
    }

    @Override
    protected String apiKeyEnvName() {
        return "OPENAI_API_KEY";
    }

    @Override
    protected List<String> argvPrefix() {
        return properties.getCodex().getArgvPrefix();
    }

    @Override
    protected String cliName() {
        return "Codex";
    }
}
