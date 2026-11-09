package com.example.dvely.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dvely.auth.infrastructure.config.security.JwtProvider;
import com.example.dvely.common.paging.CursorResponse;
import com.example.dvely.project.application.query.ProjectQueryService;
import com.example.dvely.withdrawal.application.UserWithdrawalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 배포 이력 목록의 상한·커서와 활동 로그의 소유권 확인 횟수를 실 MySQL 로 본다(#625).
 *
 * <p>목·스파이 빈을 쓰지 않는다 — 공유 기본 컨텍스트를 그대로 써서 테스트 커넥션 한도를 늘리지 않는다.</p>
 */
@SpringBootTest
class DeploymentHistoryPageIntegrationTest {

    private static final String MARK = "dh625-";
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 11, 9, 10, 0, 0);

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired private ProjectQueryService projectQueryService;
    @Autowired private UserWithdrawalService withdrawalService;

    private MockMvc mvc;
    private long userId;
    private long projectId;
    private String token;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
        cleanUp();
        userId = insert("insert into users (github_user_id, user_name) values (?, ?)", MARK + System.nanoTime(), MARK + "u");
        projectId = insert("insert into projects (user_id, project_name) values (?, ?)", userId, MARK + "p");
        token = "Bearer " + jwtProvider.createToken(userId);
    }

    @AfterEach
    void cleanUp() {
        for (Long id : jdbc.queryForList("select user_id from users where github_user_id like ?", Long.class, MARK + "%")) {
            withdrawalService.withdraw(id);
        }
    }

    @Test
    void pagesWalkTheWholeHistoryInOrderEvenAcrossRowsFromTheSameSecond() throws Exception {
        // id 순서와 요청 시각 순서가 다르고, 같은 초에 들어온 행이 있다(triggered_at 은 초 단위)
        long a = history(BASE);
        long b = history(BASE.plusSeconds(5));
        long c = history(BASE.plusSeconds(5));
        long d = history(BASE.minusMinutes(1));

        List<Long> walked = new ArrayList<>();
        String after = null;
        int pages = 0;
        do {
            MvcResult page = list("limit=1" + (after == null ? "" : "&after=" + after));
            walked.addAll(ids(page));
            after = page.getResponse().getHeader(CursorResponse.NEXT_CURSOR_HEADER);
            pages++;
        } while (after != null && pages < 10);

        // 최신순: 같은 초면 id 큰 것 먼저 — 건너뛰거나 두 번 주지 않는다
        assertThat(walked).containsExactly(c, b, a, d);
        assertThat(pages).isEqualTo(4);
    }

    @Test
    void withoutALimitAtMostTwoHundredComeBackWithACursorForTheRest() throws Exception {
        // 예전에는 전량이었다(100건 응답 179KB). limit 를 모르는 클라이언트는 최신 200건을 받는다
        StringBuilder sql = new StringBuilder(
                "insert into deployment_histories (user_id, project_id, deploy_target_type, status, correlation_id, triggered_at) values ");
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            sql.append(i == 0 ? "" : ",").append("(?, ?, 'GITHUB_PAGES', 'LIVE', ?, ?)");
            args.addAll(List.of(userId, projectId, UUID.randomUUID().toString(), BASE.plusSeconds(i)));
        }
        jdbc.update(sql.toString(), args.toArray());

        MvcResult first = list(null);
        MvcResult rest = list("after=" + first.getResponse().getHeader(CursorResponse.NEXT_CURSOR_HEADER));

        assertThat(ids(first)).hasSize(200);
        assertThat(first.getResponse().getHeader(CursorResponse.NEXT_CURSOR_HEADER)).isNotNull();
        assertThat(ids(rest)).hasSize(1);
        assertThat(rest.getResponse().getHeader(CursorResponse.NEXT_CURSOR_HEADER)).isNull();
        // 상한 200 을 넘겨 달라고 해도 200 이다
        assertThat(ids(list("limit=500"))).hasSize(200);
    }

    @Test
    void aCursorFromAnotherProjectFindsNothing() throws Exception {
        history(BASE);
        long otherProject = insert("insert into projects (user_id, project_name) values (?, ?)", userId, MARK + "other");
        long foreign = insert("insert into deployment_histories (user_id, project_id, deploy_target_type, status, correlation_id, triggered_at)"
                + " values (?, ?, 'GITHUB_PAGES', 'LIVE', ?, ?)", userId, otherProject, UUID.randomUUID().toString(), BASE.plusHours(1));

        assertThat(ids(list("after=" + foreign))).isEmpty();
    }

    @Test
    void theActivityLogChecksProjectOwnershipOnce() {
        history(BASE);

        String before = sqlCaptured(() -> projectQueryService.getActivityLogs(userId, projectId));

        // 예전에는 활동 로그 한 번에 projects 를 다섯 번 읽었다 — 자기 확인 + 배포·변경·승인·도메인 서비스가 각자 확인
        long projectLookups = before.lines().filter(sql -> sql.contains(" from projects ")).count();
        assertThat(projectLookups).as(before).isEqualTo(1);
    }

    private long history(LocalDateTime triggeredAt) {
        return insert("insert into deployment_histories (user_id, project_id, deploy_target_type, status, correlation_id, triggered_at)"
                + " values (?, ?, 'GITHUB_PAGES', 'LIVE', ?, ?)", userId, projectId, UUID.randomUUID().toString(), triggeredAt);
    }

    private MvcResult list(String query) throws Exception {
        String url = "/api/v1/projects/" + projectId + "/deployments" + (query == null ? "" : "?" + query);
        return mvc.perform(get(url).header("Authorization", token)).andExpect(status().isOk()).andReturn();
    }

    private List<Long> ids(MvcResult result) throws Exception {
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        List<Long> ids = new ArrayList<>();
        data.forEach(node -> ids.add(node.get("historyId").asLong()));
        return ids;
    }

    /**
     * {@code call} 이 보낸 SQL 을 한 줄에 하나씩. Hibernate 의 SQL 로거를 잠시 DEBUG 로 내려 받는다. 같은 컨텍스트의
     * 스케줄러가 그 사이 보낸 SQL 이 섞이지 않게 이 스레드의 것만 남긴다.
     */
    private static String sqlCaptured(Runnable call) {
        String thread = Thread.currentThread().getName();
        ch.qos.logback.classic.Logger sqlLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.hibernate.SQL");
        ch.qos.logback.classic.Level original = sqlLogger.getLevel();
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        sqlLogger.addAppender(appender);
        sqlLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            call.run();
        } finally {
            sqlLogger.setLevel(original);
            sqlLogger.detachAppender(appender);
        }
        return String.join("\n", appender.list.stream()
                .filter(event -> thread.equals(event.getThreadName()))
                .map(event -> event.getFormattedMessage().replaceAll("\\s+", " ").strip() + " ").toList());
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
