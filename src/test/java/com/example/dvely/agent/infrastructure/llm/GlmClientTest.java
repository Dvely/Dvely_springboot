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
    void switchesToZaisThinkingSpellingWhenPointedAtZai() {
        // 두 게이트웨이는 서로의 표기를 거절하지 않고 조용히 무시한다. 그래서 방언을 잘못
        // 고르면 요청은 통과하는데 사고 깊이만 사라진다 — 배포가 base-url 로 한 번 선언한
        // 게이트웨이를 여기서도 따라간다.
        AiProperties properties = new AiProperties();
        properties.getGlm().setBaseUrl("https://api.z.ai/api/paas/v4/chat/completions");

        assertThat(GlmClient.endpoint(properties).reasoning())
                .isEqualTo(LlmRequestOptions.ReasoningStyle.ZAI_THINKING);
    }

    @Test
    void matchesZaiByHostRatherThanBySubstring() {
        // "notz.ai" 를 Z.ai 로 오인하면 엉뚱한 게이트웨이에 Z.ai 방언을 보낸다.
        assertThat(GlmClient.reasoningStyle("https://notz.ai/v1/chat/completions"))
                .isEqualTo(LlmRequestOptions.ReasoningStyle.OPENROUTER_REASONING);
        assertThat(GlmClient.reasoningStyle("https://z.ai/v1/chat/completions"))
                .isEqualTo(LlmRequestOptions.ReasoningStyle.ZAI_THINKING);
        assertThat(GlmClient.reasoningStyle("https://open.bigmodel.z.ai/v1/chat/completions"))
                .isEqualTo(LlmRequestOptions.ReasoningStyle.ZAI_THINKING);
    }

    @Test
    void fallsBackToTheDefaultDialectWhenTheEndpointIsMalformed() {
        assertThat(GlmClient.reasoningStyle("not-a-url"))
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
