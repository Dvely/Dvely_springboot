package com.example.dvely.cloudconnection.infrastructure.persistence.repository;

import com.example.dvely.cloudconnection.domain.repository.CloudConnectionSummaryView;
import com.example.dvely.cloudconnection.infrastructure.persistence.entity.CloudConnectionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataCloudConnectionRepository extends JpaRepository<CloudConnectionEntity, Long> {

    List<CloudConnectionEntity> findAllByOwnerUserIdOrderByCreatedAtDesc(Long ownerUserId);

    // U6 6-5: 목록은 비밀 컬럼을 읽지 않는다. 세 개의 MEDIUMTEXT 는 is not null 로만 묻는다 —
    // 엔티티로 읽으면 @Convert(AesEncryptor) 가 행마다 복호화를 돌리는데 응답에는 boolean 세 개만
    // 나간다. is not null 은 값이 아니라 널 여부만 보므로 off-page TEXT 본문을 읽지 않는다.
    @Query("""
            select new com.example.dvely.cloudconnection.domain.repository.CloudConnectionSummaryView(
                       c.id, c.provider, c.displayName, c.accountId, c.region, c.roleArn,
                       c.awsCredentialType, c.accessKeyId,
                       case when c.secretAccessKey is not null then true else false end,
                       case when c.sessionToken is not null then true else false end,
                       c.gcpCredentialType,
                       case when c.serviceAccountKeyJson is not null then true else false end,
                       c.gcpProjectId, c.serviceAccountEmail, c.status, c.lastCheckedAt,
                       c.createdAt, c.updatedAt)
            from CloudConnectionEntity c
            where c.ownerUserId = :ownerUserId
            order by c.createdAt desc, c.id desc
            """)
    List<CloudConnectionSummaryView> findSummariesByOwnerUserId(@Param("ownerUserId") Long ownerUserId);

    // provider 는 String 컬럼(CloudConnectionEntity:35).
    List<CloudConnectionEntity> findAllByProvider(String provider);

    Optional<CloudConnectionEntity> findByIdAndOwnerUserId(Long id, Long ownerUserId);
}
