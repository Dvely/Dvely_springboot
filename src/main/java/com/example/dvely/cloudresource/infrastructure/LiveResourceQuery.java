package com.example.dvely.cloudresource.infrastructure;

import com.example.dvely.cloudresource.application.LiveResource;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 사용자 AWS 계정에 아직 살아 있는 자원을 찾는다. 회원 탈퇴(#613)는 한 사용자의 것을, 프로젝트 삭제(#595)는 한 프로젝트의 것을
 * 본다. 무엇을 "살아 있다" 고 보는지는 아래 조건 한 벌을 둘이 같이 쓴다 — 갈라지면 한쪽만 자원을 놓친다.
 *
 * <ul>
 *   <li>서버: 진행 중(QUEUED·BUILDING·PROVISIONING·RUNNING)이거나, 종료되지 않았는데 인스턴스·EIP 가 기록돼 있다.
 *       승인 대기(PENDING)나 만들기 전에 실패한 것(인스턴스 없음)은 남은 자원이 아니다</li>
 *   <li>DB: 사용자 AWS 의 RDS·Docker DB 중 생성 대기·생성 중·사용 중. 프리뷰 안 DB(LOCAL)는 우리 호스트의 것이다</li>
 *   <li>CDN: CloudFront 배포가 기록된 도메인, 그리고 아직 끝나지 않은 CloudFront·인증서 정리 작업</li>
 *   <li>배포: 대기(PENDING)·진행 중(IN_PROGRESS). 워커가 이미 집어 간 배포는 행을 지워도 멈추지 않고 사용자 AWS 에
 *       S3 버킷 같은 자원을 만든 뒤에야 끝난다. 멈춘 진행 중 배포는 회수 워커가 정해진 시간 안에 닫는다</li>
 * </ul>
 *
 * <p>호출자의 트랜잭션 안에서 읽는다. 잠그지 않는다 — 판정과 삭제 사이를 지켜야 하면 호출자가 잠근다.</p>
 */
@Repository
@RequiredArgsConstructor
public class LiveResourceQuery {

    private static final String USER_PROJECTS = "select project_id from projects where user_id = :userId";
    private static final String USER_CLOUD_CONNECTIONS =
            "select cloud_connection_id from cloud_connections where user_id = :userId";

    private static final String LIVE_SERVER = """
            (status in ('QUEUED', 'BUILDING', 'PROVISIONING', 'RUNNING')
             or (status <> 'TERMINATED' and (instance_id is not null or elastic_ip_allocation_id is not null)))""";
    private static final String LIVE_DATABASE =
            "method in ('RDS', 'DOCKER') and status in ('PENDING', 'PROVISIONING', 'READY')";
    private static final String LIVE_DEPLOYMENT = "status in ('PENDING', 'IN_PROGRESS')";

    private final EntityManager entityManager;

    /**
     * 한 사용자의 것. 소프트 삭제한 프로젝트의 것도 센다 — 화면에서 사라졌어도 과금은 계속된다. 다섯 종류를 모두 본다.
     */
    public List<LiveResource> ofUser(long userId) {
        Map<String, Object> params = Map.of("userId", userId);
        List<LiveResource> live = new ArrayList<>();
        live.addAll(servers("project_id in (" + USER_PROJECTS + ")", params));
        live.addAll(databases("project_id in (" + USER_PROJECTS + ")", params));
        for (Object[] row : rows("""
                select domain_id, project_id, domain_name, status from domains
                 where project_id in (%s) and cloudfront_distribution_id is not null
                 order by domain_id
                """.formatted(USER_PROJECTS), params)) {
            live.add(new LiveResource(LiveResource.Type.CDN, number(row[0]), number(row[1]), (String) row[2], (String) row[3]));
        }
        for (Object[] row : rows("""
                select deletion_id, hostname from cdn_deletions
                 where cloud_connection_id in (%s)
                 order by deletion_id
                """.formatted(USER_CLOUD_CONNECTIONS), params)) {
            live.add(new LiveResource(LiveResource.Type.CDN_DELETION, number(row[0]), null, (String) row[1], "PENDING"));
        }
        live.addAll(deployments("(user_id = :userId or project_id in (" + USER_PROJECTS + "))", params));
        return live;
    }

    /**
     * 한 프로젝트의 서버·DB·배포. CDN 은 보지 않는다 — 프로젝트 삭제가 S3 프론트 도메인의 CloudFront·인증서 정리를 스스로
     * 큐잉하기 때문이다({@code DomainBindingCommandService.cleanupProjectS3Domains}). 서버·DB 는 삭제가 건드리지 않으므로
     * 남으면 주인을 잃는다.
     */
    public List<LiveResource> ofProject(long projectId) {
        Map<String, Object> params = Map.of("projectId", projectId);
        List<LiveResource> live = new ArrayList<>();
        live.addAll(servers("project_id = :projectId", params));
        live.addAll(databases("project_id = :projectId", params));
        live.addAll(deployments("project_id = :projectId", params));
        return live;
    }

    private List<LiveResource> servers(String scope, Map<String, Object> params) {
        return rows("""
                select server_id, project_id, coalesce(instance_id, public_host), status from provisioned_servers
                 where %s and %s
                 order by server_id
                """.formatted(scope, LIVE_SERVER), params).stream()
                .map(row -> new LiveResource(LiveResource.Type.SERVER, number(row[0]), number(row[1]), (String) row[2],
                        (String) row[3]))
                .toList();
    }

    private List<LiveResource> databases(String scope, Map<String, Object> params) {
        return rows("""
                select database_id, project_id, concat(method, ' ', engine), status from provisioned_databases
                 where %s and %s
                 order by database_id
                """.formatted(scope, LIVE_DATABASE), params).stream()
                .map(row -> new LiveResource(LiveResource.Type.DATABASE, number(row[0]), number(row[1]), (String) row[2],
                        (String) row[3]))
                .toList();
    }

    private List<LiveResource> deployments(String scope, Map<String, Object> params) {
        return rows("""
                select history_id, project_id, title, status from deployment_histories
                 where %s and %s
                 order by history_id
                """.formatted(scope, LIVE_DEPLOYMENT), params).stream()
                .map(row -> new LiveResource(LiveResource.Type.DEPLOYMENT, number(row[0]), number(row[1]), (String) row[2],
                        (String) row[3]))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> rows(String sql, Map<String, Object> params) {
        var query = entityManager.createNativeQuery(sql);
        params.forEach(query::setParameter);
        return (List<Object[]>) query.getResultList();
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }
}
