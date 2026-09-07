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

    /**
     * Codex needs a login step, not an environment variable.
     *
     * <p>Measured against codex-cli 0.153.2: running {@code codex exec} with {@code OPENAI_API_KEY}
     * set fails with "401 Missing bearer or basic authentication in header" — the CLI simply does
     * not read that variable. {@code codex login --with-api-key} takes the key on stdin and stores
     * it for the session ({@code codex login status} then reports "Logged in using an API key"),
     * after which {@code codex exec} authenticates normally.</p>
     */
    @Override
    protected CodingAgentContainerRunner.Credential credentialing(String apiKey) {
        return viaLoginCommand(properties.getCodex().getLoginArgv(), apiKey);
    }

    @Override
    protected List<String> argvPrefix() {
        return properties.getCodex().getArgvPrefix();
    }

    @Override
    protected String model() {
        return properties.getCodex().getModel();
    }

    @Override
    protected String cliName() {
        return "Codex";
    }
}
