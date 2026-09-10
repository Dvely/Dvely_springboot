package com.example.dvely.apitoken.infrastructure.persistence.repository;

import com.example.dvely.apitoken.infrastructure.persistence.entity.ApiTokenEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataApiTokenRepository extends JpaRepository<ApiTokenEntity, Long> {

    Optional<ApiTokenEntity> findByTokenHash(String tokenHash);

    List<ApiTokenEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    long deleteByIdAndUserId(Long id, Long userId);

    // A targeted UPDATE rather than a load-modify-save: this runs on the authentication path, and
    // nothing else about the row is being changed.
    @Modifying
    @Query("update ApiTokenEntity t set t.lastUsedAt = :now where t.id = :id")
    void touchLastUsed(@Param("id") Long id, @Param("now") LocalDateTime now);
}
