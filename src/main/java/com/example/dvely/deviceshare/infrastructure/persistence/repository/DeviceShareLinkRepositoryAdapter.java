package com.example.dvely.deviceshare.infrastructure.persistence.repository;

import com.example.dvely.deviceshare.domain.model.DeviceShareLink;
import com.example.dvely.deviceshare.domain.repository.DeviceShareLinkRepository;
import com.example.dvely.deviceshare.infrastructure.persistence.entity.DeviceShareLinkEntity;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class DeviceShareLinkRepositoryAdapter implements DeviceShareLinkRepository {

    private final SpringDataDeviceShareLinkRepository jpa;

    @Override
    @Transactional
    public DeviceShareLink save(DeviceShareLink link) {
        // DATETIME(6) 와 같은 정밀도로 맞춰 둬야 저장 전후 값이 같다.
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        return jpa.save(DeviceShareLinkEntity.from(link, now)).toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DeviceShareLink> findById(Long shareId) {
        return jpa.findById(shareId).map(DeviceShareLinkEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DeviceShareLink> findByTokenHash(String tokenHash) {
        return jpa.findByTokenHash(tokenHash).map(DeviceShareLinkEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeviceShareLink> findRecentByUser(Long userId, int limit) {
        return jpa.findRecentByUser(userId, PageRequest.of(0, limit)).stream()
                .map(DeviceShareLinkEntity::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countActiveByUser(Long userId, LocalDateTime now) {
        return jpa.countActiveByUser(userId, now);
    }

    @Override
    @Transactional
    public boolean markRevoked(Long shareId, LocalDateTime now) {
        return jpa.markRevoked(shareId, now) > 0;
    }

    @Override
    @Transactional
    public void touchViewed(Long shareId, LocalDateTime now) {
        jpa.touchViewed(shareId, now);
    }
}
