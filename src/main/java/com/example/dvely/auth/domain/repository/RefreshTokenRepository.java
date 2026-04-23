package com.example.dvely.auth.domain.repository;

import com.example.dvely.auth.domain.model.RefreshToken;

import java.util.Optional;

public interface RefreshTokenRepository {
    RefreshToken save(RefreshToken refreshToken);
    Optional<RefreshToken> findByToken(String token);
    void revokeAllByUserId(Long userId);
}
