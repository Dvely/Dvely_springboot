package com.example.dvely.template.infrastructure.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 카탈로그의 마지막 성공 스냅샷 (#395). 단일 행이다.
 *
 * 재시작이 warm 하게 시작하기 위한 것이다. 메모리 캐시는 재시작을 넘지 못하고, 배포가 곧
 * 재시작이므로 "배포 직후 + GH Pages 불통"이 겹치면 카탈로그 API 가 503 이 된다.
 */
@Entity
@Table(name = "template_catalog_snapshot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TemplateCatalogSnapshotEntity {

    /** 이력을 쌓지 않는다. 항상 이 값 하나만 쓴다. */
    public static final byte SINGLETON_ID = 1;

    @Id
    @Column(name = "catalog_id")
    private Byte id;

    @Column(name = "payload", nullable = false, columnDefinition = "LONGTEXT")
    private String payload;

    /** 네트워크에서 실제로 받은 시각. 복원할지 말지는 이 값의 나이로 판단한다. */
    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @Column(name = "source_url", nullable = false, length = 500)
    private String sourceUrl;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static TemplateCatalogSnapshotEntity of(String payload, Instant fetchedAt, String sourceUrl) {
        TemplateCatalogSnapshotEntity entity = new TemplateCatalogSnapshotEntity();
        entity.id = SINGLETON_ID;
        entity.payload = payload;
        entity.fetchedAt = fetchedAt;
        entity.sourceUrl = sourceUrl;
        entity.updatedAt = Instant.now();
        return entity;
    }
}
