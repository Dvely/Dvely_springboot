package com.example.dvely.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.AgentTaskFailure;
import com.example.dvely.agent.application.dto.AnsweredClarification;
import com.example.dvely.agent.application.dto.ClarificationRequest;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.agent.presentation.AgentController;
import com.example.dvely.agent.presentation.dto.TaskStatusResponse;
import com.example.dvely.common.exception.NotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #619 의 완료 기준 두 가지를 실 MySQL 에서 본다.
 *
 * <ol>
 *   <li><b>응답 JSON 이 한 글자도 바뀌지 않는다.</b> #341({@link ListProjectionResponseIdentityTest})과 같은 방식이다 —
 *       예전 매핑(컨트롤러가 다섯 번 따로 읽어 조립하던 것)을 이 파일 안에 리터럴로 옮겨 적고, 새 경로(컨트롤러 →
 *       {@code TaskStatusQueryService})의 응답과 직렬화 문자열로 비교한다.</li>
 *   <li><b>요청당 DB 왕복이 줄어든다.</b> 요청을 부른 스레드가 낸 JDBC 호출만 센다 — 문장 실행과 트랜잭션 의례
 *       (autocommit·read-only·commit). 백그라운드 워커의 호출은 다른 스레드라 섞이지 않는다.</li>
 * </ol>
 */
@SpringBootTest
class TaskStatusQueryIdentityTest {

    private static final String PREFIX = "status-619-";
    private static final ThreadLocal<AtomicInteger> CALLS = new ThreadLocal<>();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AgentController agentController;
    @Autowired private TaskStore taskStore;
    @Autowired private AgentOrchestrator agentOrchestrator;

    private long ownerId;
    private long otherUserId;

    @BeforeEach
    void seed() throws Exception {
        cleanUp();
        ownerId = insertUser("owner");
        otherUserId = insertUser("other");

        // FAILED · 실패 정보 · 승인 셋(결정됨 하나, PENDING 둘 — 가장 오래된 PENDING 이 나와야 한다) · 큰 plan
        insertRun("failed-blocked", "FAILED", "https://p.example/old", "요약", "빌드 실패", null,
                "로그 일부", "수정안", 1, 3, null, null);
        insertApproval("failed-blocked", "DEPLOYMENT", "APPROVED");
        insertApproval("failed-blocked", "CHANGE", "PENDING");
        insertApproval("failed-blocked", "RESULT", "PENDING");
        // FAILED · 재시도 가능
        insertRun("failed-retryable", "FAILED", null, null, "빌드 실패", null, "로그", "수정안", 0, 3, null, null);
        // FAILED · 시도 소진
        insertRun("failed-exhausted", "FAILED", null, null, "빌드 실패", null, "로그", null, 3, 3, null, null);
        // WAITING_INPUT · 되묻기 + 읽을 수 없는 답 스냅샷(→ null)
        insertRun("waiting-input", "WAITING_INPUT", null, null, null, "어떤 스택으로 만들까요?", null, null, 0, 3,
                objectMapper.writeValueAsString(new ClarificationRequest("어떤 스택으로 만들까요?",
                        ClarificationRequest.InputType.SINGLE_SELECT,
                        List.of(new ClarificationRequest.Option("react", "React", true)), true)),
                "{not json");
        // DONE · 프리뷰 · 답한 되묻기 · 빈 문자열 되묻기(→ null)
        insertRun("done", "DONE", "https://p.example/x", "완료 요약", null, null, null, null, 0, 3, "  ",
                objectMapper.writeValueAsString(new AnsweredClarification("스택?",
                        ClarificationRequest.InputType.TEXT, List.of(), false, "순수 HTML")));
        // RUNNING · 빈 previewUrl
        insertRun("running", "RUNNING", "", null, null, null, null, null, 0, 3, null, null);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from approvals where task_id like ?", PREFIX + "%");
        jdbc.update("delete from agent_runs where task_id like ?", PREFIX + "%");
        jdbc.update("delete from users where github_user_id like ?", PREFIX + "%");
    }

