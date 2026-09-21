package com.example.dvely.agent.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    // The two tests that stood here bound QEPLOY_AI_*_API_KEY / ANTHROPIC_API_KEY / OPENAI_API_KEY /
    // OPENROUTER_API_KEY onto Provider.apiKey. That property is gone on purpose (#364): the
    // deployment holds no vendor key, every call runs on the calling user's own. What is left to
    // prove is the model catalogue, and that no key can be bound even when the environment offers one.

    @ParameterizedTest
    @ValueSource(strings = {"application-dev.yml", "application-prod.yml"})
    void bindsTheModelCatalogueOfEveryProviderFromTheEnvironment(String profile) throws IOException {
        // Which models a request may name, and which of them think, is the whole of what the
        // deployment still decides per provider — it is a cost decision, so it must be operable
        // through the documented QEPLOY_AI_* variables and not silently fall back to defaults.
        MockEnvironment environment = new MockEnvironment()
                .withProperty("QEPLOY_AI_ANTHROPIC_MODEL", "claude-sonnet-x")
                .withProperty("QEPLOY_AI_ANTHROPIC_ALLOWED_MODELS", "claude-haiku-x,claude-opus-x")
                .withProperty("QEPLOY_AI_ANTHROPIC_THINKING_MODELS", "claude-opus-x")
                .withProperty("QEPLOY_AI_ANTHROPIC_BASE_URL", "https://proxy.internal/v1/messages")
                .withProperty("QEPLOY_AI_OPENAI_MODEL", "gpt-x")
                .withProperty("QEPLOY_AI_OPENAI_ALLOWED_MODELS", "gpt-x-mini")
                .withProperty("QEPLOY_AI_OPENAI_THINKING_MODELS", "o-x")
                .withProperty("QEPLOY_AI_GLM_MODEL", "glm-x")
                .withProperty("QEPLOY_AI_GLM_ALLOWED_MODELS", "glm-x-flash")
                .withProperty("QEPLOY_AI_GLM_THINKING_MODELS", "glm-x");
        addProfileProperties(environment, profile);

        AiProperties properties = bind(environment);

        assertThat(properties.getAnthropic().getModel()).isEqualTo("claude-sonnet-x");
        assertThat(properties.getAnthropic().getAllowedModels()).containsExactly("claude-haiku-x", "claude-opus-x");
        assertThat(properties.getAnthropic().getThinkingModels()).containsExactly("claude-opus-x");
        assertThat(properties.getAnthropic().getBaseUrl()).isEqualTo("https://proxy.internal/v1/messages");

        assertThat(properties.getOpenai().getModel()).isEqualTo("gpt-x");
        assertThat(properties.getOpenai().getAllowedModels()).containsExactly("gpt-x-mini");
        assertThat(properties.getOpenai().getThinkingModels()).containsExactly("o-x");

        assertThat(properties.getGlm().getModel()).isEqualTo("glm-x");
        assertThat(properties.getGlm().getAllowedModels()).containsExactly("glm-x-flash");
        assertThat(properties.getGlm().getThinkingModels()).containsExactly("glm-x");

        // The bound catalogue is what admission actually consults.
        assertThat(properties.getAnthropic().allows("claude-haiku-x")).isTrue();
        assertThat(properties.getAnthropic().allows("claude-unlisted")).isFalse();
        assertThat(properties.getAnthropic().supportsThinking("claude-opus-x")).isTrue();
        assertThat(properties.getAnthropic().supportsThinking("claude-haiku-x")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"application-dev.yml", "application-prod.yml"})
    void defaultsTheModelCatalogueToTheLeastPrivilege(String profile) throws IOException {
        MockEnvironment environment = new MockEnvironment();
        addProfileProperties(environment, profile);

        AiProperties properties = bind(environment);

        // Nothing beyond the default model may be named until an operator widens it.
        assertThat(properties.getAnthropic().getModel()).isEqualTo("claude-opus-4-5-20251101");
        assertThat(properties.getAnthropic().getAllowedModels()).isEmpty();
        assertThat(properties.getAnthropic().getBaseUrl()).isEqualTo("https://api.anthropic.com/v1/messages");
        assertThat(properties.getOpenai().getModel()).isEqualTo("gpt-4o");
        assertThat(properties.getOpenai().getAllowedModels()).isEmpty();
        assertThat(properties.getGlm().getAllowedModels()).isEmpty();
        // gpt-4o rejects reasoning_effort, so no OpenAI model thinks by default.
        assertThat(properties.getOpenai().getThinkingModels()).isEmpty();
        assertThat(properties.getAnthropic().getThinkingModels()).containsExactly("claude-opus-4-5-20251101");
        assertThat(properties.getGlm().getThinkingModels()).containsExactly("z-ai/glm-4.6");
    }

    @ParameterizedTest
    @ValueSource(strings = {"application-dev.yml", "application-prod.yml"})
    void hasNoPlaceToHoldAVendorKeyEvenWhenTheEnvironmentOffersOne(String profile) throws IOException {
        // Inverse of the two tests removed above. Every variable that used to be bound onto the
        // provider is present here, and the property tree must still have nowhere to put it: the
        // deployment's ambient credentials must never become the key a user's request runs on.
        MockEnvironment environment = new MockEnvironment()
                .withProperty("ANTHROPIC_API_KEY", "ambient-anthropic-key")
                .withProperty("OPENAI_API_KEY", "ambient-openai-key")
                .withProperty("OPENROUTER_API_KEY", "ambient-openrouter-key")
                .withProperty("QEPLOY_AI_ANTHROPIC_API_KEY", "qeploy-anthropic-key")
                .withProperty("QEPLOY_AI_OPENAI_API_KEY", "qeploy-openai-key")
                .withProperty("QEPLOY_AI_GLM_API_KEY", "qeploy-glm-key");
        addProfileProperties(environment, profile);

        // Binding must still succeed: a leftover api-key line in a yml or an ecosystem file must
        // not stop a deployment from booting, it must just be inert.
        AiProperties properties = bind(environment);

        assertThat(properties).isNotNull();
        for (Class<?> type : configurationTypes()) {
            assertThat(declaredNames(type))
                    .as("%s must not declare a key-holding member", type.getSimpleName())
                    .noneMatch(name -> name.toLowerCase().contains("apikey"));
        }
    }

    @Test
    void hasNoDeploymentWideProviderSelection() {
        // defaultProvider used to decide which vendor's server key served a request that named none.
        // With no server key there is nothing to default to: the provider is always the request's.
        assertThat(declaredNames(AiProperties.class))
                .noneMatch(name -> name.toLowerCase().contains("defaultprovider"))
                .noneMatch(name -> name.toLowerCase().contains("failureanalysis"));
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

    private AiProperties bind(MockEnvironment environment) {
        return Binder.get(environment)
                .bind("qeploy.ai", Bindable.of(AiProperties.class))
                .orElseThrow(() -> new IllegalStateException("qeploy.ai 설정 바인딩 실패"));
    }

    /** AiProperties and every provider block nested in it, including the shared base class. */
    private static List<Class<?>> configurationTypes() {
        List<Class<?>> types = new ArrayList<>();
        types.add(AiProperties.class);
        types.addAll(Arrays.asList(AiProperties.class.getDeclaredClasses()));
        return types;
    }

    /** Field and method names a type declares — a getter, setter or field would each show up. */
    private static List<String> declaredNames(Class<?> type) {
        List<String> names = new ArrayList<>();
        Arrays.stream(type.getDeclaredFields()).map(Field::getName).forEach(names::add);
        Arrays.stream(type.getDeclaredMethods()).map(Method::getName).forEach(names::add);
        return names;
    }

    private void addProfileProperties(MockEnvironment environment, String resourceName) throws IOException {
        new YamlPropertySourceLoader()
                .load(resourceName, new ClassPathResource(resourceName))
                .forEach(environment.getPropertySources()::addLast);
    }
}
