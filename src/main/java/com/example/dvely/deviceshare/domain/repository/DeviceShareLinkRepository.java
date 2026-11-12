package com.example.dvely.deviceshare.domain.repository;

import com.example.dvely.deviceshare.domain.model.DeviceShareLink;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DeviceShareLinkRepository {

    DeviceShareLink save(DeviceShareLink link);

    Optional<DeviceShareLink> findById(Long shareId);

    Optional<DeviceShareLink> findByTokenHash(String tokenHash);

    List<DeviceShareLink> findRecentByUser(Long userId, int limit);

    long countActiveByUser(Long userId, LocalDateTime now);

    /** @return 회수 표시가 실제로 붙었는지(이미 회수됐으면 false) */
    boolean markRevoked(Long shareId, LocalDateTime now);

    void touchViewed(Long shareId, LocalDateTime now);
}
