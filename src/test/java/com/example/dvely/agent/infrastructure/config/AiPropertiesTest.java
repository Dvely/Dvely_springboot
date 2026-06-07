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
    void bindsLegacyEnvironmentVariablesAsFallback() throws IOException {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DVELY_AI_ANTHROPIC_API_KEY", "legacy-anthropic-key")
                .withProperty("DVELY_AI_OPENAI_API_KEY", "legacy-openai-key");
        addProfileProperties(environment, "application-dev.yml");

        AiProperties properties = Binder.get(environment)
                .bind("qeploy.ai", Bindable.of(AiProperties.class))
                .orElseThrow(() -> new IllegalStateException("qeploy.ai 설정 바인딩 실패"));

        assertThat(properties.getAnthropic().getApiKey()).isEqualTo("legacy-anthropic-key");
        assertThat(properties.getOpenai().getApiKey()).isEqualTo("legacy-openai-key");
    }

    @Test
    void prefersQeployEnvironmentVariablesOverLegacyValues() throws IOException {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("QEPLOY_AI_ANTHROPIC_API_KEY", "qeploy-anthropic-key")
                .withProperty("DVELY_AI_ANTHROPIC_API_KEY", "legacy-anthropic-key")
                .withProperty("QEPLOY_AI_OPENAI_API_KEY", "qeploy-openai-key")
                .withProperty("DVELY_AI_OPENAI_API_KEY", "legacy-openai-key");
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
