package com.example.dvely.auth.infrastructure.persistence.repository;

import com.example.dvely.auth.infrastructure.persistence.entity.InstallationTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Optional;

public interface SpringDataInstallationTokenRepository extends JpaRepository<InstallationTokenEntity, Long> {
    Optional<InstallationTokenEntity> findByInstallationId(Long installationId);

    @Modifying
    @Query("DELETE FROM InstallationTokenEntity t WHERE t.expiresAt < :now")
    int deleteExpired(LocalDateTime now);
}
