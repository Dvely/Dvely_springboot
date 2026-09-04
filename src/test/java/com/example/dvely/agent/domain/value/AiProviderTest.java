package com.example.dvely.agent.domain.value;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AiProviderTest {

    @Test
    void classifiesTheCliExecutionModesAsCodingAgents() {
        assertThat(AiProvider.CLAUDE_CODE.isCodingAgent()).isTrue();
        assertThat(AiProvider.CODEX.isCodingAgent()).isTrue();
    }

    @Test
    void classifiesTheChatCompletionProvidersAsNotCodingAgents() {
        assertThat(AiProvider.ANTHROPIC.isCodingAgent()).isFalse();
        assertThat(AiProvider.OPENAI.isCodingAgent()).isFalse();
        assertThat(AiProvider.GLM.isCodingAgent()).isFalse();
    }

    @Test
    void mapsEachCodingAgentToTheVendorWhoseKeyItsCliReads() {
        // Claude Code runs on ANTHROPIC_API_KEY and Codex on OPENAI_API_KEY, so a user registers
        // each vendor key once rather than once per execution mode.
        assertThat(AiProvider.CLAUDE_CODE.credentialVendor()).isEqualTo(AiProvider.ANTHROPIC);
        assertThat(AiProvider.CODEX.credentialVendor()).isEqualTo(AiProvider.OPENAI);
    }

    @ParameterizedTest
    @EnumSource(value = AiProvider.class, names = {"ANTHROPIC", "OPENAI", "GLM"})
    void vendorsMapToThemselves(AiProvider vendor) {
        assertThat(vendor.credentialVendor()).isEqualTo(vendor);
    }

    @ParameterizedTest
    @EnumSource(AiProvider.class)
    void everyProviderResolvesToAVendorThatCanOwnACredential(AiProvider provider) {
        // Guards the invariant the credential store depends on: whatever is added to this enum
        // later, credentialVendor() must never point at another execution mode.
        assertThat(provider.credentialVendor().isCodingAgent()).isFalse();
    }
}
