package com.example.dvely.deviceshare.domain.model;

import com.example.dvely.deviceshare.domain.value.DeviceShareKind;
import com.example.dvely.deviceshare.domain.value.DeviceShareStatus;
import java.time.LocalDateTime;

/**
 * 내 기기 프리뷰 공유 링크 하나.
 *
 * <p>보기 토큰과 내 제어 링크 비밀은 해시만 가진다. 해시라도 로그에 나갈 이유가 없어 {@code toString} 을 두지 않는다.</p>
 */
public final class DeviceShareLink {

    private final Long id;
    private final Long userId;
    private final DeviceShareKind kind;
    private final String targetLabel;
    private final String tokenHash;
    private final String controlSecretHash;
    private final String tokenPrefix;
    private final LocalDateTime expiresAt;
    private final LocalDateTime revokedAt;
    private final LocalDateTime lastViewedAt;
    private final LocalDateTime createdAt;

    public DeviceShareLink(Long id, Long userId, DeviceShareKind kind, String targetLabel, String tokenHash,
                           String controlSecretHash, String tokenPrefix, LocalDateTime expiresAt,
                           LocalDateTime revokedAt, LocalDateTime lastViewedAt, LocalDateTime createdAt) {
        this.id = id;
        this.userId = userId;
        this.kind = kind;
        this.targetLabel = targetLabel;
        this.tokenHash = tokenHash;
        this.controlSecretHash = controlSecretHash;
        this.tokenPrefix = tokenPrefix;
        this.expiresAt = expiresAt;
        this.revokedAt = revokedAt;
        this.lastViewedAt = lastViewedAt;
        this.createdAt = createdAt;
    }

    public static DeviceShareLink newLink(Long userId, DeviceShareKind kind, String targetLabel, String tokenHash,
                                          String controlSecretHash, String tokenPrefix, LocalDateTime expiresAt) {
        return new DeviceShareLink(null, userId, kind, targetLabel, tokenHash, controlSecretHash, tokenPrefix,
                expiresAt, null, null, null);
    }

    public DeviceShareStatus status(LocalDateTime now) {
        if (revokedAt != null) {
            return DeviceShareStatus.REVOKED;
        }
        return now.isBefore(expiresAt) ? DeviceShareStatus.ACTIVE : DeviceShareStatus.EXPIRED;
    }

    public boolean isActive(LocalDateTime now) {
        return status(now) == DeviceShareStatus.ACTIVE;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public DeviceShareKind getKind() { return kind; }
    public String getTargetLabel() { return targetLabel; }
    public String getTokenHash() { return tokenHash; }
    public String getControlSecretHash() { return controlSecretHash; }
    public String getTokenPrefix() { return tokenPrefix; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getRevokedAt() { return revokedAt; }
    public LocalDateTime getLastViewedAt() { return lastViewedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