    @Test
    void theResponseIsTheSameJsonAsTheOldFiveReadAssembly() throws Exception {
        for (String task : List.of("failed-blocked", "failed-retryable", "failed-exhausted", "waiting-input", "done",
                "running")) {
            String taskId = PREFIX + task;
            String before = objectMapper.writeValueAsString(oldResponse(taskId, ownerId));
            String after = objectMapper.writeValueAsString(agentController.getTaskStatus(ownerId, taskId).getBody());

            assertThat(after).as(task).isEqualTo(before);
        }

        // 고정한 값이 실제로 그 분기를 탔는지도 본다 — 모두 null/false 로 같아서 통과하는 일이 없게.
        TaskStatusResponse blocked = agentController.getTaskStatus(ownerId, PREFIX + "failed-blocked").getBody();
        assertThat(blocked.retryable()).isFalse();
        assertThat(blocked.pendingApprovalId()).isEqualTo(oldestPendingApprovalId(PREFIX + "failed-blocked"));
        assertThat(agentController.getTaskStatus(ownerId, PREFIX + "failed-retryable").getBody().retryable()).isTrue();
        assertThat(agentController.getTaskStatus(ownerId, PREFIX + "waiting-input").getBody().clarification()).isNotNull();
        assertThat(agentController.getTaskStatus(ownerId, PREFIX + "done").getBody().answeredClarification()).isNotNull();
    }

