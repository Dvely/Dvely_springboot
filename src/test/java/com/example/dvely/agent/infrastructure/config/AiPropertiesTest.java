package com.example.dvely.agent.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

class AiPropertiesTest {

    @Test
    void usesQeployConfigurationPrefix() {
        ConfigurationProperties annotation = AiProperties.class.getAnnotation(ConfigurationProperties.class);

        assertThat(annotation.prefix()).isEqualTo("qeploy.ai");
    }

    @Test
    void bindsCommonApiKeyEnvironmentVariablesAsFallback() throws IOException {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("ANTHROPIC_API_KEY", "common-anthropic-key")
                .withProperty("OPENAI_API_KEY", "common-openai-key")
                .withProperty("OPENROUTER_API_KEY", "common-openrouter-key");
        addProfileProperties(environment, "application-dev.yml");

        AiProperties properties = Binder.get(environment)
                .bind("qeploy.ai", Bindable.of(AiProperties.class))
                .orElseThrow(() -> new IllegalStateException("qeploy.ai 설정 바인딩 실패"));

        assertThat(properties.getAnthropic().getApiKey()).isEqualTo("common-anthropic-key");
        assertThat(properties.getOpenai().getApiKey()).isEqualTo("common-openai-key");
        // GLM is reached through OpenRouter, so the key it falls back to is OpenRouter's, not
        // one named after the model vendor.
        assertThat(properties.getGlm().getApiKey()).isEqualTo("common-openrouter-key");
    }

    @Test
    void prefersQeployEnvironmentVariablesOverCommonValues() throws IOException {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("QEPLOY_AI_ANTHROPIC_API_KEY", "qeploy-anthropic-key")
                .withProperty("ANTHROPIC_API_KEY", "common-anthropic-key")
                .withProperty("QEPLOY_AI_OPENAI_API_KEY", "qeploy-openai-key")
                .withProperty("OPENAI_API_KEY", "common-openai-key")
                .withProperty("QEPLOY_AI_GLM_API_KEY", "qeploy-glm-key")
                .withProperty("OPENROUTER_API_KEY", "common-openrouter-key");
        addProfileProperties(environment, "application-prod.yml");

        AiProperties properties = Binder.get(environment)
                .bind("qeploy.ai", Bindable.of(AiProperties.class))
                .orElseThrow(() -> new IllegalStateException("qeploy.ai 설정 바인딩 실패"));

        assertThat(properties.getAnthropic().getApiKey()).isEqualTo("qeploy-anthropic-key");
        assertThat(properties.getOpenai().getApiKey()).isEqualTo("qeploy-openai-key");
        assertThat(properties.getGlm().getApiKey()).isEqualTo("qeploy-glm-key");
    }

    @Test
    void bindsTheGlmEndpointSoADeploymentCanPointItSomewhereOtherThanOpenRouter() throws IOException {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("QEPLOY_AI_GLM_BASE_URL", "https://api.z.ai/api/paas/v4/chat/completions")
                .withProperty("QEPLOY_AI_GLM_MODEL", "glm-4.6");
        addProfileProperties(environment, "application-prod.yml");

        AiProperties properties = Binder.get(environment)
                .bind("qeploy.ai", Bindable.of(AiProperties.class))
                .orElseThrow(() -> new IllegalStateException("qeploy.ai 설정 바인딩 실패"));

        assertThat(properties.getGlm().getBaseUrl())
                .isEqualTo("https://api.z.ai/api/paas/v4/chat/completions");
        assertThat(properties.getGlm().getModel()).isEqualTo("glm-4.6");
    }

    @Test
    void defaultsGlmToOpenRouter() throws IOException {
        MockEnvironment environment = new MockEnvironment();
        addProfileProperties(environment, "application-prod.yml");

        AiProperties properties = Binder.get(environment)
                .bind("qeploy.ai", Bindable.of(AiProperties.class))
                .orElseThrow(() -> new IllegalStateException("qeploy.ai 설정 바인딩 실패"));

        assertThat(properties.getGlm().getBaseUrl())
                .isEqualTo("https://openrouter.ai/api/v1/chat/completions");
        assertThat(properties.getGlm().getModel()).isEqualTo("z-ai/glm-4.6");
    }

    @Test
    void bindsTheRetryEnvironmentVariablesDocumentedForOperators() throws IOException {
        // 이 이름들은 deploy/ecosystem.config.js.example 에 그대로 적혀 있다. yml 에 명시적
        // 플레이스홀더가 없으면 운영자가 넣은 값이 조용히 무시된다.
        MockEnvironment environment = new MockEnvironment()
                .withProperty("QEPLOY_AI_RETRY_MAX_ATTEMPTS", "5")
                .withProperty("QEPLOY_AI_RETRY_INITIAL_DELAY_MS", "250")
                .withProperty("QEPLOY_AI_RETRY_MAX_DELAY_MS", "4000");
        addProfileProperties(environment, "application-prod.yml");

        AiProperties properties = Binder.get(environment)
                .bind("qeploy.ai", Bindable.of(AiProperties.class))
                .orElseThrow(() -> new IllegalStateException("qeploy.ai 설정 바인딩 실패"));

        assertThat(properties.getRetry().getMaxAttempts()).isEqualTo(5);
        assertThat(properties.getRetry().getInitialDelayMs()).isEqualTo(250);
        assertThat(properties.getRetry().getMaxDelayMs()).isEqualTo(4000);
    }

    @Test
    void defaultsRetryToTheBoundedPolicy() throws IOException {
        MockEnvironment environment = new MockEnvironment();
        addProfileProperties(environment, "application-prod.yml");

        AiProperties properties = Binder.get(environment)
                .bind("qeploy.ai", Bindable.of(AiProperties.class))
                .orElseThrow(() -> new IllegalStateException("qeploy.ai 설정 바인딩 실패"));

        assertThat(properties.getRetry().getMaxAttempts()).isEqualTo(3);
        assertThat(properties.getRetry().getInitialDelayMs()).isEqualTo(1000);
        assertThat(properties.getRetry().getMaxDelayMs()).isEqualTo(8000);
    }

    private void addProfileProperties(MockEnvironment environment, String resourceName) throws IOException {
        new YamlPropertySourceLoader()
                .load(resourceName, new ClassPathResource(resourceName))
                .forEach(environment.getPropertySources()::addLast);
    }
}
