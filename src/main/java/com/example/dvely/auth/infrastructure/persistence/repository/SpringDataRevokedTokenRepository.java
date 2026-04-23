package com.example.dvely.auth.infrastructure.persistence.repository;

import com.example.dvely.auth.infrastructure.persistence.entity.RevokedTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;

public interface SpringDataRevokedTokenRepository extends JpaRepository<RevokedTokenEntity, Long> {
    boolean existsByJti(String jti);

    @Modifying
    @Query("DELETE FROM RevokedTokenEntity r WHERE r.expiresAt < :now")
    int deleteExpired(LocalDateTime now);
}
