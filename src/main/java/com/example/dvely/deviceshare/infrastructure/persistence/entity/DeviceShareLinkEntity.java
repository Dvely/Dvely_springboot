package com.example.dvely.deviceshare.infrastructure.persistence.entity;

import com.example.dvely.deviceshare.domain.model.DeviceShareLink;
import com.example.dvely.deviceshare.domain.value.DeviceShareKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** JPA row for {@code device_share_links}. 해시만 담지만 로그에 나갈 이유가 없어 {@code @ToString} 을 두지 않는다. */
@Entity
@Table(name = "device_share_links")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeviceShareLinkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "share_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    @Column(name = "target_label", nullable = false, length = 255)
    private String targetLabel;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "control_secret_hash", nullable = false, length = 64)
    private String controlSecretHash;

    @Column(name = "token_prefix", nullable = false, length = 8)
    private String tokenPrefix;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "last_viewed_at")
    private LocalDateTime lastViewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static DeviceShareLinkEntity from(DeviceShareLink link, LocalDateTime createdAt) {
        DeviceShareLinkEntity e = new DeviceShareLinkEntity();
        e.userId = link.getUserId();
        e.kind = link.getKind().name();
        e.targetLabel = link.getTargetLabel();
        e.tokenHash = link.getTokenHash();
        e.controlSecretHash = link.getControlSecretHash();
        e.tokenPrefix = link.getTokenPrefix();
        e.expiresAt = link.getExpiresAt();
        e.createdAt = createdAt;
        return e;
    }

    public DeviceShareLink toDomain() {
        return new DeviceShareLink(id, userId, DeviceShareKind.valueOf(kind), targetLabel, tokenHash,
                controlSecretHash, tokenPrefix, expiresAt, revokedAt, lastViewedAt, createdAt);
    }
}
