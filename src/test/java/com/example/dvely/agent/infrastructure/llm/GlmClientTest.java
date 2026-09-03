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
    void namesTheGatewayInErrorsBecauseThatIsWhereAKeyOrBalanceIsFixed() {
        assertThat(GlmClient.endpoint(new AiProperties()).providerName())
                .isEqualTo("GLM(openrouter.ai)");
    }

    @Test
    void followsTheEndpointWhenNamingTheGateway() {
        // A fixed "GLM(OpenRouter)" sends the operator to the wrong dashboard the moment base-url
        // is repointed — 2026-09-03 실측으로 Z.ai 잔액 부족이 OpenRouter 이름으로 보고됐다.
        AiProperties properties = new AiProperties();
        properties.getGlm().setBaseUrl("https://api.z.ai/api/paas/v4/chat/completions");

        assertThat(GlmClient.endpoint(properties).providerName()).isEqualTo("GLM(api.z.ai)");
    }

    @Test
    void fallsBackToAPlainNameWhenTheEndpointHasNoHost() {
        assertThat(GlmClient.providerName("not-a-url")).isEqualTo("GLM");
    }
}
