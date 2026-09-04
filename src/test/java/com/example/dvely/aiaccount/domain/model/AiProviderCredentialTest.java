package com.example.dvely.aiaccount.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dvely.agent.domain.value.AiProvider;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiProviderCredentialTest {

    private static final String ANTHROPIC_KEY = "sk-ant-api03-abcdefghijklmnop";

    @Test
    void acceptsAWellFormedKey() {
        AiProviderCredential credential =
                new AiProviderCredential(1L, AiProvider.ANTHROPIC, ANTHROPIC_KEY, "내 개인 키");

        assertThat(credential.getApiKey()).isEqualTo(ANTHROPIC_KEY);
        assertThat(credential.getProvider()).isEqualTo(AiProvider.ANTHROPIC);
        assertThat(credential.getLabel()).isEqualTo("내 개인 키");
    }

    @Test
    void trimsSurroundingWhitespaceFromAPastedKey() {
        AiProviderCredential credential =
                new AiProviderCredential(1L, AiProvider.OPENAI, "  " + ANTHROPIC_KEY + "\n", null);

        assertThat(credential.getApiKey()).isEqualTo(ANTHROPIC_KEY);
    }

    @Test
    void rejectsABlankKey() {
        assertThatThrownBy(() -> new AiProviderCredential(1L, AiProvider.ANTHROPIC, "   ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANullKey() {
        assertThatThrownBy(() -> new AiProviderCredential(1L, AiProvider.ANTHROPIC, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAKeyWithInternalWhitespace() {
        // This value is injected verbatim into a container env var; an embedded space or newline
        // would either break the var or surprise a shell downstream.
        assertThatThrownBy(() -> new AiProviderCredential(1L, AiProvider.ANTHROPIC, "sk-ant abc", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAKeyWithAControlCharacter() {
        assertThatThrownBy(() -> new AiProviderCredential(1L, AiProvider.ANTHROPIC, "sk-ant\001abc", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnOverlongKey() {
        String tooLong = "s".repeat(513);

        assertThatThrownBy(() -> new AiProviderCredential(1L, AiProvider.ANTHROPIC, tooLong, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANullUserId() {
        // user scope is what keeps operator key-pooling structurally impossible, so it is not
        // merely a not-null convention — it is the compliance boundary.
        assertThatThrownBy(() -> new AiProviderCredential(null, AiProvider.ANTHROPIC, ANTHROPIC_KEY, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsAnExecutionModeAsTheCredentialOwner() {
        // CLAUDE_CODE reads the ANTHROPIC key; storing a row under the execution mode would give
        // the same key two homes and let the copies drift.
        assertThatThrownBy(() -> new AiProviderCredential(1L, AiProvider.CLAUDE_CODE, ANTHROPIC_KEY, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ANTHROPIC");

        assertThatThrownBy(() -> new AiProviderCredential(1L, AiProvider.CODEX, ANTHROPIC_KEY, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OPENAI");
    }

    @Test
    void acceptsEveryVendorAsACredentialOwner() {
        for (AiProvider vendor : List.of(AiProvider.ANTHROPIC, AiProvider.OPENAI, AiProvider.GLM)) {
            assertThat(new AiProviderCredential(1L, vendor, ANTHROPIC_KEY, null).getProvider())
                    .isEqualTo(vendor);
        }
    }

    @Test
    void normalisesABlankLabelToNull() {
        AiProviderCredential credential =
                new AiProviderCredential(1L, AiProvider.ANTHROPIC, ANTHROPIC_KEY, "   ");

        assertThat(credential.getLabel()).isNull();
    }

    @Test
    void rejectsAnOverlongLabel() {
        assertThatThrownBy(
                () -> new AiProviderCredential(1L, AiProvider.ANTHROPIC, ANTHROPIC_KEY, "l".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void masksAllButAShortLeadingSlice() {
        AiProviderCredential credential =
                new AiProviderCredential(1L, AiProvider.ANTHROPIC, ANTHROPIC_KEY, null);

        assertThat(credential.maskedApiKey()).isEqualTo("sk-ant****");
    }

    @Test
    void maskedKeyNeverRevealsTheTail() {
        AiProviderCredential credential =
                new AiProviderCredential(1L, AiProvider.ANTHROPIC, ANTHROPIC_KEY, null);

        // The tail is real key entropy — a suffix-revealing mask would shorten a brute-force.
        assertThat(credential.maskedApiKey()).doesNotContain("mnop");
    }

    @Test
    void masksAVeryShortKeyEntirely() {
        AiProviderCredential credential = new AiProviderCredential(1L, AiProvider.GLM, "abc", null);

        assertThat(credential.maskedApiKey()).isEqualTo("****");
    }

    @Test
    void rotatesTheKeyInPlace() {
        AiProviderCredential credential =
                new AiProviderCredential(1L, AiProvider.ANTHROPIC, ANTHROPIC_KEY, null);

        credential.changeApiKey("sk-ant-api03-rotated-value");

        assertThat(credential.getApiKey()).isEqualTo("sk-ant-api03-rotated-value");
    }

    @Test
    void rotationRevalidatesTheNewKey() {
        AiProviderCredential credential =
                new AiProviderCredential(1L, AiProvider.ANTHROPIC, ANTHROPIC_KEY, null);

        assertThatThrownBy(() -> credential.changeApiKey(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hasNoToStringOverrideThatCouldLeakTheKey() {
        AiProviderCredential credential =
                new AiProviderCredential(1L, AiProvider.ANTHROPIC, ANTHROPIC_KEY, null);

        // Deliberately relies on Object#toString: the guarantee is that the key never appears,
        // which a future @ToString/@Data on this class would break.
        assertThat(credential.toString()).doesNotContain(ANTHROPIC_KEY);
    }
}
