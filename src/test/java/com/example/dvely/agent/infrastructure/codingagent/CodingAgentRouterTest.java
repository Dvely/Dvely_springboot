package com.example.dvely.agent.infrastructure.codingagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.port.out.CodingAgentPort;
import com.example.dvely.agent.domain.value.AiProvider;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodingAgentRouterTest {

    private static CodingAgentPort portFor(AiProvider vendor) {
        CodingAgentPort port = mock(CodingAgentPort.class);
        when(port.vendor()).thenReturn(vendor);
        return port;
    }

    @Test
    void routesEachCodingAgentToTheAdapterForItsVendor() {
        CodingAgentPort claude = portFor(AiProvider.ANTHROPIC);
        CodingAgentPort codex = portFor(AiProvider.OPENAI);
        CodingAgentRouter router = new CodingAgentRouter(List.of(claude, codex));

        assertThat(router.route(AiProvider.CLAUDE_CODE)).isSameAs(claude);
        assertThat(router.route(AiProvider.CODEX)).isSameAs(codex);
    }

    @Test
    void rejectsAChatCompletionProvider() {
        CodingAgentRouter router = new CodingAgentRouter(List.of(portFor(AiProvider.ANTHROPIC)));

        // ANTHROPIC means "the chat-completions client", which this router does not serve — a
        // silent match on vendor would route a plain chat request into a container run.
        assertThatThrownBy(() -> router.route(AiProvider.ANTHROPIC))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failsClearlyWhenNoAdapterIsRegisteredForTheVendor() {
        CodingAgentRouter router = new CodingAgentRouter(List.of(portFor(AiProvider.ANTHROPIC)));

        assertThatThrownBy(() -> router.route(AiProvider.CODEX))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CODEX");
    }
}
