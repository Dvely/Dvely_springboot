package com.example.dvely.apitoken.domain.repository;

import com.example.dvely.apitoken.domain.model.ApiToken;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Lookup is by hash, never by plaintext or id-alone: the filter hashes what it was given and asks
 * for that row, so a token that does not exist produces no row rather than a comparison the caller
 * could get wrong. Listing and per-token deletion are user-scoped so no caller can reach another
 * user's tokens; {@link #deleteExpiredBefore} is the one exception, and it is reachable only from
 * the retention scheduler, never from a request.
 */
public interface ApiTokenRepository {

    ApiToken save(ApiToken token);

    Optional<ApiToken> findByTokenHash(String tokenHash);

    List<ApiToken> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** @return true when a row was actually removed, false when the user had no such token. */
    boolean deleteByIdAndUserId(Long id, Long userId);

    void touchLastUsed(Long id, LocalDateTime now);

    /**
     * 만료된 지 유예 기간이 지난 토큰을 지운다(#338 — 지금까지 만료 토큰이 영구 잔존했다).
     *
     * @return 지워진 건수
     */
    int deleteExpiredBefore(LocalDateTime cutoff);
}
