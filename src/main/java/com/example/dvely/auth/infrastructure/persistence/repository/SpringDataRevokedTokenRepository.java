package com.example.dvely.auth.infrastructure.persistence.repository;

import com.example.dvely.auth.infrastructure.persistence.entity.RevokedTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Optional;

public interface SpringDataRevokedTokenRepository extends JpaRepository<RevokedTokenEntity, Long> {
    /**
     * 존재 여부만이 아니라 행을 가져온다 — 같은 UNIQUE 인덱스 한 번 타는 것은 같고, 캐시에 넣을
     * 만료 시각을 여기서 함께 얻어야 두 번째 조회를 하지 않는다.
     */
    Optional<RevokedTokenEntity> findByJti(String jti);

    @Modifying
    @Query("DELETE FROM RevokedTokenEntity r WHERE r.expiresAt < :now")
    int deleteExpired(LocalDateTime now);
}
