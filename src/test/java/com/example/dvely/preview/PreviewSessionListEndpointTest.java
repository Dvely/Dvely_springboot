package com.example.dvely.preview;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.apitoken.application.command.ApiTokenCommandService;
import com.example.dvely.apitoken.domain.value.ApiTokenScope;
import com.example.dvely.auth.infrastructure.config.security.JwtProvider;
import com.example.dvely.support.JdbcRows;
import com.example.dvely.withdrawal.application.UserWithdrawalService;
import jakarta.servlet.Filter;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 내 프리뷰 목록(#651)을 보안 필터부터 실 MySQL 까지 본다. 이슈의 완료 기준 — 남의 세션이 안 보이고, 상한(3)이 찬 사용자에게
 * 셋이 보이며 그중 닫을 수 있는 것과 닫을 수 없는 이유가 나오고, 요청 중 Docker 호출이 0회다.
 *
 * <p>컨텍스트 설정은 {@code UserWithdrawalEndpointTest} 와 같다(Docker 만 대체) — 같은 컨텍스트를 나눠 쓴다.</p>
 */
@SpringBootTest
class PreviewSessionListEndpointTest {

    private static final String MARK = "ps651-";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JdbcTemplate jdbc;
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
            withdrawalService.withdraw(id);
        }
    }

    @Test
    void listsMyLivePreviewsOldestFirstWithWhetherEachCanBeClosed() throws Exception {
        long me = seedUser();
        long projectId = insert("insert into projects (user_id, project_name) values (?, ?)", me, MARK + "shop");
        long chat = insert("insert into chat_sessions (user_id, project_id) values (?, ?)", me, projectId);
        String waitingTask = run(me, projectId, chat, "WAITING_RESULT_APPROVAL");
        LocalDateTime now = LocalDateTime.now();
        String opened = session(me, projectId, null, null, "ACTIVE", now.minusMinutes(30));
        String awaiting = session(me, projectId, chat, waitingTask, "ACTIVE", now.minusMinutes(20));
        String preparing = session(me, projectId, null, null, "PROVISIONING", now.minusMinutes(10));
        session(me, projectId, null, null, "CLOSED", now.minusMinutes(40));
        long someoneElse = seedUser();
        long theirProject = insert("insert into projects (user_id, project_name) values (?, ?)", someoneElse, MARK + "theirs");
        session(someoneElse, theirProject, null, null, "ACTIVE", now.minusMinutes(50));
        clearInvocations(docker);

        mvc.perform(get("/api/v1/preview-sessions").header("Authorization", "Bearer " + jwtProvider.createToken(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.maxLivePreviews").value(3))
                .andExpect(jsonPath("$.data.items.length()").value(3))
                // 오래 안 본 순 — 상한 회수가 고르는 순서와 같다
                .andExpect(jsonPath("$.data.items[0].sessionId").value(opened))
                .andExpect(jsonPath("$.data.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.items[0].projectName").value(MARK + "shop"))
                .andExpect(jsonPath("$.data.items[0].taskId").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].closable").value(true))
                .andExpect(jsonPath("$.data.items[0].closeBlockedReason").doesNotExist())
                .andExpect(jsonPath("$.data.items[1].sessionId").value(awaiting))
                .andExpect(jsonPath("$.data.items[1].taskId").value(waitingTask))
                .andExpect(jsonPath("$.data.items[1].taskStatus").value("WAITING_RESULT_APPROVAL"))
                .andExpect(jsonPath("$.data.items[1].closable").value(false))
                .andExpect(jsonPath("$.data.items[1].closeBlockedReason").value("WAITING_RESULT_APPROVAL"))
                .andExpect(jsonPath("$.data.items[2].sessionId").value(preparing))
                .andExpect(jsonPath("$.data.items[2].status").value("PROVISIONING"))
                .andExpect(jsonPath("$.data.items[2].closable").value(false))
                .andExpect(jsonPath("$.data.items[2].closeBlockedReason").value("PROVISIONING"))
                .andExpect(jsonPath("$.data.items[0].lastAccessedAt").exists())
                .andExpect(jsonPath("$.data.items[0].expiresAt").exists())
                // 열 주소와 컨테이너 정보는 내보내지 않는다
                .andExpect(jsonPath("$.data.items[0].accessToken").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].publicUrl").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].containerId").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].containerIp").doesNotExist());

        // 목록 하나에 항목마다 inspect 가 붙지 않게 — Docker 는 한 번도 부르지 않는다
        verifyNoInteractions(docker);
    }

    @Test
    void aReadOnlyAccessTokenCanReadItToo() throws Exception {
        long me = seedUser();
        String token = apiTokens.issue(me, ApiTokenScope.READ, MARK + "read", 7).plaintext();

        mvc.perform(get("/api/v1/preview-sessions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    private String run(long userId, long projectId, long chat, String status) {
        String taskId = MARK + UUID.randomUUID();
        jdbc.update("insert into agent_runs (task_id, user_id, project_id, chat_session_id, status, plan_json) values (?, ?, ?, ?, ?, '{}')",
                taskId, userId, projectId, chat, status);
        return taskId;
    }

    private String session(long userId, long projectId, Long chat, String taskId, String status, LocalDateTime lastAccessedAt) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                insert into preview_sessions (preview_session_id, access_token, user_id, project_id, chat_session_id, task_id,
                                              container_id, status, public_url, expires_at, last_accessed_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'http://p', ?, ?)
                """, id, UUID.randomUUID().toString().replace("-", ""), userId, projectId, chat, taskId,
                MARK + "ctr", status, lastAccessedAt.plusMinutes(60), lastAccessedAt);
        return id;
    }

    private long seedUser() {
        return insert("insert into users (github_user_id, user_name) values (?, ?)", MARK + System.nanoTime(), MARK + "u");
    }

    private long insert(String sql, Object... args) {
        return JdbcRows.insert(jdbc, sql, args);
    }
}
