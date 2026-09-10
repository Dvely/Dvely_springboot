package com.example.dvely.apitoken.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dvely.apitoken.domain.model.ApiToken;
import com.example.dvely.apitoken.domain.model.IssuedApiToken;
import com.example.dvely.apitoken.domain.service.ApiTokenGenerator;
import com.example.dvely.apitoken.domain.value.ApiTokenScope;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ApiTokenTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 8, 12, 0);

    private static ApiToken stored(ApiTokenScope scope, LocalDateTime expiresAt, LocalDateTime lastUsed) {
        return new ApiToken(1L, 7L, "hash", "qp_abcd1234", scope, null, expiresAt, lastUsed, NOW);
    }

    @Test
    void issuesATokenWhosePlaintextCarriesTheRoutingPrefix() {
        // The filter decides between PAT and JWT by this prefix rather than by trying to parse a
        // JWT first — a wrong guess there turns every bad token into a stack trace.
        IssuedApiToken issued =
                ApiTokenGenerator.issue(7L, ApiTokenScope.READ, null, NOW.plusDays(90));

        assertThat(issued.plaintext()).startsWith("qp_");
        assertThat(ApiTokenGenerator.looksLikeApiToken(issued.plaintext())).isTrue();
    }

    @Test
    void storesOnlyAHashAndADisplayPrefixNeverThePlaintext() {
        IssuedApiToken issued =
                ApiTokenGenerator.issue(7L, ApiTokenScope.READ, null, NOW.plusDays(90));
        ApiToken stored = issued.stored();

        assertThat(stored.getTokenHash()).isEqualTo(ApiTokenGenerator.hash(issued.plaintext()));
        assertThat(stored.getTokenHash()).isNotEqualTo(issued.plaintext());
        assertThat(stored.getTokenPrefix()).isEqualTo(issued.plaintext().substring(0, 11));
        // Losing the database must not hand anyone a working token.
        assertThat(stored.toString()).doesNotContain(issued.plaintext());
    }

    @Test
    void issuedTokenToStringRedactsThePlaintext() {
        IssuedApiToken issued =
                ApiTokenGenerator.issue(7L, ApiTokenScope.WRITE, null, NOW.plusDays(1));

        // A record's generated toString would print the token into any log line formatting it.
        assertThat(issued.toString()).doesNotContain(issued.plaintext());
        assertThat(issued.toString()).contains("plaintext=***");
    }

    @Test
    void everyIssuedTokenIsDistinct() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(ApiTokenGenerator.issue(7L, ApiTokenScope.READ, null, NOW.plusDays(1)).plaintext());
        }

        assertThat(seen).hasSize(200);
    }

    @Test
    void hashingIsStableSoALookupByHashFindsTheRow() {
        String token = "qp_fixed-value-for-this-test";

        assertThat(ApiTokenGenerator.hash(token)).isEqualTo(ApiTokenGenerator.hash(token));
        assertThat(ApiTokenGenerator.hash(token)).hasSize(64);
    }

    @Test
    void doesNotMistakeAJwtForAnApiToken() {
        assertThat(ApiTokenGenerator.looksLikeApiToken("eyJhbGciOiJIUzI1NiJ9.abc.def")).isFalse();
        assertThat(ApiTokenGenerator.looksLikeApiToken(null)).isFalse();
    }

    @Test
    void expiryIsInclusiveOfTheExpiryInstant() {
        assertThat(stored(ApiTokenScope.READ, NOW.plusMinutes(1), null).isExpired(NOW)).isFalse();
        // At exactly expires_at the token is already expired — an "expires at 12:00" token must not
        // still work at 12:00:00.
        assertThat(stored(ApiTokenScope.READ, NOW, null).isExpired(NOW)).isTrue();
        assertThat(stored(ApiTokenScope.READ, NOW.minusSeconds(1), null).isExpired(NOW)).isTrue();
    }

    @Test
    void writeImpliesReadButReadNeverImpliesWrite() {
        assertThat(stored(ApiTokenScope.WRITE, NOW.plusDays(1), null).allowsWrite()).isTrue();
        assertThat(stored(ApiTokenScope.READ, NOW.plusDays(1), null).allowsWrite()).isFalse();
    }

    @Test
    void lastUsedIsRefreshedOnlyOnceAnHourSoAuthDoesNotWriteOnEveryRequest() {
        assertThat(stored(ApiTokenScope.READ, NOW.plusDays(1), null).shouldRefreshLastUsed(NOW)).isTrue();
        assertThat(stored(ApiTokenScope.READ, NOW.plusDays(1), NOW.minusMinutes(59))
                .shouldRefreshLastUsed(NOW)).isFalse();
        assertThat(stored(ApiTokenScope.READ, NOW.plusDays(1), NOW.minusHours(2))
                .shouldRefreshLastUsed(NOW)).isTrue();
    }

    @Test
    void rejectsAnOverlongLabel() {
        assertThatThrownBy(() -> new ApiToken(7L, "h", "qp_a", ApiTokenScope.READ,
                "l".repeat(65), NOW.plusDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalisesABlankLabelToNull() {
        assertThat(new ApiToken(7L, "h", "qp_a", ApiTokenScope.READ, "   ", NOW.plusDays(1))
                .getLabel()).isNull();
    }
}
