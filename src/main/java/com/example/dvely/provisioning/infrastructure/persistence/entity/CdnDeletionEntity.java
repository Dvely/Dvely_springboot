package com.example.dvely.provisioning.infrastructure.persistence.entity;

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
import org.hibernate.annotations.CreationTimestamp;

/**
 * CloudFront 배포 정리(리프) 큐 한 행. CloudFront 배포 삭제는 disable → Deployed 대기 → delete 의
 * 다단계·수 분 작업이라 도메인 삭제 시점에 동기로 못 끝낸다. 삭제 시 이 큐에 넣고, {@code CdnDeletionReaper}
 * 가 Deployed 되면 배포·인증서를 지운다(고아 자원 방지). 도메인 행과 분리돼 도메인은 즉시 하드삭제된다.
 */
@Entity
@Table(name = "cdn_deletions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CdnDeletionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "deletion_id")
    private Long id;

    @Column(name = "cloud_connection_id", nullable = false)
    private Long cloudConnectionId;

    @Column(name = "distribution_id", nullable = false)
    private String distributionId;

    @Column(name = "certificate_arn")
    private String certificateArn;

    @Column(name = "hostname")
    private String hostname;

    @Column(name = "last_error")
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    private CdnDeletionEntity(Long cloudConnectionId, String distributionId,
                             String certificateArn, String hostname) {
        this.cloudConnectionId = cloudConnectionId;
        this.distributionId = distributionId;
        this.certificateArn = certificateArn;
        this.hostname = hostname;
    }

    public static CdnDeletionEntity of(Long cloudConnectionId, String distributionId,
                                       String certificateArn, String hostname) {
        return new CdnDeletionEntity(cloudConnectionId, distributionId, certificateArn, hostname);
    }

    public void recordError(String message) {
        this.lastError = message == null ? null : (message.length() > 500 ? message.substring(0, 500) : message);
    }
}
