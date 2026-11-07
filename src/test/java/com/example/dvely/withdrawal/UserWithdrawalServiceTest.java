package com.example.dvely.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dvely.withdrawal.application.LiveResource;
import com.example.dvely.withdrawal.application.UserWithdrawalService;
import com.example.dvely.withdrawal.application.WithdrawalBlockedException;
import com.example.dvely.withdrawal.infrastructure.UserWithdrawalStore;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * 회원 탈퇴(#613 PR-1)를 실 MySQL 에서 본다.
 *
 * <p>두 사용자에게 탈퇴가 다루는 <b>모든</b> 테이블의 행을 심고, 한 사람만 탈퇴시킨다. 그 사람을 가리키던 행은 테이블마다 0건,
 * 다른 사람의 행은 그대로여야 한다. 테이블 목록 자체가 스키마와 맞는지는 {@link UserWithdrawalCoverageTest} 가 본다 — 이
 * 테스트는 그 목록의 모든 테이블에 실제로 행이 있는 상태에서 FK 순서가 맞는지(삭제가 FK 위반 없이 끝나는지)를 증명한다.</p>
 */
@SpringBootTest
class UserWithdrawalServiceTest {

    private static final String MARK = "wd613-";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserWithdrawalService service;

    private final List<Long> userIds = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanUp() {
        // 남은 픽스처가 있으면 탈퇴 경로로 치운다(살아 있는 자원 픽스처는 먼저 끈다)
        List<Long> leftovers = jdbc.queryForList("select user_id from users where github_user_id like ?", Long.class, MARK + "%");
        for (Long id : leftovers) {
            jdbc.update("update provisioned_servers set status = 'TERMINATED', instance_id = null, elastic_ip_allocation_id = null"
                    + " where project_id in (select project_id from projects where user_id = ?)", id);
            jdbc.update("update provisioned_databases set status = 'FAILED'"
                    + " where project_id in (select project_id from projects where user_id = ?)", id);
            jdbc.update("update domains set cloudfront_distribution_id = null"
                    + " where project_id in (select project_id from projects where user_id = ?)", id);
            jdbc.update("delete from cdn_deletions where cloud_connection_id in"
                    + " (select cloud_connection_id from cloud_connections where user_id = ?)", id);
            service.withdraw(id);
        }
        jdbc.update("delete from webhook_deliveries where delivery_id like ?", MARK + "%");
    }

    @Test
    void deletesEveryRowOfTheUserAndNothingOfAnyoneElse() {
        Fixture withdrawn = seed("a");
        Fixture other = seed("b");
        Map<String, Long> otherBefore = counts(other);
        assertThat(counts(withdrawn).values()).as("픽스처가 모든 테이블에 행을 심었다").allMatch(count -> count > 0);

        service.withdraw(withdrawn.userId);

        assertThat(counts(withdrawn)).as("탈퇴한 사용자의 행").allSatisfy((table, count) -> assertThat(count).as(table).isZero());
        assertThat(counts(other)).as("다른 사용자의 행").isEqualTo(otherBefore);
        // 같은 GitHub 계정으로 다시 로그인하면 새 사용자로 시작한다(기존 행이 없다)
        assertThat(jdbc.queryForObject("select count(*) from users where github_user_id = ?", Long.class,
                withdrawn.githubUserId)).isZero();
    }

    @Test
    void aSecondWithdrawalOfTheSameUserDoesNothing() {
        Fixture withdrawn = seed("a");
        service.withdraw(withdrawn.userId);

        service.withdraw(withdrawn.userId);

        assertThat(counts(withdrawn).values()).allMatch(count -> count == 0);
    }

    @Test
    void liveAwsResourcesBlockTheWithdrawalAndNothingIsDeleted() {
        Fixture user = seed("a");
        long server = insert("""
                insert into provisioned_servers (project_id, instance_type, status, instance_id, elastic_ip_allocation_id, port,
                                                 created_at, updated_at)
                values (?, 't3.micro', 'RUNNING', 'i-0abc', 'eipalloc-1', 8080, now(), now())
                """, user.deletedProjectId);   // 소프트 삭제한 프로젝트의 서버도 센다 — 화면에서 사라졌어도 과금은 계속된다
        long failedWithInstance = insert("""
                insert into provisioned_servers (project_id, instance_type, status, instance_id, port, created_at, updated_at)
                values (?, 't3.micro', 'FAILED', 'i-0def', 8080, now(), now())
                """, user.projectId);
        insert("""
                insert into provisioned_servers (project_id, instance_type, status, port, created_at, updated_at)
                values (?, 't3.micro', 'PENDING', 8080, now(), now())
                """, user.projectId);   // 승인 대기 — 아직 아무것도 만들지 않았다
        long rds = insert("insert into provisioned_databases (project_id, method, engine, status) values (?, 'RDS', 'MYSQL', 'READY')",
                user.projectId);
        insert("insert into provisioned_databases (project_id, method, engine, status) values (?, 'LOCAL', 'MYSQL', 'READY')",
                user.projectId);   // 프리뷰 안 DB 는 우리 호스트의 것이다
        long cdnDomain = insert("insert into domains (project_id, domain_name, cloudfront_distribution_id) values (?, ?, 'E123')",
                user.projectId, MARK + "cdn.example.com");
        long cdnDeletion = insert("insert into cdn_deletions (cloud_connection_id, distribution_id, hostname) values (?, 'E999', ?)",
                user.cloudConnectionId, MARK + "old.example.com");
        Map<String, Long> before = counts(user);

        assertThatThrownBy(() -> service.withdraw(user.userId))
                .isInstanceOfSatisfying(WithdrawalBlockedException.class, e -> assertThat(e.liveResources())
                        .extracting(LiveResource::type, LiveResource::id)
                        .containsExactlyInAnyOrder(
                                org.assertj.core.groups.Tuple.tuple(LiveResource.Type.SERVER, server),
                                org.assertj.core.groups.Tuple.tuple(LiveResource.Type.SERVER, failedWithInstance),
                                org.assertj.core.groups.Tuple.tuple(LiveResource.Type.DATABASE, rds),
                                org.assertj.core.groups.Tuple.tuple(LiveResource.Type.CDN, cdnDomain),
                                org.assertj.core.groups.Tuple.tuple(LiveResource.Type.CDN_DELETION, cdnDeletion)));

        assertThat(counts(user)).as("막혔으면 아무것도 지우지 않는다").isEqualTo(before);
        assertThat(service.findLiveResources(user.userId)).hasSize(5);
    }

    @Test
    void aWebhookDeliveryThatIsNotJsonDoesNotBreakTheWithdrawal() {
        Fixture user = seed("a");
        String broken = MARK + "broken-" + System.nanoTime();
        jdbc.update("insert into webhook_deliveries (delivery_id, event_type, payload) values (?, 'push', ?)",
                broken, "not json at all".getBytes(StandardCharsets.UTF_8));

        service.withdraw(user.userId);

        assertThat(jdbc.queryForObject("select count(*) from webhook_deliveries where delivery_id = ?", Long.class, broken))
                .as("저장소를 알 수 없는 배달은 남는다(7일 보존 스윕이 지운다)").isOne();
    }

    // ── fixture ──────────────────────────────────────────────────────────────

    /** 한 사용자에게 딸린 식별자. 탈퇴 뒤 "이 사용자를 가리키는 행" 을 셀 때 쓴다. */
    private static final class Fixture {
        long userId;
        String githubUserId;
        long projectId;
        long deletedProjectId;
        long chatSessionId;
        String taskId;
        long approvalId;
        String previewSessionId;
        long cloudConnectionId;
        long historyId;
        String commitSha;
        String deliveryId;
        String unrelatedDeliveryId;
        final List<Long> projectIds = new ArrayList<>();
    }

    private Fixture seed(String name) {
        Fixture f = new Fixture();
        String unique = name + "-" + System.nanoTime();
        f.githubUserId = MARK + unique;
        f.userId = insert("insert into users (github_user_id, user_name) values (?, ?)", f.githubUserId, MARK + name);
        userIds.add(f.userId);
        String repo = "wd613-owner-" + unique + "/app";
        f.projectId = insert("insert into projects (user_id, project_name, source_repository) values (?, ?, ?)",
                f.userId, MARK + name, repo);
        f.deletedProjectId = insert("insert into projects (user_id, project_name, is_deleted) values (?, ?, 1)",
                f.userId, MARK + name + "-deleted");
        f.projectIds.addAll(List.of(f.projectId, f.deletedProjectId));

        f.chatSessionId = insert("insert into chat_sessions (user_id, project_id) values (?, ?)", f.userId, f.projectId);
        insert("insert into chat_messages (chat_session_id, role, content) values (?, 'user', '안녕')", f.chatSessionId);
        f.taskId = MARK + "task-" + unique;
        jdbc.update("insert into agent_runs (task_id, user_id, project_id, chat_session_id, status, plan_json) values (?, ?, ?, ?, 'DONE', '{}')",
                f.taskId, f.userId, f.projectId, f.chatSessionId);
        jdbc.update("insert into agent_run_events (task_id, event_type, status) values (?, 'CREATED', 'DONE')", f.taskId);
        f.approvalId = insert("""
                insert into approvals (user_id, project_id, chat_session_id, task_id, approval_type, status, summary)
                values (?, ?, ?, ?, 'CHANGE', 'APPROVED', '요약')
                """, f.userId, f.projectId, f.chatSessionId, f.taskId);
        f.previewSessionId = MARK + "ps-" + unique;
        jdbc.update("""
                insert into preview_sessions (preview_session_id, access_token, user_id, project_id, chat_session_id, task_id,
                                              container_id, status, public_url, expires_at, last_accessed_at)
                values (?, ?, ?, ?, ?, ?, 'container', 'EXPIRED', 'http://p', now(), now())
                """, f.previewSessionId, (MARK + unique).substring(0, Math.min(64, (MARK + unique).length())), f.userId,
                f.projectId, f.chatSessionId, f.taskId);
        insert("""
                insert into project_changes (user_id, project_id, chat_session_id, task_id, preview_session_id, approval_id, status)
                values (?, ?, ?, ?, ?, ?, 'APPROVED')
                """, f.userId, f.projectId, f.chatSessionId, f.taskId, f.previewSessionId, f.approvalId);
        f.commitSha = String.format("%040x", System.nanoTime());
        jdbc.update("insert into server_pushed_commits (commit_sha, repository, task_id, kind, created_at) values (?, ?, ?, 'PREVIEW', now())",
                f.commitSha, repo, f.taskId);
        insert("insert into llm_usage (task_id, user_id, project_id, phase, provider, model) values (?, ?, ?, 'CODE', 'OPENAI', 'gpt')",
                f.taskId, f.userId, f.projectId);
        insert("""
                insert into audit_logs (category, action, outcome, actor_type, actor_user_id, project_id, resource_id, task_id)
                values ('REPOSITORY', 'PREVIEW_BRANCH_PUSHED', 'SUCCEEDED', 'AGENT', ?, ?, ?, ?)
                """, f.userId, f.projectId, repo, f.taskId);

        f.cloudConnectionId = insert("""
                insert into cloud_connections (user_id, provider, display_name, region) values (?, 'AWS', ?, 'ap-northeast-2')
                """, f.userId, MARK + name);
        jdbc.update("insert into cloud_connection_verification_jobs (job_id, cloud_connection_id, user_id, message) values (?, ?, ?, 'ok')",
                java.util.UUID.randomUUID().toString(), f.cloudConnectionId, f.userId);
        insert("insert into project_cloud_connection_settings (project_id, cloud_connection_id) values (?, ?)",
                f.projectId, f.cloudConnectionId);

        f.historyId = insert("""
                insert into deployment_histories (user_id, project_id, deploy_target_type, status, correlation_id)
                values (?, ?, 'S3', 'FAILED', ?)
                """, f.userId, f.projectId, MARK + "c-" + unique);
        insert("""
                insert into deployment_histories (user_id, project_id, deploy_target_type, status, correlation_id, retried_from_history_id)
                values (?, ?, 'S3', 'SUCCEEDED', ?, ?)
                """, f.userId, f.projectId, MARK + "c2-" + unique, f.historyId);
        insert("""
                insert into deployment_failure_analyses (history_id, user_id, source, summary, log_excerpt, suggested_fix)
                values (?, ?, 'RULE', '실패', '로그', '수정')
                """, f.historyId, f.userId);

        long variable = insert("insert into environment_variables (project_id, scope, env_key, env_value) values (?, 'PREVIEW', 'KEY', 'enc')",
                f.projectId);
        insert("""
                insert into environment_variable_histories (project_id, environment_variable_id, scope, env_key, action, secret, actor_user_id)
                values (?, ?, 'PREVIEW', 'KEY', 'CREATED', 0, ?)
                """, f.projectId, variable, f.userId);
        insert("insert into domains (project_id, domain_name) values (?, ?)", f.projectId, MARK + unique + ".example.com");
        insert("insert into preview_runtime_configs (project_id, runtime_type) values (?, 'STATIC')", f.projectId);
        insert("insert into project_approval_policies (project_id) values (?)", f.projectId);
        insert("insert into project_budget_settings (project_id, monthly_budget_amount) values (?, 10)", f.projectId);
        insert("""
                insert into project_infrastructure_settings (project_id, deployment_architecture, compute_tier, storage_type, network_access)
                values (?, 'A', 'B', 'C', 'D')
                """, f.projectId);
        insert("""
                insert into project_infrastructure_setting_changes (project_id, approval_id, action, status, deployment_architecture,
                                                                   compute_tier, storage_type, network_access, actor_user_id)
                values (?, ?, 'UPDATE', 'APPLIED', 'A', 'B', 'C', 'D', ?)
                """, f.projectId, f.approvalId, f.userId);
        // 끝났거나 우리 호스트의 것이라 탈퇴를 막지 않는 자원 기록
        insert("insert into provisioned_databases (project_id, method, engine, status) values (?, 'RDS', 'MYSQL', 'FAILED')", f.projectId);
        insert("""
                insert into provisioned_servers (project_id, instance_type, status, port, created_at, updated_at)
                values (?, 't3.micro', 'TERMINATED', 8080, now(), now())
                """, f.projectId);

        insert("insert into refresh_tokens (user_id, token_hash, expires_at) values (?, ?, ?)",
                f.userId, String.format("%064x", System.nanoTime()), LocalDateTime.now().plusDays(1));
        insert("insert into ai_provider_credentials (user_id, provider, encrypted_api_key) values (?, 'OPENAI', 'enc')", f.userId);
        insert("""
                insert into api_tokens (user_id, token_hash, token_prefix, scope, expires_at) values (?, ?, 'qp_', 'READ', ?)
                """, f.userId, String.format("%064x", System.nanoTime() + 1), LocalDateTime.now().plusDays(1));

        f.deliveryId = MARK + "d-" + unique;
        jdbc.update("insert into webhook_deliveries (delivery_id, event_type, payload) values (?, 'push', ?)", f.deliveryId,
                ("{\"repository\":{\"full_name\":\"" + repo.toUpperCase() + "\"}}").getBytes(StandardCharsets.UTF_8));
        f.unrelatedDeliveryId = MARK + "u-" + unique;
        jdbc.update("insert into webhook_deliveries (delivery_id, event_type, payload) values (?, 'push', ?)", f.unrelatedDeliveryId,
                "{\"repository\":{\"full_name\":\"someone/else\"}}".getBytes(StandardCharsets.UTF_8));
        return f;
    }

    /** 테이블마다 이 사용자를 가리키는 행 수. 탈퇴가 다루는 모든 테이블이 들어 있어야 한다. */
    private Map<String, Long> counts(Fixture f) {
        String projects = f.projectIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElseThrow();
        Map<String, String> where = new LinkedHashMap<>();
        where.put("users", "user_id = " + f.userId);
        where.put("projects", "project_id in (" + projects + ")");
        where.put("chat_sessions", "chat_session_id = " + f.chatSessionId);
        where.put("chat_messages", "chat_session_id = " + f.chatSessionId);
        where.put("agent_runs", "task_id = '" + f.taskId + "'");
        where.put("agent_run_events", "task_id = '" + f.taskId + "'");
        where.put("approvals", "approval_id = " + f.approvalId);
        where.put("preview_sessions", "preview_session_id = '" + f.previewSessionId + "'");
        where.put("project_changes", "task_id = '" + f.taskId + "'");
        where.put("server_pushed_commits", "commit_sha = '" + f.commitSha + "'");
        where.put("llm_usage", "user_id = " + f.userId);
        where.put("audit_logs", "actor_user_id = " + f.userId);
        where.put("cloud_connections", "cloud_connection_id = " + f.cloudConnectionId);
        where.put("cloud_connection_verification_jobs", "cloud_connection_id = " + f.cloudConnectionId);
        where.put("project_cloud_connection_settings", "cloud_connection_id = " + f.cloudConnectionId);
        where.put("deployment_histories", "user_id = " + f.userId);
        where.put("deployment_failure_analyses", "history_id = " + f.historyId);
        where.put("cdn_deletions", "cloud_connection_id = " + f.cloudConnectionId);
        for (String table : List.of("environment_variables", "environment_variable_histories", "domains", "preview_runtime_configs",
                "project_approval_policies", "project_budget_settings", "project_infrastructure_settings",
                "project_infrastructure_setting_changes", "provisioned_databases", "provisioned_servers")) {
            where.put(table, "project_id in (" + projects + ")");
        }
        where.put("refresh_tokens", "user_id = " + f.userId);
        where.put("ai_provider_credentials", "user_id = " + f.userId);
        where.put("api_tokens", "user_id = " + f.userId);
        where.put("webhook_deliveries", "delivery_id = '" + f.deliveryId + "'");

        // 세는 테이블이 탈퇴가 다루는 테이블과 정확히 같아야 한다 — 하나라도 빠지면 이 테스트가 그 테이블을 보지 않는 것이다
        java.util.Set<String> covered = new java.util.HashSet<>(UserWithdrawalStore.HANDLED_TABLES);
        covered.addAll(UserWithdrawalStore.CASCADED_TABLES);
        assertThat(where.keySet()).containsExactlyInAnyOrderElementsOf(covered);

        Map<String, Long> counts = new LinkedHashMap<>();
        where.forEach((table, condition) -> {
            if ("cdn_deletions".equals(table)) {
                return;   // 탈퇴할 수 있는 상태의 픽스처에는 없다(남아 있으면 탈퇴를 막는다) — liveAwsResources 테스트가 본다
            }
            counts.put(table, jdbc.queryForObject("select count(*) from " + table + " where " + condition, Long.class));
        });
        // 남의 배달 기록은 언제나 남는다
        assertThat(jdbc.queryForObject("select count(*) from webhook_deliveries where delivery_id = ?", Long.class,
                f.unrelatedDeliveryId)).isOne();
        return counts;
    }

    private long insert(String sql, Object... args) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement;
        }, keys);
        Number key = keys.getKey();
        return Objects.requireNonNullElse(key, 0L).longValue();
    }
}
