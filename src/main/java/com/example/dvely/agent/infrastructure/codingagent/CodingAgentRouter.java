package com.example.dvely.agent.infrastructure.codingagent;

import com.example.dvely.agent.application.port.out.CodingAgentPort;
import com.example.dvely.agent.domain.value.AiProvider;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Picks the CLI adapter for a coding-agent provider.
 *
 * <p>Built from the injected {@link CodingAgentPort} beans rather than a hard-coded switch, so
 * adding a third vendor's CLI is one new {@code @Component} and nothing else. The lookup goes
 * through {@link AiProvider#credentialVendor()}, which is also what the credential store is keyed
 * by — so the provider a request names, the adapter that runs it, and the key it authenticates
 * with can never disagree.</p>
 */
@Component
public class CodingAgentRouter {

    private final List<CodingAgentPort> ports;

    public CodingAgentRouter(List<CodingAgentPort> ports) {
        this.ports = List.copyOf(ports);
    }

    public CodingAgentPort route(AiProvider provider) {
        if (!provider.isCodingAgent()) {
            throw new IllegalArgumentException("코딩 에이전트 제공자가 아닙니다: " + provider);
        }
        AiProvider vendor = provider.credentialVendor();
        return ports.stream()
                .filter(port -> port.vendor() == vendor)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "코딩 에이전트 어댑터를 찾을 수 없습니다: " + provider + " (vendor=" + vendor + ")"));
    }
}
