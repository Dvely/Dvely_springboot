package com.example.dvely.agent.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.agent.infrastructure.config.AiProperties;
import org.junit.jupiter.api.Test;

/**
 * Covers the part of the GLM client that is not the shared OpenAI wire format: where the call goes,
 * which key signs it, and which optional OpenRouter headers ride along.
 */
class GlmClientTest {

    @Test
    void postsToTheConfiguredEndpointWithTheGlmKeyAndModel() {
        AiProperties properties = new AiProperties();
        properties.getGlm().setApiKey("sk-or-key");

        OpenAiCompatibleChat.Endpoint endpoint = GlmClient.endpoint(properties);

        assertThat(endpoint.url()).isEqualTo("https://openrouter.ai/api/v1/chat/completions");
        assertThat(endpoint.config().getApiKey()).isEqualTo("sk-or-key");
        assertThat(endpoint.config().getModel()).isEqualTo("z-ai/glm-4.6");
    }

    @Test
    void followsTheConfiguredBaseUrlSoTheProviderCanBeRepointed() {
        AiProperties properties = new AiProperties();
        properties.getGlm().setBaseUrl("https://gateway.internal/v1/chat/completions");

        assertThat(GlmClient.endpoint(properties).url())
                .isEqualTo("https://gateway.internal/v1/chat/completions");
    }

    @Test
    void usesOpenRoutersReasoningParameterRatherThanOpenAisReasoningEffort() {
        assertThat(GlmClient.endpoint(new AiProperties()).reasoning())
                .isEqualTo(LlmRequestOptions.ReasoningStyle.OPENROUTER_REASONING);
    }

    @Test
    void sendsNoAttributionHeadersUntilTheDeploymentConfiguresThem() {
        // They are optional at OpenRouter, and an empty HTTP-Referer is worse than none.
        assertThat(GlmClient.endpoint(new AiProperties()).extraHeaders()).isEmpty();
    }

    @Test
    void sendsTheConfiguredAttributionHeaders() {
        AiProperties properties = new AiProperties();
        properties.getGlm().setReferer("https://qeploy.com");
        properties.getGlm().setTitle("Qeploy");

        assertThat(GlmClient.endpoint(properties).extraHeaders())
                .containsEntry("HTTP-Referer", "https://qeploy.com")
                .containsEntry("X-Title", "Qeploy");
    }

    @Test
    void namesOpenRouterInErrorsBecauseThatIsWhereAKeyOrBalanceIsFixed() {
        assertThat(GlmClient.PROVIDER_NAME).contains("OpenRouter");
    }
}
