package com.example.dvely.apitoken.application.service;

import com.example.dvely.apitoken.domain.model.ApiToken;
import com.example.dvely.apitoken.domain.repository.ApiTokenRepository;
import com.example.dvely.apitoken.domain.service.ApiTokenGenerator;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Turns a presented PAT into an authenticated user, or into nothing.
 *
 * <p>Lookup is by hash: the presented value is hashed and that row is fetched. There is no path
 * that compares plaintexts, so a mistyped comparison cannot become an auth bypass, and an unknown
 * token simply finds no row.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiTokenAuthenticator {

    private final ApiTokenRepository apiTokenRepository;

    /**
     * @return the token when it exists and has not expired, otherwise empty. Expiry is checked here
     *         rather than in a query so that "expired" and "unknown" are the same outcome to the
     *         caller — neither should tell an attacker which one it was.
     */
    public Optional<ApiToken> authenticate(String plaintext) {
        LocalDateTime now = LocalDateTime.now();
        Optional<ApiToken> found =
                apiTokenRepository.findByTokenHash(ApiTokenGenerator.hash(plaintext));

        if (found.isEmpty()) {
            return Optional.empty();
        }
        ApiToken token = found.get();
        if (token.isExpired(now)) {
            return Optional.empty();
        }

        // Best-effort: a failure to record usage must not fail the request the user actually made.
        if (token.shouldRefreshLastUsed(now)) {
            try {
                apiTokenRepository.touchLastUsed(token.getId(), now);
            } catch (RuntimeException e) {
                log.warn("PAT last_used_at 갱신 실패: tokenId={} reason={}", token.getId(), e.toString());
            }
        }
        return Optional.of(token);
    }
}
