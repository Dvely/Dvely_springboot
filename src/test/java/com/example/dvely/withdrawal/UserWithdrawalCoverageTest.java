package com.example.dvely.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.withdrawal.infrastructure.UserWithdrawalStore;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 새 테이블이 생겼을 때 탈퇴가 그것을 빠뜨리지 않게 한다(#613).
 *
 * <p>실제 스키마(Flyway 를 다 적용한 MySQL)를 읽어, 사용자·프로젝트·태스크·대화·클라우드 연결을 가리키는 테이블을 모두 찾는다 —
 * FK 로 가리키든 FK 없이 같은 이름의 컬럼만 두든. 그 테이블이 {@link UserWithdrawalStore} 의 직접 삭제 목록에도, CASCADE 로
 * 함께 지워지는 목록에도 없으면 실패한다. 탈퇴 테스트의 픽스처는 아는 테이블만 채우므로, 이 검사가 없으면 새 테이블은 아무
 * 경고 없이 탈퇴 뒤에 남는다(처리방침 위반).</p>
 */
@SpringBootTest
class UserWithdrawalCoverageTest {

    /** 이 테이블들을 가리키면 "사용자의 것" 이다. */
    private static final List<String> OWNED_PARENTS = List.of(
            "users", "projects", "agent_runs", "chat_sessions", "cloud_connections", "deployment_histories",
            "approvals", "preview_sessions", "environment_variables");

    /** FK 없이도 사용자의 것을 가리키는 컬럼 이름. */
    private static final List<String> OWNER_COLUMNS = List.of(
            "user_id", "actor_user_id", "owner_user_id", "project_id", "task_id", "chat_session_id", "cloud_connection_id");

    /** 사용자와 무관한 전역 테이블(위 컬럼 이름을 갖지 않는다면 애초에 걸리지 않는다). */
    private static final Set<String> GLOBAL_TABLES = Set.of("flyway_schema_history", "template_catalog_snapshot",
            "revoked_access_tokens");

    @Autowired private JdbcTemplate jdbc;

    @Test
    void everyTableThatPointsAtAUsersDataIsDeletedOnWithdrawal() {
        Set<String> owned = new HashSet<>(jdbc.queryForList("""
                select distinct table_name from information_schema.key_column_usage
                 where table_schema = database() and referenced_table_name in (%s)
                """.formatted(quoted(OWNED_PARENTS)), String.class));
        owned.addAll(jdbc.queryForList("""
                select distinct table_name from information_schema.columns
                 where table_schema = database() and column_name in (%s)
                """.formatted(quoted(OWNER_COLUMNS)), String.class));
        owned.removeAll(GLOBAL_TABLES);

        Set<String> covered = new HashSet<>(UserWithdrawalStore.HANDLED_TABLES);
        covered.addAll(UserWithdrawalStore.CASCADED_TABLES);

        assertThat(owned)
                .as("사용자 데이터를 가리키는데 탈퇴가 지우지 않는 테이블 — UserWithdrawalStore 에 삭제 순서를 추가하라")
                .isSubsetOf(covered);
        // 반대 방향: 목록에 있는데 스키마에 없는 이름(오타·삭제된 테이블)도 잡는다
        Set<String> tables = new HashSet<>(jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = database()", String.class));
        assertThat(tables).containsAll(covered);
    }

    @Test
    void everyCascadedTableReallyCascadesFromAHandledParent() {
        // CASCADE 목록에 넣은 테이블은 실제로 ON DELETE CASCADE 로 지워져야 한다. 아니면 부모 삭제가 FK 위반으로 실패하거나 행이 남는다.
        for (String table : UserWithdrawalStore.CASCADED_TABLES) {
            List<String> parents = jdbc.queryForList("""
                    select k.referenced_table_name from information_schema.key_column_usage k
                      join information_schema.referential_constraints r
                        on r.constraint_schema = k.constraint_schema and r.constraint_name = k.constraint_name
                     where k.table_schema = database() and k.table_name = ? and r.delete_rule = 'CASCADE'
                    """, String.class, table);
            assertThat(parents).as(table).isNotEmpty();
            assertThat(parents).as(table).anyMatch(UserWithdrawalStore.HANDLED_TABLES::contains);
        }
    }

    private static String quoted(List<String> names) {
        return String.join(", ", names.stream().map(name -> "'" + name + "'").toList());
    }
}
