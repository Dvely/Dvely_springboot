package com.example.dvely.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import com.example.dvely.deployment.domain.repository.DeploymentHistoryRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 배포 확정은 이력당 한 번만 통과한다(#511 B10) — 실제 MySQL 에서 조건부 UPDATE 와 행 잠금을 확인한다.
 * 웹훅과 StuckDeploymentRecoveryWorker 가 같은 이력을 동시에 확정하려 들 때 뒤에 온 쪽이 지는지가 핵심이다.
 */
@SpringBootTest
class DeploymentHistorySettleIntegrationTest {

    @Autowired
    private DeploymentHistoryRepository repository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private UserRepository userRepository;

    private final List<Long> seeded = new ArrayList<>();
    private Long userId;

    @BeforeEach
    void seedUser() {
        // deployment_histories.user_id 에 FK 가 걸려 있다
        userId = userRepository.save(new User(new GithubId("settle-" + System.nanoTime()), "octo", null)).getId();
    }

    @AfterEach
    void cleanUp() {
        seeded.forEach(id -> jdbc.update("delete from deployment_histories where history_id = ?", id));
        jdbc.update("delete from users where user_id = ?", userId);
    }

    @Test
    void openHistorySettlesOnceAndTheSecondAttemptLoses() {
        long id = seed("IN_PROGRESS");

        assertThat(repository.settle(id, DeployStatus.LIVE)).isTrue();
        assertThat(repository.settle(id, DeployStatus.FAILED)).isFalse();
        assertThat(status(id)).isEqualTo("LIVE");
    }

    @Test
    void pendingHistoryIsAlsoOpen() {
        long id = seed("PENDING");

        assertThat(repository.settle(id, DeployStatus.FAILED)).isTrue();
        assertThat(status(id)).isEqualTo("FAILED");
    }

    @Test
    void alreadyTerminalHistoryIsNeverOverwritten() {
        long live = seed("LIVE");
        long failed = seed("FAILED");

        assertThat(repository.settle(live, DeployStatus.FAILED)).isFalse();
        assertThat(repository.settle(failed, DeployStatus.LIVE)).isFalse();
        assertThat(status(live)).isEqualTo("LIVE");
        assertThat(status(failed)).isEqualTo("FAILED");
    }

    @Test
    void concurrentSettlerWaitsForTheFirstCommitAndThenLoses() throws Exception {
        long id = seed("IN_PROGRESS");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch firstHoldsRow = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> tx.execute(status -> {
                boolean won = repository.settle(id, DeployStatus.LIVE);
                firstHoldsRow.countDown();
                awaitQuietly(releaseFirst);
                return won;
            }));
            assertThat(firstHoldsRow.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Boolean> second = pool.submit(() -> tx.execute(status -> repository.settle(id, DeployStatus.FAILED)));
            // 앞쪽이 커밋하기 전에는 행 잠금에 막혀 결과를 내지 못한다
            assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

            releaseFirst.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(10, TimeUnit.SECONDS)).isFalse();
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
        assertThat(status(id)).isEqualTo("LIVE");
    }

    private long seed(String status) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    insert into deployment_histories
                        (user_id, project_id, deploy_target_type, deployed_url, status,
                         correlation_id, commit_sha, title, triggered_at, updated_at)
                    values (?, ?, 'LATEST', 'https://example.com/settle', ?, ?, 'commit-settle', '확정 테스트', ?, ?)
                    """, new String[] {"history_id"});
            statement.setLong(1, userId);
            statement.setLong(2, 900_001L);
            statement.setString(3, status);
            statement.setString(4, "settle-" + System.nanoTime());
            statement.setObject(5, now);
            statement.setObject(6, now);
            return statement;
        }, keys);
        long id = keys.getKey().longValue();
        seeded.add(id);
        return id;
    }

    private String status(long id) {
        return jdbc.queryForObject("select status from deployment_histories where history_id = ?", String.class, id);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
