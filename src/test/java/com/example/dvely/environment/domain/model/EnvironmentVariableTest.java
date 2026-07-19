package com.example.dvely.environment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dvely.environment.domain.value.EnvironmentScope;
import org.junit.jupiter.api.Test;

class EnvironmentVariableTest {

    @Test
    void acceptsAWellFormedKey() {
        EnvironmentVariable variable = new EnvironmentVariable(1L, EnvironmentScope.PREVIEW, "API_BASE_URL", "value", false);

        assertThat(variable.getKey()).isEqualTo("API_BASE_URL");
    }

    @Test
    void trimsLeadingAndTrailingWhitespaceFromKeyOnly() {
        EnvironmentVariable variable = new EnvironmentVariable(1L, EnvironmentScope.PREVIEW, "  API_KEY  ", "value", false);

        assertThat(variable.getKey()).isEqualTo("API_KEY");
    }

    @Test
    void rejectsKeyStartingWithADigit() {
        assertThatThrownBy(() -> new EnvironmentVariable(1L, EnvironmentScope.PREVIEW, "1KEY", "value", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsKeyContainingAHyphen() {
        assertThatThrownBy(() -> new EnvironmentVariable(1L, EnvironmentScope.PREVIEW, "API-KEY", "value", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsKeyContainingInternalWhitespace() {
        // Only leading/trailing whitespace is trimmed (D9) — an embedded space still fails the
        // POSIX-style pattern after trimming.
        assertThatThrownBy(() -> new EnvironmentVariable(1L, EnvironmentScope.PREVIEW, "API KEY", "value", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankKey() {
        assertThatThrownBy(() -> new EnvironmentVariable(1L, EnvironmentScope.PREVIEW, "   ", "value", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsKeyLongerThan128Characters() {
        String tooLong = "A".repeat(129);

        assertThatThrownBy(() -> new EnvironmentVariable(1L, EnvironmentScope.PREVIEW, tooLong, "value", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsKeyOfExactly128Characters() {
        String maxLength = "A".repeat(128);

        EnvironmentVariable variable = new EnvironmentVariable(1L, EnvironmentScope.PREVIEW, maxLength, "value", false);

        assertThat(variable.getKey()).hasSize(128);
    }

    @Test
    void allowsAnEmptyValue() {
        EnvironmentVariable variable = new EnvironmentVariable(1L, EnvironmentScope.PREVIEW, "KEY", "", false);

        assertThat(variable.getValue()).isEmpty();
    }

    @Test
    void rejectsNullValue() {
        assertThatThrownBy(() -> new EnvironmentVariable(1L, EnvironmentScope.PREVIEW, "KEY", null, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsValueOfExactlyMaxLength() {
        String maxLength = "a".repeat(4096);

        EnvironmentVariable variable = new EnvironmentVariable(1L, EnvironmentScope.PREVIEW, "KEY", maxLength, false);

        assertThat(variable.getValue()).hasSize(4096);
    }

    @Test
    void rejectsValueLongerThanMaxLength() {
        String tooLong = "a".repeat(4097);

        assertThatThrownBy(() -> new EnvironmentVariable(1L, EnvironmentScope.PREVIEW, "KEY", tooLong, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsValueContainingNulCharacter() {
        String withNul = "abc" + '\u0000' + "def";

        assertThatThrownBy(() -> new EnvironmentVariable(1L, EnvironmentScope.PREVIEW, "KEY", withNul, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void neverTrimsValueWhitespace() {
        EnvironmentVariable variable = new EnvironmentVariable(1L, EnvironmentScope.PREVIEW, "KEY", "  spaced value  ", false);

        assertThat(variable.getValue()).isEqualTo("  spaced value  ");
    }

    @Test
    void changeValueReplacesValueAfterRevalidating() {
        EnvironmentVariable variable = new EnvironmentVariable(1L, EnvironmentScope.PREVIEW, "KEY", "old", false);

        variable.changeValue("new");

        assertThat(variable.getValue()).isEqualTo("new");
    }

    @Test
    void changeValueRejectsTooLongReplacement() {
        EnvironmentVariable variable = new EnvironmentVariable(1L, EnvironmentScope.PREVIEW, "KEY", "old", false);

        assertThatThrownBy(() -> variable.changeValue("a".repeat(4097)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markSecretPromotesFalseToTrue() {
        EnvironmentVariable variable = new EnvironmentVariable(1L, EnvironmentScope.PREVIEW, "KEY", "value", false);

        variable.markSecret();

        assertThat(variable.isSecret()).isTrue();
    }

    @Test
    void unmarkSecretAlwaysThrows() {
        EnvironmentVariable variable = new EnvironmentVariable(1L, EnvironmentScope.PREVIEW, "KEY", "value", true);

        assertThatThrownBy(variable::unmarkSecret)
                .isInstanceOf(IllegalArgumentException.class);
        // The attempted downgrade must not silently succeed even after throwing.
        assertThat(variable.isSecret()).isTrue();
    }
}
