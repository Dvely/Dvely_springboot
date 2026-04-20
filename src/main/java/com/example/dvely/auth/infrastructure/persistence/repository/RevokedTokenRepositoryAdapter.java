package com.example.dvely.auth.infrastructure.persistence.repository;

import com.example.dvely.auth.application.port.out.TokenBlacklistPort;
import com.example.dvely.auth.infrastructure.persistence.entity.RevokedTokenEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
@RequiredArgsConstructor
public class RevokedTokenRepositoryAdapter implements TokenBlacklistPort {

    private final SpringDataRevokedTokenRepository springDataRevokedTokenRepository;

    @Override
    public void revoke(String jti, LocalDateTime expiresAt) {
        springDataRevokedTokenRepository.save(new RevokedTokenEntity(jti, expiresAt));
    }

    @Override
    public boolean isRevoked(String jti) {
        return springDataRevokedTokenRepository.existsByJti(jti);
    }
}
