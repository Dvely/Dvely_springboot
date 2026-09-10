package com.example.dvely.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.domainbinding.infrastructure.persistence.repository.SpringDataDomainBindingRepository;
import com.example.dvely.project.infrastructure.persistence.entity.ProjectEntity;
import com.example.dvely.project.infrastructure.persistence.repository.SpringDataProjectRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * V61 · V62 (이슈 #338) 가드.
 *
 * <p>이 저장소의 다른 {@code *SchemaTest} 들은 컬럼과 제약을 검사하지만 <b>인덱스는 아무도 검사하지
 * 않는다</b> — {@code ddl-auto: validate} 도 인덱스를 보지 않는다. 즉 인덱스를 잘못 만들거나 실수로
 * 지워도 전체 테스트가 통과한다. 이 클래스가 그 구멍을 메운다.</p>
 *
 * <p>가장 중요한 것은 아래 {@code IgnoreCase} 관련 테스트다. 리포지토리에서 {@code IgnoreCase} 를
 * 걷어낸 것은 성능 때문인데, 그 변경이 <b>동작을 바꾸지 않는다</b>는 근거는 오직 컬럼 컬레이션이
 * {@code _ci} 라는 사실뿐이다. 누군가 컬레이션을 {@code _bin}/{@code _cs} 로 바꾸면 대소문자가 다른
 * 저장소 이름으로 오는 GitHub 웹훅이 조용히 프로젝트를 못 찾게 된다 — 그 순간 이 테스트가 깨진다.</p>
 */
@SpringBootTest
class SchemaOptimizationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SpringDataProjectRepository projectRepository;

    @Autowired
    private SpringDataDomainBindingRepository domainBindingRepository;

    // ---------------------------------------------------------------------
    // IgnoreCase 제거가 동작을 바꾸지 않는다는 근거
    // ---------------------------------------------------------------------

    @Test
    void 대소문자를_무시하는_것은_IgnoreCase_가_아니라_컬럼_컬레이션이다() {
        List<String> collations = jdbcTemplate.queryForList(
                """
                        select collation_name from information_schema.columns
                        where table_schema = database()
                          and ((table_name = 'projects' and column_name = 'source_repository')
                            or (table_name = 'domains'  and column_name = 'domain_name'))
                        """,
                String.class);

        assertThat(collations).hasSize(2).allSatisfy(collation ->
                assertThat(collation).endsWith("_ci"));
    }

    @ParameterizedTest(name = "저장: Octo-Org/Sample-Repo / 조회: {0}")
    @ValueSource(strings = {"Octo-Org/Sample-Repo", "octo-org/sample-repo", "OCTO-ORG/SAMPLE-REPO"})
    @Transactional
    void 웹훅이_어떤_대소문자로_와도_프로젝트를_찾는다(String lookedUpAs) {
        long userId = insertUser();
        insertProject(userId, "Octo-Org/Sample-Repo");

        Optional<ProjectEntity> found = projectRepository.findFirstBySourceRepository(lookedUpAs);
        List<ProjectEntity> foundAlive = projectRepository.findBySourceRepositoryAndDeletedFalse(lookedUpAs);
        Optional<ProjectEntity> foundForOwner = projectRepository
                .findFirstByOwnerUserIdAndSourceRepositoryAndDeletedFalseOrderByUpdatedAtDesc(userId, lookedUpAs);

        assertThat(found).isPresent();
        assertThat(foundAlive).hasSize(1);
        assertThat(foundForOwner).isPresent();
    }

    @ParameterizedTest(name = "저장: Sample.Qeploy.COM / 조회: {0}")
    @ValueSource(strings = {"Sample.Qeploy.COM", "sample.qeploy.com", "SAMPLE.QEPLOY.COM"})
    @Transactional
    void 호스트네임_중복_검사도_대소문자를_구분하지_않는다(String lookedUpAs) {
        long userId = insertUser();
        long projectId = insertProject(userId, "octo-org/domain-repo");
        jdbcTemplate.update(
                "insert into domains (project_id, domain_name, status) values (?, ?, 'VERIFYING')",
                projectId, "Sample.Qeploy.COM");

        assertThat(domainBindingRepository.existsByHostname(lookedUpAs)).isTrue();
    }

    // ---------------------------------------------------------------------
    // V61 — 인덱스
    // ---------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "projects:idx_projects_source_repository",
            "domains:idx_domains_status_created",
            "deployment_histories:idx_deployment_histories_project_triggered",
            "revoked_access_tokens:idx_revoked_access_tokens_expires_at",
            "refresh_tokens:idx_refresh_tokens_expires_at",
            "webhook_deliveries:idx_webhook_deliveries_retention",
            "users:uk_users_github_installation_id",
            "approvals:idx_approvals_project_type_status",
            "chat_sessions:idx_chat_sessions_user_project_list",
            "provisioned_servers:idx_provisioned_servers_connection_status",
    })
    void 새_인덱스가_존재한다(String tableAndIndex) {
        String[] parts = tableAndIndex.split(":");

        assertThat(indexExists(parts[0], parts[1]))
                .as("%s 의 %s", parts[0], parts[1])
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // 복합 인덱스의 prefix 라 중복이던 것들 — 핫 INSERT 테이블의 쓰기 증폭이었다
            "chat_messages:idx_chat_messages_chat_session_id",
            "deployment_histories:idx_deployment_histories_project_id",
            "cloud_connections:idx_cloud_connections_user_id",
            "approvals:idx_approvals_project_id",
            "chat_sessions:idx_chat_sessions_user_id",
            // UK 와 완전 중복이던 것(V12)
            "revoked_access_tokens:idx_revoked_tokens_jti",
            // 어떤 쿼리도 쓰지 않던 것들
            "cloud_connections:idx_cloud_connections_user_status",
            "webhook_deliveries:idx_webhook_deliveries_event",
    })
    void 중복_또는_미사용_인덱스가_사라졌다(String tableAndIndex) {
        String[] parts = tableAndIndex.split(":");

        assertThat(indexExists(parts[0], parts[1]))
                .as("%s 의 %s", parts[0], parts[1])
                .isFalse();
    }

    @Test
    void github_installation_id_는_유일해야_한다() {
        // findByGithubInstallationId 가 Optional 을 돌려주므로 중복 행이 있으면 런타임에 터진다.
        Integer nonUnique = jdbcTemplate.queryForObject(
                """
                        select min(non_unique) from information_schema.statistics
                        where table_schema = database() and table_name = 'users'
                          and index_name = 'uk_users_github_installation_id'
                        """,
                Integer.class);

        assertThat(nonUnique).isZero();
    }

    @Test
    void chat_messages_의_FK_는_남은_복합_인덱스가_받쳐준다() {
        // 단일 chat_session_id 인덱스를 지웠으므로, 남은 것이 leftmost 로 받쳐주지 않으면
        // FK 가 무인덱스가 된다.
        Integer seq = jdbcTemplate.queryForObject(
                """
                        select seq_in_index from information_schema.statistics
                        where table_schema = database() and table_name = 'chat_messages'
                          and index_name = 'idx_chat_messages_task' and column_name = 'chat_session_id'
                        """,
                Integer.class);

        assertThat(seq).isEqualTo(1);
    }

    // V62(죽은 테이블·컬럼 제거) 가드는 DeadSchemaRemovalTest 에 있다.

    // ---------------------------------------------------------------------

    private boolean indexExists(String table, String index) {
        Integer count = jdbcTemplate.queryForObject(
                """
                        select count(*) from information_schema.statistics
                        where table_schema = database() and table_name = ? and index_name = ?
                        """,
                Integer.class, table, index);
        return count != null && count > 0;
    }

    private long insertUser() {
        String githubUserId = "gh-" + System.nanoTime();
        jdbcTemplate.update(
                "insert into users (github_user_id, user_name) values (?, ?)",
                githubUserId, "schema-optimization-test");
        return jdbcTemplate.queryForObject(
                "select user_id from users where github_user_id = ?", Long.class, githubUserId);
    }

    private long insertProject(long userId, String sourceRepository) {
        jdbcTemplate.update(
                "insert into projects (user_id, project_name, source_repository) values (?, ?, ?)",
                userId, "schema-optimization-test", sourceRepository);
        return jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
    }
}
