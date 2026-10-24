package com.example.dvely.cloudconnection.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.cloudconnection.application.port.out.CloudConnectionResourceUsagePort.ResourceUsage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 네이티브 카운트가 실제 스키마에서 도는지 본다(#495). 단위 테스트는 포트를 mock 하므로 컬럼 이름이
 * 틀리거나 상태 문자열이 어긋나도 통과한다 — 그러면 해제 가드는 늘 "자원 없음" 으로 열린다.
 *
 * <p>외래키가 없는 서버·CDN 큐 행을 넣어 상태 거르기까지 확인한다. DB·도메인 행은 프로젝트 외래키가
 * 있어 넣지 않고 쿼리가 오류 없이 0 을 내는 것만 본다. 트랜잭션은 끝나면 되돌린다.
 */
@SpringBootTest
@Transactional
class CloudConnectionResourceUsageAdapterIntegrationTest {

    private static final long CONNECTION_ID = 987_654_321L;

    @Autowired
    private CloudConnectionResourceUsageAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void countsLiveServersAndPendingCdnDeletionsButNotTerminatedServers() {
        assertThat(adapter.usageOf(CONNECTION_ID)).isEqualTo(new ResourceUsage(0, 0, 0, 0));

        insertServer("RUNNING");
        insertServer("FAILED");
        insertServer("TERMINATED");
        jdbcTemplate.update("insert into cdn_deletions (cloud_connection_id, distribution_id) values (?, ?)",
                CONNECTION_ID, "E2TESTDIST");

        ResourceUsage usage = adapter.usageOf(CONNECTION_ID);
        // FAILED 도 센다 — 생성이 중간에 실패해도 인스턴스가 남을 수 있다.
        assertThat(usage.servers()).isEqualTo(2);
        assertThat(usage.pendingCdnDeletions()).isEqualTo(1);
        assertThat(usage.databases()).isZero();
        assertThat(usage.cdnDomains()).isZero();
    }

    private void insertServer(String status) {
        jdbcTemplate.update("insert into provisioned_servers"
                        + " (project_id, instance_type, status, port, cloud_connection_id, created_at, updated_at)"
                        + " values (?, 't3.micro', ?, 8080, ?, now(), now())",
                999_999_999L, status, CONNECTION_ID);
    }
}
