package com.example.dvely.auth.domain.repository;

import com.example.dvely.auth.domain.model.RefreshToken;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RefreshTokenRepository {
    /** 새로 발급한 토큰을 저장한다. 원문이 아니라 해시만 남는다(#506). */
    void saveNew(RefreshToken refreshToken);

    /** 원문 토큰으로 찾는다. 돌려주는 객체의 token 은 비어 있다 — 원문은 저장되지 않는다. */
    Optional<RefreshToken> findByToken(String rawToken);

    /** 아직 유효한 토큰이면 폐기하고 true. 이미 폐기·만료됐거나 다른 요청이 먼저 폐기했으면 false. */
    boolean revokeIfActive(String rawToken, LocalDateTime now);

    void revokeAllByUserId(Long userId);
}
