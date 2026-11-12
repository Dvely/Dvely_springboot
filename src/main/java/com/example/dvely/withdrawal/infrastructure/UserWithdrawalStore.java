package com.example.dvely.withdrawal.infrastructure;

import com.example.dvely.withdrawal.application.LiveResource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 회원 탈퇴의 DB 쪽(#613). 한 사용자의 개인정보와 서비스 이용 기록을 FK 순서대로 지운다.
 *
 * <p><b>도메인 저장소를 쓰지 않고 이 클래스에 문장을 모았다.</b> 지울 것이 열 개 넘는 도메인에 걸쳐 있고, 순서가 곧
 * 정확성이다(자식부터 지우지 않으면 FK 위반으로 탈퇴 전체가 실패한다). 순서와 대상을 한 파일에서 읽을 수 있어야 새 테이블이
 * 생겼을 때 빠뜨리지 않는다 — {@code UserWithdrawalCoverageTest} 가 스키마를 읽어 {@link #HANDLED_TABLES} 와
 * {@link #CASCADED_TABLES} 밖의 테이블이 사용자·프로젝트를 가리키면 실패한다.</p>
 *
 * <p>"그 사용자의 것" 은 사용자 id 로 바로 묶이는 행과, 그 사용자의 <b>모든</b> 프로젝트(소프트 삭제분 포함)·태스크에 딸린 행이다.
 * 부모를 지우기 전에 자식을 지우므로 하위 질의는 아직 남아 있는 부모 테이블을 읽는다. MySQL 은 지우는 테이블 자신을 하위 질의에서
 * 읽지 못하므로(ERROR 1093) 그런 자리에는 직접 조건을 쓴다.</p>
 *
 * <p>호출자가 트랜잭션을 연다({@code UserWithdrawalService}). 문장이 하나라도 실패하면 전부 되돌아간다 — 반쯤 지운 계정은
 * 남기지 않는다.</p>
 */
@Repository
@RequiredArgsConstructor
public class UserWithdrawalStore {

    private static final String PROJECTS = "select project_id from projects where user_id = :userId";
    private static final String TASKS = "select task_id from agent_runs where user_id = :userId or project_id in (" + PROJECTS + ")";
    private static final String CLOUD_CONNECTIONS = "select cloud_connection_id from cloud_connections where user_id = :userId";

    /**
     * 지우는 순서. 위가 먼저다. 각 줄의 FK 이유는 옆에 적는다.
     */
    private static final List<Step> STEPS = List.of(
            // FK 없음 — 태스크 id 로 묶인다. 이 테이블(V70)은 콜레이션을 적지 않고 만들어져 서버 기본값을 따른다 — MySQL 8.0
            // 기본(utf8mb4_0900_ai_ci)인 서버에서는 agent_runs.task_id(utf8mb4_unicode_ci)와 그냥 비교하면
            // "Illegal mix of collations" 로 탈퇴 전체가 실패한다(CI 에서 실제로 났다). 비교 콜레이션을 못박는다.
            new Step("server_pushed_commits",
                    "delete from server_pushed_commits where task_id collate utf8mb4_unicode_ci in (" + TASKS + ")"),
            // FK 없음. 처리방침상 탈퇴 시 파기 대상이다(회원 번호·프로젝트 번호가 실린다)
            new Step("llm_usage", "delete from llm_usage where user_id = :userId or project_id in (" + PROJECTS + ")"
                    + " or task_id in (" + TASKS + ")"),
            // FK 없음. resource_id·detail 에 저장소 이름 같은 식별자가 실리므로 익명화가 아니라 지운다
            new Step("audit_logs", "delete from audit_logs where actor_user_id = :userId or project_id in (" + PROJECTS + ")"
                    + " or task_id in (" + TASKS + ")"),
            // FK 없음. 사용자 저장소의 GitHub 이벤트 원문이다. 처리된 것도 7일은 남아 있으므로 저장소 이름으로 찾아 지운다
            new Step("webhook_deliveries", """
                    delete from webhook_deliveries
                     where (case when json_valid(convert(payload using utf8mb4))
                                 then convert(json_unquote(json_extract(convert(payload using utf8mb4), '$.repository.full_name'))
                                              using utf8mb4) collate utf8mb4_unicode_ci
                            end) in (select source_repository from projects
                                      where user_id = :userId and source_repository is not null
                                     union
                                     select deployment_repository from projects
                                      where user_id = :userId and deployment_repository is not null)
                    """),
            // preview_sessions·agent_runs 를 NO ACTION 으로 가리키므로 그 둘보다 먼저
            new Step("project_changes", "delete from project_changes where user_id = :userId or project_id in (" + PROJECTS + ")"
                    + " or task_id in (" + TASKS + ")"),
            // agent_runs 를 NO ACTION 으로 가리킨다
            new Step("preview_sessions", "delete from preview_sessions where user_id = :userId or project_id in (" + PROJECTS + ")"
                    + " or task_id in (" + TASKS + ")"),
            new Step("agent_run_events", "delete from agent_run_events where task_id in (" + TASKS + ")"),
            // users 를 actor 로 NO ACTION 으로 가리킨다(프로젝트 삭제의 CASCADE 로도 지워지지만 actor 쪽을 먼저 끊는다)
            new Step("project_infrastructure_setting_changes",
                    "delete from project_infrastructure_setting_changes where actor_user_id = :userId or project_id in (" + PROJECTS + ")"),
            new Step("approvals", "delete from approvals where user_id = :userId or project_id in (" + PROJECTS + ")"
                    + " or task_id in (" + TASKS + ")"),
            new Step("agent_runs", "delete from agent_runs where user_id = :userId or project_id in (" + PROJECTS + ")"),
            // chat_messages 는 ON DELETE CASCADE 로 함께 지워진다
            new Step("chat_sessions", "delete from chat_sessions where user_id = :userId or project_id in (" + PROJECTS + ")"),
            new Step("deployment_failure_analyses", "delete from deployment_failure_analyses where user_id = :userId"
                    + " or history_id in (select history_id from deployment_histories"
                    + " where user_id = :userId or project_id in (" + PROJECTS + "))"),
            // 자기 참조(retried_from_history_id)는 ON DELETE SET NULL 이다
            new Step("deployment_histories", "delete from deployment_histories where user_id = :userId or project_id in (" + PROJECTS + ")"),
            new Step("environment_variable_histories",
                    "delete from environment_variable_histories where actor_user_id = :userId or project_id in (" + PROJECTS + ")"),
            new Step("domains", "delete from domains where project_id in (" + PROJECTS + ")"),
            new Step("project_approval_policies", "delete from project_approval_policies where project_id in (" + PROJECTS + ")"),
            // FK 없음
            new Step("provisioned_servers", "delete from provisioned_servers where project_id in (" + PROJECTS + ")"),
            // FK 없음 — 클라우드 연결 id 로 묶인다
            new Step("cdn_deletions", "delete from cdn_deletions where cloud_connection_id in (" + CLOUD_CONNECTIONS + ")"),
            // environment_variables·preview_runtime_configs·project_budget_settings·project_cloud_connection_settings·
            // project_infrastructure_settings·provisioned_databases 는 ON DELETE CASCADE 로 함께 지워진다
            new Step("projects", "delete from projects where user_id = :userId"),
            new Step("cloud_connection_verification_jobs", "delete from cloud_connection_verification_jobs where user_id = :userId"),
            new Step("cloud_connections", "delete from cloud_connections where user_id = :userId"),
            new Step("refresh_tokens", "delete from refresh_tokens where user_id = :userId"),
            // ai_provider_credentials·api_tokens 는 ON DELETE CASCADE 로 함께 지워진다
            new Step("users", "delete from users where user_id = :userId")
    );

    /** {@link #STEPS} 가 직접 지우는 테이블. */
    public static final Set<String> HANDLED_TABLES = STEPS.stream().map(Step::table)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** 직접 지우지 않고 부모의 ON DELETE CASCADE 로 함께 지워지는 테이블. */
    public static final Set<String> CASCADED_TABLES = Set.of(
            "chat_messages",                       // chat_sessions
            "ai_provider_credentials",             // users
            "api_tokens",                          // users
            "environment_variables",               // projects
            "preview_runtime_configs",             // projects
            "project_budget_settings",             // projects
            "project_cloud_connection_settings",   // projects, cloud_connections
            "project_infrastructure_settings",     // projects
            "provisioned_databases",               // projects
            "device_share_links"                   // users (#652). 연결된 공유는 DeviceShareService 가 15초 안에 끊는다
    );

    private final EntityManager entityManager;

    /**
     * 사용자 행을 잠근다. 탈퇴 판정(살아 있는 자원)과 삭제 사이에 같은 사용자의 다른 결정이 끼어들지 않게 한다.
     *
     * @return 사용자가 있으면 {@code true}
     */
    public boolean lockUser(long userId) {
        return !entityManager.createNativeQuery("select user_id from users where user_id = :userId for update")
                .setParameter("userId", userId)
                .getResultList()
                .isEmpty();
    }

    /**
     * 사용자 AWS 계정에 남은 자원. 소프트 삭제한 프로젝트의 것도 센다 — 화면에서 사라졌어도 과금은 계속된다.
     *
     * <ul>
     *   <li>서버: 진행 중(QUEUED·BUILDING·PROVISIONING·RUNNING)이거나, 종료되지 않았는데 인스턴스·EIP 가 기록돼 있다.
     *       승인 대기(PENDING)나 만들기 전에 실패한 것(인스턴스 없음)은 남은 자원이 아니다</li>
     *   <li>DB: 사용자 AWS 의 RDS·Docker DB 중 생성 대기·생성 중·사용 중. 프리뷰 안 DB(LOCAL)는 우리 호스트의 것이다</li>
     *   <li>CDN: CloudFront 배포가 기록된 도메인, 그리고 아직 끝나지 않은 CloudFront·인증서 정리 작업</li>
     *   <li>배포: 대기(PENDING)·진행 중(IN_PROGRESS). 워커가 이미 집어 간 배포는 행을 지워도 멈추지 않고 사용자 AWS 에
     *       S3 버킷 같은 자원을 만든 뒤에야 끝난다. 멈춘 진행 중 배포는 회수 워커가 정해진 시간 안에 닫는다</li>
     * </ul>
     */
    public List<LiveResource> findLiveResources(long userId) {
        List<LiveResource> live = new ArrayList<>();
        for (Object[] row : rows("""
                select server_id, project_id, coalesce(instance_id, public_host), status from provisioned_servers
                 where project_id in (%s)
                   and (status in ('QUEUED', 'BUILDING', 'PROVISIONING', 'RUNNING')
                        or (status <> 'TERMINATED' and (instance_id is not null or elastic_ip_allocation_id is not null)))
                 order by server_id
                """.formatted(PROJECTS), userId)) {
            live.add(new LiveResource(LiveResource.Type.SERVER, number(row[0]), number(row[1]), (String) row[2], (String) row[3]));
        }
        for (Object[] row : rows("""
                select database_id, project_id, concat(method, ' ', engine), status from provisioned_databases
                 where project_id in (%s)
                   and method in ('RDS', 'DOCKER') and status in ('PENDING', 'PROVISIONING', 'READY')
                 order by database_id
                """.formatted(PROJECTS), userId)) {
            live.add(new LiveResource(LiveResource.Type.DATABASE, number(row[0]), number(row[1]), (String) row[2], (String) row[3]));
        }
        for (Object[] row : rows("""
                select domain_id, project_id, domain_name, status from domains
                 where project_id in (%s) and cloudfront_distribution_id is not null
                 order by domain_id
                """.formatted(PROJECTS), userId)) {
            live.add(new LiveResource(LiveResource.Type.CDN, number(row[0]), number(row[1]), (String) row[2], (String) row[3]));
        }
        for (Object[] row : rows("""
                select deletion_id, hostname from cdn_deletions
                 where cloud_connection_id in (%s)
                 order by deletion_id
                """.formatted(CLOUD_CONNECTIONS), userId)) {
            live.add(new LiveResource(LiveResource.Type.CDN_DELETION, number(row[0]), null, (String) row[1], "PENDING"));
        }
        for (Object[] row : rows("""
                select history_id, project_id, title, status from deployment_histories
                 where (user_id = :userId or project_id in (%s)) and status in ('PENDING', 'IN_PROGRESS')
                 order by history_id
                """.formatted(PROJECTS), userId)) {
            live.add(new LiveResource(LiveResource.Type.DEPLOYMENT, number(row[0]), number(row[1]), (String) row[2], (String) row[3]));
        }
        return live;
    }

    /**
     * 아직 끝나지 않은 에이전트 런. 탈퇴 전에 취소한다 — 행을 지운 뒤에는 실행기가 취소를 알아챌 길이 없다(런이 없으면
     * "취소되지 않음" 으로 읽는다).
     */
    @SuppressWarnings("unchecked")
    public List<String> findUnfinishedTaskIds(long userId) {
        return (List<String>) entityManager.createNativeQuery("""
                        select task_id from agent_runs
                         where user_id = :userId and status not in ('DONE', 'FAILED', 'CANCELLED')
                         order by created_at
                        """)
                .setParameter("userId", userId)
                .getResultList();
    }

    /**
     * 우리 호스트에 띄운 프리뷰 테스트 DB 컨테이너(LOCAL). 사용자 라벨이 없어 행에서 찾는다 — 행이 지워지면 만료 회수 워커도
     * 더는 찾지 못하므로 탈퇴가 직접 지운다.
     */
    @SuppressWarnings("unchecked")
    public List<String> findLocalDatabaseContainerIds(long userId) {
        return (List<String>) entityManager.createNativeQuery("""
                        select resource_id from provisioned_databases
                         where project_id in (%s) and method = 'LOCAL' and resource_id is not null
                           and status not in ('FAILED', 'EXPIRED')
                         order by database_id
                        """.formatted(PROJECTS))
                .setParameter("userId", userId)
                .getResultList();
    }

    /** {@link #STEPS} 를 순서대로 실행한다. 호출자의 트랜잭션 안에서 돈다. */
    public void deleteAll(long userId) {
        for (Step step : STEPS) {
            entityManager.createNativeQuery(step.sql()).setParameter("userId", userId).executeUpdate();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> rows(String sql, long userId) {
        Query query = entityManager.createNativeQuery(sql).setParameter("userId", userId);
        return (List<Object[]>) query.getResultList();
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private record Step(String table, String sql) {}
}
