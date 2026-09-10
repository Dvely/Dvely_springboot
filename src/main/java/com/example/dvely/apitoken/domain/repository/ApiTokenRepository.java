package com.example.dvely.apitoken.domain.repository;

import com.example.dvely.apitoken.domain.model.ApiToken;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Lookup is by hash, never by plaintext or id-alone: the filter hashes what it was given and asks
 * for that row, so a token that does not exist produces no row rather than a comparison the caller
 * could get wrong. Listing and deletion are user-scoped so no caller can reach another user's
 * tokens.
 */
public interface ApiTokenRepository {

    ApiToken save(ApiToken token);

    Optional<ApiToken> findByTokenHash(String tokenHash);

    List<ApiToken> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** @return true when a row was actually removed, false when the user had no such token. */
    boolean deleteByIdAndUserId(Long id, Long userId);

    void touchLastUsed(Long id, LocalDateTime now);
}
