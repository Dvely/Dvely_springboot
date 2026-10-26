package com.example.dvely.auth.infrastructure.persistence.repository;

import com.example.dvely.auth.domain.model.RefreshToken;
import com.example.dvely.auth.domain.repository.RefreshTokenRepository;
import com.example.dvely.auth.infrastructure.persistence.entity.RefreshTokenEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class RefreshTokenRepositoryAdapter implements RefreshTokenRepository {

    private final SpringDataRefreshTokenRepository springDataRefreshTokenRepository;

    @Override
    public void saveNew(RefreshToken refreshToken) {
        springDataRefreshTokenRepository.save(new RefreshTokenEntity(
                refreshToken.getUserId(), RefreshTokenHash.of(refreshToken.getToken()), refreshToken.getExpiresAt()));
    }

    @Override
    public Optional<RefreshToken> findByToken(String rawToken) {
        return springDataRefreshTokenRepository.findByTokenHash(RefreshTokenHash.of(rawToken))
                .map(RefreshTokenEntity::toDomain);
    }

    @Override
    public boolean revokeIfActive(String rawToken, LocalDateTime now) {
        return springDataRefreshTokenRepository.revokeIfActive(RefreshTokenHash.of(rawToken), now) == 1;
    }

    @Override
    public void revokeAllByUserId(Long userId) {
        springDataRefreshTokenRepository.revokeAllByUserId(userId);
    }
}
