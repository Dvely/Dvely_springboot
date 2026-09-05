package com.example.dvely.provisioning.infrastructure.persistence.repository;

import com.example.dvely.provisioning.infrastructure.persistence.entity.CdnDeletionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** CloudFront 배포 정리(리프) 큐. 리퍼 워커가 폴링해 Deployed 된 배포를 지운다. */
public interface SpringDataCdnDeletionRepository extends JpaRepository<CdnDeletionEntity, Long> {
}
