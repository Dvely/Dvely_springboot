package com.example.dvely.deviceshare.infrastructure.persistence.repository;

import com.example.dvely.deviceshare.infrastructure.persistence.entity.DeviceShareLinkEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataDeviceShareLinkRepository extends JpaRepository<DeviceShareLinkEntity, Long> {

    Optional<DeviceShareLinkEntity> findByTokenHash(String tokenHash);

    @Query("select l from DeviceShareLinkEntity l where l.userId = :userId order by l.createdAt desc, l.id desc")
    List<DeviceShareLinkEntity> findRecentByUser(@Param("userId") Long userId, Pageable pageable);

    @Query("select count(l) from DeviceShareLinkEntity l "
            + "where l.userId = :userId and l.revokedAt is null and l.expiresAt > :now")
    long countActiveByUser(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update DeviceShareLinkEntity l set l.revokedAt = :now where l.id = :id and l.revokedAt is null")
    int markRevoked(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update DeviceShareLinkEntity l set l.lastViewedAt = :now where l.id = :id")
    int touchViewed(@Param("id") Long id, @Param("now") LocalDateTime now);
}