    @Test
    void anotherUsersTaskIsStillNotFound() {
        assertThat(oldResponse(PREFIX + "done", otherUserId)).isNull();
        assertThatThrownBy(() -> agentController.getTaskStatus(otherUserId, PREFIX + "done"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> agentController.getTaskStatus(ownerId, PREFIX + "missing"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void oneRequestMakesFarFewerRoundTrips() {
        String taskId = PREFIX + "failed-blocked";
        oldResponse(taskId, ownerId);                        // 준비 쿼리·캐시를 데운다
        agentController.getTaskStatus(ownerId, taskId);

        int before = countCalls(() -> oldResponse(taskId, ownerId));
        int after = countCalls(() -> agentController.getTaskStatus(ownerId, taskId));

        // 진단(실측 약 25문장)과 같은 크기인지, 그리고 완료 기준(7 수준)인지
        assertThat(before).as("이전 경로").isGreaterThanOrEqualTo(20);
        assertThat(after).as("새 경로").isLessThanOrEqualTo(8);
        System.out.printf("[#619] JDBC round trips per request: before=%d after=%d%n", before, after);
    }

    // ── 예전 경로: 바뀌기 전 AgentController#getTaskStatus 를 그대로 옮겨 적은 것 ─────────────────

    private TaskStatusResponse oldResponse(String taskId, Long userId) {
        AgentTask task = taskStore.getOwned(taskId, userId);
        if (task == null) {
            return null;
        }
        AgentTaskFailure failure = taskStore.getFailure(taskId, userId);
        Long pendingApprovalId = agentOrchestrator.findPendingApprovalId(taskId);
        boolean retryable = failure != null
                && task.status() == TaskStatus.FAILED
                && failure.retryable()
                && pendingApprovalId == null;
        return new TaskStatusResponse(
                task.taskId(),
                task.status(),
                task.previewUrl() != null && !task.previewUrl().isBlank(),
                task.summary(),
                task.error(),
                task.question(),
                failure == null ? null : failure.logExcerpt(),
                failure == null ? null : failure.suggestedFix(),
                failure == null ? 0 : failure.attempt(),
                failure == null ? 0 : failure.maxAttempts(),
                retryable,
                pendingApprovalId,
                taskStore.getClarification(taskId),
                taskStore.getAnsweredClarification(taskId)
        );
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private long insertUser(String name) {
        String githubUserId = PREFIX + name + "-" + System.nanoTime();
        jdbc.update("insert into users (github_user_id, user_name) values (?, ?)", githubUserId, PREFIX + name);
        return jdbc.queryForObject("select user_id from users where github_user_id = ?", Long.class, githubUserId);
    }

    private void insertRun(String task, String status, String previewUrl, String summary, String error,
                           String question, String failureLog, String suggestedFix, int attempt, int maxAttempts,
                           String clarificationJson, String answeredClarificationJson) {
        jdbc.update("""
                        insert into agent_runs (task_id, user_id, status, plan_json, attempt, max_attempts, preview_url,
                                                summary, error, question, failure_log, suggested_fix,
                                                clarification_json, answered_clarification_json, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                // plan 은 응답에 쓰이지 않는 큰 값이다 — 예전 경로는 이것을 매번 실어 왔다
                PREFIX + task, ownerId, status, "{\"steps\":[],\"pad\":\"" + "x".repeat(8_000) + "\"}",
                attempt, maxAttempts, previewUrl, summary, error, question, failureLog, suggestedFix,
                clarificationJson, answeredClarificationJson, LocalDateTime.now().minusMinutes(5));
    }

    private void insertApproval(String task, String type, String status) {
        jdbc.update("""
                        insert into approvals (user_id, task_id, approval_type, status, summary)
                        values (?, ?, ?, ?, '요약')
                        """, ownerId, PREFIX + task, type, status);
    }

    private Long oldestPendingApprovalId(String taskId) {
        return jdbc.queryForObject(
                "select min(approval_id) from approvals where task_id = ? and status = 'PENDING'", Long.class, taskId);
    }

    // ── 요청 스레드의 JDBC 왕복 세기 ───────────────────────────────────────────

    private int countCalls(Runnable request) {
        AtomicInteger calls = new AtomicInteger();
        CALLS.set(calls);
        try {
            request.run();
        } finally {
            CALLS.remove();
        }
        return calls.get();
    }

    /** 서버로 가는 호출: 문장 실행, 그리고 트랜잭션 의례(드라이버가 SET·COMMIT 을 보낸다). */
    private static final Set<String> CONNECTION_ROUND_TRIPS =
            Set.of("setAutoCommit", "setReadOnly", "setTransactionIsolation", "commit", "rollback");
    private static final Set<String> STATEMENT_ROUND_TRIPS =
            Set.of("execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "executeBatch");

    @TestConfiguration
    static class CountingDataSourceConfig {

        @Bean
        static BeanPostProcessor countingDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof DataSource dataSource)) {
                        return bean;
                    }
                    return proxy(DataSource.class, dataSource, (target, method, args) -> {
                        Object result = invoke(target, method, args);
                        return result instanceof Connection connection ? countingConnection(connection) : result;
                    });
                }
            };
        }

        private static Connection countingConnection(Connection connection) {
            return proxy(Connection.class, connection, (target, method, args) -> {
                if (CONNECTION_ROUND_TRIPS.contains(method.getName())) {
                    count();
                }
                Object result = invoke(target, method, args);
                return result instanceof Statement statement ? countingStatement(statement) : result;
            });
        }

        private static Statement countingStatement(Statement statement) {
            Class<?>[] interfaces = statement.getClass().getInterfaces().length == 0
                    ? new Class<?>[]{Statement.class}
                    : allInterfaces(statement.getClass());
            return (Statement) Proxy.newProxyInstance(TaskStatusQueryIdentityTest.class.getClassLoader(), interfaces,
                    (proxy, method, args) -> {
                        if (STATEMENT_ROUND_TRIPS.contains(method.getName())) {
                            count();
                        }
                        return invoke(statement, method, args);
                    });
        }

        private static Class<?>[] allInterfaces(Class<?> type) {
            java.util.LinkedHashSet<Class<?>> all = new java.util.LinkedHashSet<>();
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                for (Class<?> i : c.getInterfaces()) {
                    if (i.getName().startsWith("java.sql.")) {
                        all.add(i);
                    }
                }
            }
            if (all.isEmpty()) {
                all.add(Statement.class);
            }
            return all.toArray(Class<?>[]::new);
        }

        private static void count() {
            AtomicInteger calls = CALLS.get();
            if (calls != null) {
                calls.incrementAndGet();
            }
        }

        @SuppressWarnings("unchecked")
        private static <T> T proxy(Class<T> type, T target, TargetHandler handler) {
            InvocationHandler invocation = (proxy, method, args) -> handler.handle(target, method, args);
            return (T) Proxy.newProxyInstance(TaskStatusQueryIdentityTest.class.getClassLoader(),
                    new Class<?>[]{type}, invocation);
        }

        private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        @FunctionalInterface
        interface TargetHandler {
            Object handle(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable;
        }
    }
}
