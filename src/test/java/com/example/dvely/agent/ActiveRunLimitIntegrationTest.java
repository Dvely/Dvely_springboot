package com.example.dvely.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import com.example.dvely.common.limit.UserLimitExceededException;
import com.example.dvely.common.limit.UserLimitProperties;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사용자별 동시 런 상한(#509)이 같은 사용자의 동시 요청에서도 지켜지는지 실제 MySQL 로 본다.
 *
 * <p>채팅 전송은 사용자 행을 잠그기 전에 대화를 읽는다. REPEATABLE READ 에서 그 읽기가 트랜잭션의 스냅샷을 정하므로,
 * 같은 트랜잭션 안에서 런을 세면 앞서 잠금을 쥐고 커밋한 요청의 런이 보이지 않는다. 여기서는 두 요청이 모두 스냅샷을
 * 잡은 뒤에 경합하게 만들어 그 상황을 그대로 재현한다.</p>
 */
@SpringBootTest
class ActiveRunLimitIntegrationTest {

    @Autowired
    private AgentOrchestrator agentOrchestrator;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private UserLimitProperties limits;

    @Test
    void twoRequestsThatReadBeforeTakingTheLockCannotBothTakeTheLastSlot() throws Exception {
        Long userId = userRepository.save(
                new User(new GithubId("run-limit-" + System.nanoTime()), "octo", null)).getId();
        // 상한 바로 아래까지 채운다. PENDING 은 워커가 집지 않는 상태라 테스트 도중 바뀌지 않는다.
        for (int i = 0; i < limits.maxActiveRuns() - 1; i++) {
            taskStore.save(new AgentTask("it-limit-" + System.nanoTime(), userId, null, null,
                    TaskStatus.PENDING, null, null, null, null, Instant.now()));
        }

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch snapshotsTaken = new CountDownLatch(2);
        Callable<Boolean> request = () -> {
            try {
                tx.executeWithoutResult(status -> {
                    jdbcTemplate.queryForObject(
                            "select count(*) from agent_runs where user_id = ?", Long.class, userId);
                    snapshotsTaken.countDown();
                    try {
                        assertThat(snapshotsTaken.await(10, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                    agentOrchestrator.createPending(userId, null);
                });
                return true;
            } catch (UserLimitExceededException refused) {
                return false;
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Boolean> outcomes;
        try {
            List<Future<Boolean>> futures = pool.invokeAll(List.of(request, request));
            outcomes = List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdown();
        }

        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        Long working = jdbcTemplate.queryForObject(
                "select count(*) from agent_runs where user_id = ? and status in ('PENDING','QUEUED','RETRY_WAIT','RUNNING')",
                Long.class, userId);
        assertThat(working).isEqualTo(limits.maxActiveRuns());
    }
}
