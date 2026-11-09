package com.example.dvely.preview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.auth.infrastructure.config.security.JwtProvider;
import com.example.dvely.preview.application.service.ProjectPreviewProvisioner;
import com.example.dvely.withdrawal.application.UserWithdrawalService;
import jakarta.servlet.Filter;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 서버 전체 프리뷰 상한(#624)을 보안 필터부터 실 MySQL 까지 본다. 이슈의 완료 기준 — 서버 전체 상한을 2 로 두고 세 사용자가
 * 프리뷰를 열면 세 번째가 429 를 받는다.
 *
 * <p>도커와 비동기 준비(clone·build)는 대체한다. 상한은 컨테이너를 띄우기 전({@code makeRoom})과 행을 저장한 뒤
 * ({@code confirm})에 DB 를 세므로, 대체해도 세는 것은 실제 행이다.</p>
 *
 * <p>서버 전체 수는 테스트 DB 의 모든 행을 센다. 다른 테스트가 남긴 살아 있는 세션이 끼어들지 않게, 시작할 때 그것들을 잠시
 * 닫아 두고 끝나면 원래 상태로 되돌린다.</p>
 */
@SpringBootTest(properties = "qeploy.limits.global.max-live-previews=2")
class GlobalPreviewLimitEndpointTest {

    private static final String MARK = "gl624-";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserWithdrawalService withdrawalService;
    @MockitoBean private DockerContainerService docker;
    @MockitoBean private ProjectPreviewProvisioner provisioner;

    private MockMvc mvc;
    private final List<Map<String, Object>> parked = new ArrayList<>();
    private final AtomicInteger containers = new AtomicInteger();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
        when(docker.createAndStartContainer(any(), anyLong(), anyString(), any(), any(), any(), anyLong()))
                .thenAnswer(invocation -> MARK + "ctr-" + containers.incrementAndGet());
        when(docker.getContainerIp(anyString())).thenReturn("172.18.0.9");
        removeMine();
        parked.addAll(jdbc.queryForList(
                "select preview_session_id, status from preview_sessions where status in ('ACTIVE', 'PROVISIONING')"));
        for (Map<String, Object> row : parked) {
            jdbc.update("update preview_sessions set status = 'CLOSED' where preview_session_id = ?", row.get("preview_session_id"));
        }
    }

    @AfterEach
    void tearDown() {
        removeMine();
        for (Map<String, Object> row : parked) {
            jdbc.update("update preview_sessions set status = ? where preview_session_id = ?",
                    row.get("status"), row.get("preview_session_id"));
        }
        parked.clear();
    }

    @Test
    void theThirdUserIsTurnedAwayWhenTheServerHoldsTwo() throws Exception {
        String first = tokenForNewProject();
        String second = tokenForNewProject();
        String third = tokenForNewProject();

        open(first).andExpect(status().isAccepted());
        open(second).andExpect(status().isAccepted());
        open(third)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("PREVIEW_CAPACITY_EXCEEDED"))
                .andExpect(jsonPath("$.message").value(
                        "지금은 서버의 프리뷰 자리가 모두 차 있어 새 프리뷰를 띄울 수 없습니다. 잠시 뒤 다시 시도하거나, "
                                + "열어 둔 프리뷰가 있으면 닫은 뒤 다시 시도해주세요."));

        assertThat(liveCount()).isEqualTo(2);
        // 세 번째는 컨테이너를 띄우기 전에 거절됐다
        assertThat(containers).hasValue(2);
    }

    @Test
    void threeAtOnceNeverLeaveMoreThanTwoAlive() throws Exception {
        List<String> tokens = List.of(tokenForNewProject(), tokenForNewProject(), tokenForNewProject());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(tokens.size())) {
            for (String token : tokens) {
                Callable<Integer> call = () -> {
                    start.await();
                    MvcResult result = open(token).andReturn();
                    return result.getResponse().getStatus();
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }

            // 동시에 몰리면 둘 다 물러날 수도 있다(PreviewCapacity#confirm) — 넘친 채로 남지만 않으면 된다.
            assertThat(statuses).containsAnyOf(429).allMatch(code -> code == 202 || code == 429);
            assertThat(liveCount()).isLessThanOrEqualTo(2).isEqualTo(statuses.stream().filter(code -> code == 202).count());
        }
    }

    private org.springframework.test.web.servlet.ResultActions open(String token) throws Exception {
        String[] parts = token.split(" ", 2);
        return mvc.perform(post("/api/v1/projects/" + parts[0] + "/preview-session")
                .header("Authorization", "Bearer " + parts[1]));
    }

    /** 저장소가 연결된 프로젝트를 가진 새 사용자. "{projectId} {accessToken}" 을 돌려준다. */
    private String tokenForNewProject() {
        long userId = insert("insert into users (github_user_id, user_name) values (?, ?)", MARK + System.nanoTime(), MARK + "u");
        long projectId = insert("insert into projects (user_id, project_name, source_repository) values (?, ?, ?)",
                userId, MARK + "p", "octo/" + MARK + userId);
        return projectId + " " + jwtProvider.createToken(userId);
    }

    private long liveCount() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "select count(*) from preview_sessions where status in ('ACTIVE', 'PROVISIONING')", Long.class));
    }

    private void removeMine() {
        for (Long id : jdbc.queryForList("select user_id from users where github_user_id like ?", Long.class, MARK + "%")) {
            withdrawalService.withdraw(id);
        }
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
