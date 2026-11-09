package com.example.dvely.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.apitoken.application.command.ApiTokenCommandService;
import com.example.dvely.apitoken.domain.value.ApiTokenScope;
import com.example.dvely.audit.infrastructure.config.AuditLogExecutor;
import com.example.dvely.auth.infrastructure.config.security.JwtProvider;
import com.example.dvely.withdrawal.application.UserWithdrawalService;
import jakarta.servlet.Filter;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code DELETE /api/v1/users/me}(#613 PR-2)를 보안 필터부터 실 MySQL 까지 통째로 본다.
 *
 * <p>도커는 대체한다. 진짜 데몬에 붙으면 테스트 DB 의 회원 번호와 같은 번호를 단 개발자의 프리뷰 컨테이너를 지울 수 있다.</p>
 */
@SpringBootTest
class UserWithdrawalEndpointTest {

    private static final String MARK = "wd613e-";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuditLogExecutor auditLogExecutor;
    @Autowired private ApiTokenCommandService apiTokens;
    @Autowired private UserWithdrawalService withdrawalService;
    @MockitoBean private DockerContainerService docker;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
        when(docker.listContainerIdsOwnedBy(anyLong())).thenReturn(List.of());
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        for (Long id : jdbc.queryForList("select user_id from users where github_user_id like ?", Long.class, MARK + "%")) {
            jdbc.update("update provisioned_servers set status = 'TERMINATED', instance_id = null, elastic_ip_allocation_id = null"
                    + " where project_id in (select project_id from projects where user_id = ?)", id);
            withdrawalService.withdraw(id);
        }
    }

    @Test
    void withdrawsAndTheSameLoginStopsWorking() throws Exception {
        long userId = seedUser();
        long projectId = insert("insert into projects (user_id, project_name) values (?, ?)", userId, MARK + "p");
        insert("insert into refresh_tokens (user_id, token_hash, expires_at) values (?, ?, ?)",
                userId, MARK + System.nanoTime(), LocalDateTime.now().plusDays(30));
        String token = "Bearer " + jwtProvider.createToken(userId);

        mvc.perform(get("/api/v1/users/me/withdrawal").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.withdrawable").value(true))
                .andExpect(jsonPath("$.data.liveResources").isEmpty());

        mvc.perform(delete("/api/v1/users/me").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        assertThat(count("users", "user_id", userId)).isZero();
        assertThat(count("projects", "project_id", projectId)).isZero();
        assertThat(count("refresh_tokens", "user_id", userId)).as("refresh 토큰 전부").isZero();
        // 지금 쓰던 access 토큰은 폐기됐다 — 같은 토큰으로는 더 들어오지 못한다
        mvc.perform(get("/api/v1/users/me").header("Authorization", token)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/users/me").header("Authorization", token)).andExpect(status().isUnauthorized());
        // 컨테이너는 삭제 전과 후 두 번 쓸었다
        verify(docker, times(2)).listContainerIdsOwnedBy(userId);

        assertThat(auditLogExecutor.awaitDrained(Duration.ofSeconds(10))).isTrue();
        List<Map<String, Object>> audits = jdbc.queryForList(
                "select * from audit_logs where action = 'USER_WITHDRAWN' and actor_user_id = ?", userId);
        assertThat(audits).as("탈퇴 사실 한 줄만 — 회원 번호 외의 식별자는 없다").singleElement().satisfies(row -> {
            assertThat(row.get("category")).isEqualTo("ACCOUNT");
            assertThat(row.get("actor_type")).isEqualTo("USER");
            assertThat(row.get("project_id")).isNull();
            assertThat(row.get("resource_type")).isNull();
            assertThat(row.get("resource_id")).isNull();
            assertThat(row.get("task_id")).isNull();
            assertThat(row.get("detail")).isNull();
        });
        jdbc.update("delete from audit_logs where action = 'USER_WITHDRAWN' and actor_user_id = ?", userId);
    }

    @Test
    void liveAwsResourcesAnswer409WithTheListAndChangeNothing() throws Exception {
        long userId = seedUser();
        long projectId = insert("insert into projects (user_id, project_name) values (?, ?)", userId, MARK + "p");
        long serverId = insert("""
                insert into provisioned_servers (project_id, instance_type, status, instance_id, port, created_at, updated_at)
                values (?, 't3.micro', 'RUNNING', 'i-0abc', 8080, now(), now())
                """, projectId);
        String token = "Bearer " + jwtProvider.createToken(userId);

        mvc.perform(delete("/api/v1/users/me").header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("WITHDRAWAL_BLOCKED"))
                .andExpect(jsonPath("$.message").value("AWS 계정에 아직 정리하지 않은 자원이 1개 있어 탈퇴할 수 없습니다."))
                .andExpect(jsonPath("$.data.withdrawable").value(false))
                .andExpect(jsonPath("$.data.liveResources.length()").value(1))
                .andExpect(jsonPath("$.data.liveResources[0].type").value("SERVER"))
                .andExpect(jsonPath("$.data.liveResources[0].id").value(serverId))
                .andExpect(jsonPath("$.data.liveResources[0].projectId").value(projectId))
                .andExpect(jsonPath("$.data.liveResources[0].label").value("i-0abc"))
                .andExpect(jsonPath("$.data.liveResources[0].status").value("RUNNING"));

        assertThat(count("users", "user_id", userId)).isOne();
        assertThat(count("projects", "project_id", projectId)).isOne();
        verify(docker, never()).listContainerIdsOwnedBy(anyLong());
        // 막혔으면 로그인도 그대로다
        mvc.perform(get("/api/v1/users/me").header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/users/me/withdrawal").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.withdrawable").value(false))
                .andExpect(jsonPath("$.data.liveResources[0].id").value(serverId));
    }

    @Test
    void aPersonalAccessTokenCannotWithdraw() throws Exception {
        long userId = seedUser();
        String pat = apiTokens.issue(userId, ApiTokenScope.WRITE, MARK + "agent", 30).plaintext();

        mvc.perform(delete("/api/v1/users/me").header("Authorization", "Bearer " + pat))
                .andExpect(status().isForbidden());

        assertThat(count("users", "user_id", userId)).isOne();
    }

    @Test
    void withoutALoginItIs401() throws Exception {
        mvc.perform(delete("/api/v1/users/me")).andExpect(status().isUnauthorized());
    }

    private long seedUser() {
        return insert("insert into users (github_user_id, user_name) values (?, ?)", MARK + System.nanoTime(), MARK + "u");
    }

    private long count(String table, String column, long id) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "select count(*) from " + table + " where " + column + " = ?", Long.class, id));
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
        return Objects.requireNonNull(keys.getKey()).longValue();
    }
}
