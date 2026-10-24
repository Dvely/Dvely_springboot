package com.example.dvely.cloudconnection.infrastructure.persistence.repository;

import com.example.dvely.cloudconnection.application.port.out.CloudConnectionResourceUsagePort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

/**
 * 다른 모듈의 자원 테이블을 읽기만 한다. 그 모듈의 저장소를 고치지 않으려고 네이티브 카운트로 둔다.
 *
 * <p>"살아 있다"의 기준은 삭제 경로가 끝난 상태가 아닌 것이다. FAILED 도 센다 — 생성이 중간에
 * 실패해도 AWS 에 인스턴스가 남을 수 있고, 그 정리 역시 이 연결의 자격으로 한다. CloudFront 도메인은
 * 연결 id 를 따로 저장하지 않고 프로젝트가 고른 연결로 다루므로 그 선택을 거쳐 센다.
 */
@Component
public class CloudConnectionResourceUsageAdapter implements CloudConnectionResourceUsagePort {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public ResourceUsage usageOf(Long cloudConnectionId) {
        return new ResourceUsage(
                count("select count(*) from provisioned_databases"
                        + " where cloud_connection_id = ?1 and status <> 'EXPIRED'", cloudConnectionId),
                count("select count(*) from provisioned_servers"
                        + " where cloud_connection_id = ?1 and status <> 'TERMINATED'", cloudConnectionId),
                count("select count(*) from domains d"
                        + " join project_cloud_connection_settings s on s.project_id = d.project_id"
                        + " where s.cloud_connection_id = ?1 and d.cloudfront_distribution_id is not null",
                        cloudConnectionId),
                count("select count(*) from cdn_deletions where cloud_connection_id = ?1", cloudConnectionId)
        );
    }

    private long count(String sql, Long cloudConnectionId) {
        Object result = entityManager.createNativeQuery(sql)
                .setParameter(1, cloudConnectionId)
                .getSingleResult();
        return ((Number) result).longValue();
    }
}
