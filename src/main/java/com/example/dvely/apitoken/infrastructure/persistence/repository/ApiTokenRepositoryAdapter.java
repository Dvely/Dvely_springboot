package com.example.dvely.apitoken.infrastructure.persistence.repository;

import com.example.dvely.apitoken.domain.model.ApiToken;
import com.example.dvely.apitoken.domain.repository.ApiTokenRepository;
import com.example.dvely.apitoken.infrastructure.persistence.entity.ApiTokenEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class ApiTokenRepositoryAdapter implements ApiTokenRepository {

    private final SpringDataApiTokenRepository springDataRepository;

    @Override
    public ApiToken save(ApiToken token) {
        return springDataRepository.saveAndFlush(ApiTokenEntity.from(token)).toDomain();
    }

    @Override
    public Optional<ApiToken> findByTokenHash(String tokenHash) {
        return springDataRepository.findByTokenHash(tokenHash).map(ApiTokenEntity::toDomain);
    }

    @Override
    public List<ApiToken> findByUserIdOrderByCreatedAtDesc(Long userId) {
        return springDataRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(ApiTokenEntity::toDomain).toList();
    }

    @Override
    @Transactional
    public boolean deleteByIdAndUserId(Long id, Long userId) {
        return springDataRepository.deleteByIdAndUserId(id, userId) > 0;
    }

    @Override
    @Transactional
    public void touchLastUsed(Long id, LocalDateTime now) {
        springDataRepository.touchLastUsed(id, now);
    }

    /**
     * {@code @Transactional} 이 필수다 — 호출자(TokenCleanupScheduler)는 주변 트랜잭션 없이
     * 도는 {@code @Scheduled} 메서드이고, Spring Data 프록시는 커스텀 {@code @Modifying} 쿼리에
     * 대해서는 CRUD 메서드와 달리 트랜잭션을 열어주지 않는다.
     */
    @Override
    @Transactional
    public int deleteExpiredBefore(LocalDateTime cutoff) {
        return springDataRepository.deleteExpiredBefore(cutoff);
    }
}
