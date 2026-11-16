package com.example.dvely.project.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.example.dvely.cloudresource.application.LiveResource;
import com.example.dvely.project.application.command.dto.ProjectDeleteMode;
import com.example.dvely.project.domain.exception.ProjectNotFoundException;
import com.example.dvely.support.JdbcRows;
import com.example.dvely.withdrawal.application.UserWithdrawalService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 사용자 AWS 에 살아 있는 자원이 있는 프로젝트는 지우지 않는다(#595). 실제 MySQL 에서 판정 조건과 삭제 트랜잭션을 함께 본다.
 */
@SpringBootTest
class ProjectDeletionGuardTest {

    private static final String MARK = "pd595-";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProjectDeletionService deletionService;
    @Autowired private UserWithdrawalService withdrawalService;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        // 남은 픽스처는 탈퇴 경로로 치운다(살아 있는 자원 픽스처는 먼저 끈다)
        for (Long id : jdbc.queryForList("select user_id from users where github_user_id like ?", Long.class, MARK + "%")) {
            jdbc.update("update provisioned_servers set status = 'TERMINATED', instance_id = null, elastic_ip_allocation_id = null"
                    + " where project_id in (select project_id from projects where user_id = ?)", id);
            jdbc.update("update provisioned_databases set status = 'FAILED'"
                    + " where project_id in (select project_id from projects where user_id = ?)", id);
            jdbc.update("update domains set cloudfront_distribution_id = null"
                    + " where project_id in (select project_id from projects where user_id = ?)", id);
            jdbc.update("update deployment_histories set status = 'FAILED' where user_id = ?", id);
            withdrawalService.withdraw(id);
        }
    }

    @Test
    void onlyTheProjectsOwnLiveServersDatabasesAndDeploymentsBlockIt() {
        long user = user("a");
        long project = project(user, "target");
        long sibling = project(user, "sibling");
        long running = server(project, "RUNNING", "i-0abc", "eipalloc-1");
        long failedWithInstance = server(project, "FAILED", "i-0def", null);   // 만든 뒤 실패 — 인스턴스는 남아 있다
        server(project, "PENDING", null, null);                                // 승인 대기 — 아직 아무것도 만들지 않았다
        server(project, "TERMINATED", "i-0old", null);                         // 이미 종료했다
        long rds = database(project, "RDS", "READY");
        long docker = database(project, "DOCKER", "PROVISIONING");
        database(project, "LOCAL", "READY");                                   // 프리뷰 안 DB 는 우리 호스트의 것이다
        database(project, "RDS", "FAILED");
        long queued = deployment(user, project, "PENDING", "첫 배포");
        deployment(user, project, "FAILED", null);
        // 프론트 S3 도메인의 CloudFront 는 삭제가 함께 정리한다 — 막지 않는다
        JdbcRows.insert(jdbc, "insert into domains (project_id, domain_name, cloudfront_distribution_id) values (?, ?, 'E123')",
                project, MARK + System.nanoTime() + ".example.com");
        server(sibling, "RUNNING", "i-0sib", null);                            // 같은 사용자의 다른 프로젝트

        List<LiveResource> live = deletionService.findLiveResources(user, project);

        assertThat(live)
                .extracting(LiveResource::type, LiveResource::id)
                .containsExactly(
                        tuple(LiveResource.Type.SERVER, running),
                        tuple(LiveResource.Type.SERVER, failedWithInstance),
                        tuple(LiveResource.Type.DATABASE, rds),
                        tuple(LiveResource.Type.DATABASE, docker),
                        tuple(LiveResource.Type.DEPLOYMENT, queued));
        assertThat(live.getFirst()).satisfies(r -> {
            assertThat(r.label()).isEqualTo("i-0abc");
            assertThat(r.status()).isEqualTo("RUNNING");
            assertThat(r.projectId()).isEqualTo(project);
        });
        assertThat(live.get(2).label()).isEqualTo("RDS MYSQL");
        assertThat(live.get(4).label()).isEqualTo("첫 배포");
    }

    @Test
    void aProjectWithALiveServerIsNotDeletedUntilTheServerIsTerminated() {
        long user = user("a");
        long project = project(user, "target");
        long server = server(project, "RUNNING", "i-0abc", "eipalloc-1");

        assertThatThrownBy(() -> deletionService.purge(user, project, ProjectDeleteMode.PROJECT_ONLY))
                .isInstanceOfSatisfying(ProjectDeletionBlockedException.class, e -> assertThat(e.liveResources())
                        .extracting(LiveResource::type, LiveResource::id)
                        .containsExactly(tuple(LiveResource.Type.SERVER, server)));
        assertThat(deleted(project)).as("막혔으면 지우지 않는다").isFalse();

        jdbc.update("update provisioned_servers set status = 'TERMINATED' where server_id = ?", server);

        assertThat(deletionService.findLiveResources(user, project)).isEmpty();
        deletionService.purge(user, project, ProjectDeleteMode.PROJECT_ONLY);
        assertThat(deleted(project)).isTrue();
    }

    @Test
    void someoneElsesProjectIsNotFound() {
        long owner = user("a");
        long other = user("b");
        long project = project(owner, "target");
        server(project, "RUNNING", "i-0abc", null);

        assertThatThrownBy(() -> deletionService.findLiveResources(other, project))
                .isInstanceOf(ProjectNotFoundException.class);
    }

    private long user(String name) {
        return JdbcRows.insert(jdbc, "insert into users (github_user_id, user_name) values (?, ?)",
                MARK + name + "-" + System.nanoTime(), MARK + name);
    }

    private long project(long userId, String name) {
        return JdbcRows.insert(jdbc, "insert into projects (user_id, project_name) values (?, ?)", userId, MARK + name);
    }

    private long server(long projectId, String status, String instanceId, String eip) {
        return JdbcRows.insert(jdbc, """
                insert into provisioned_servers (project_id, instance_type, status, instance_id, elastic_ip_allocation_id, port,
                                                 created_at, updated_at)
                values (?, 't3.micro', ?, ?, ?, 8080, now(), now())
                """, projectId, status, instanceId, eip);
    }

    private long database(long projectId, String method, String status) {
        return JdbcRows.insert(jdbc, "insert into provisioned_databases (project_id, method, engine, status) values (?, ?, 'MYSQL', ?)",
                projectId, method, status);
    }

    private long deployment(long userId, long projectId, String status, String title) {
        return JdbcRows.insert(jdbc, """
                insert into deployment_histories (user_id, project_id, deploy_target_type, status, correlation_id, title)
                values (?, ?, 'S3', ?, ?, ?)
                """, userId, projectId, status, MARK + System.nanoTime(), title);
    }

    private boolean deleted(long projectId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select is_deleted from projects where project_id = ?", Boolean.class,
                projectId));
    }
}
