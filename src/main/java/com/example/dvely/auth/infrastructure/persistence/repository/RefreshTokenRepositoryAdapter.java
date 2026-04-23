package com.example.dvely.auth.infrastructure.persistence.repository;

import com.example.dvely.auth.domain.model.RefreshToken;
import com.example.dvely.auth.domain.repository.RefreshTokenRepository;
import com.example.dvely.auth.infrastructure.persistence.entity.RefreshTokenEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class RefreshTokenRepositoryAdapter implements RefreshTokenRepository {

    private final SpringDataRefreshTokenRepository springDataRefreshTokenRepository;

    @Override
    public RefreshToken save(RefreshToken refreshToken) {
        RefreshTokenEntity entity = springDataRefreshTokenRepository.findByToken(refreshToken.getToken())
                .orElseGet(() -> RefreshTokenEntity.from(refreshToken));

        if (refreshToken.isRevoked()) {
            entity.revoke();
        }

        return springDataRefreshTokenRepository.save(entity).toDomain();
    }

    @Override
    public Optional<RefreshToken> findByToken(String token) {
        return springDataRefreshTokenRepository.findByToken(token)
                .map(RefreshTokenEntity::toDomain);
    }

    @Override
    public void revokeAllByUserId(Long userId) {
        springDataRefreshTokenRepository.revokeAllByUserId(userId);
    }
}
