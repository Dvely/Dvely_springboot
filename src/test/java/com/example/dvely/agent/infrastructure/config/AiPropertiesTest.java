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
                .withProperty("OPENAI_API_KEY", "common-openai-key");
        addProfileProperties(environment, "application-dev.yml");

        AiProperties properties = Binder.get(environment)
                .bind("qeploy.ai", Bindable.of(AiProperties.class))
                .orElseThrow(() -> new IllegalStateException("qeploy.ai 설정 바인딩 실패"));

        assertThat(properties.getAnthropic().getApiKey()).isEqualTo("common-anthropic-key");
        assertThat(properties.getOpenai().getApiKey()).isEqualTo("common-openai-key");
    }

    @Test
    void prefersQeployEnvironmentVariablesOverCommonValues() throws IOException {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("QEPLOY_AI_ANTHROPIC_API_KEY", "qeploy-anthropic-key")
                .withProperty("ANTHROPIC_API_KEY", "common-anthropic-key")
                .withProperty("QEPLOY_AI_OPENAI_API_KEY", "qeploy-openai-key")
                .withProperty("OPENAI_API_KEY", "common-openai-key");
        addProfileProperties(environment, "application-prod.yml");

        AiProperties properties = Binder.get(environment)
                .bind("qeploy.ai", Bindable.of(AiProperties.class))
                .orElseThrow(() -> new IllegalStateException("qeploy.ai 설정 바인딩 실패"));

        assertThat(properties.getAnthropic().getApiKey()).isEqualTo("qeploy-anthropic-key");
        assertThat(properties.getOpenai().getApiKey()).isEqualTo("qeploy-openai-key");
    }

    private void addProfileProperties(MockEnvironment environment, String resourceName) throws IOException {
        new YamlPropertySourceLoader()
                .load(resourceName, new ClassPathResource(resourceName))
                .forEach(environment.getPropertySources()::addLast);
    }
}
