package com.example.dvely.aiaccount.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.port.out.CodingAgentPort;
import com.example.dvely.agent.application.port.out.CodingAgentResult;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.codingagent.CodingAgentRouter;
import com.example.dvely.aiaccount.domain.model.AiProviderCredential;
import com.example.dvely.aiaccount.domain.repository.AiProviderCredentialRepository;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A1 의 실측 증거(#337): 코딩 에이전트가 도는 동안 커넥션이 점유되지 않는다.
 *
 * <p>2026-09-08 dev 고갈의 핵심은 "커넥션 하나를 오래 쥔다" 였다. 예전
 * {@code CodingAgentExecutionService.run} 은 {@code @Transactional(readOnly = true)} 안에서
 * 컨테이너의 CLI 가 끝날 때까지 최대 10분을 기다렸고, 동시 CODE 태스크 수만큼 곱해졌다.
 * 그래서 여기서는 CLI 자리에 "잠깐 도는 가짜 실행"을 끼워 넣고, <b>그 실행 중에</b> 풀의 active
 * 커넥션 수를 직접 샘플링해 비교한다.</p>
 *
 * <p>비교 대상은 같은 테스트 안에서 만든다 — 옛 구조를 {@link TransactionTemplate} 로 그대로
 * 재현해(readOnly 트랜잭션 + DB 접촉) 같은 샘플링을 돌린다. 절대값 대신 두 값을 비교하는
 * 이유는 이 컨텍스트에 {@code @EnableScheduling} 워커들이 함께 떠 있어 풀에 잡음이 있기
 * 때문이다. 잡음은 최댓값만 올리므로 <b>최솟값</b>을 본다 — 트랜잭션이 커넥션을 붙들고 있으면
 * 최솟값이 0 으로 내려갈 수 없고, 붙들지 않으면 내려간다.</p>
 */
@SpringBootTest
class CodingAgentConnectionHoldIntegrationTest {

    /** 실제 CLI 대신 끼워 넣는 가짜 실행. 샘플이 충분히 모이도록 이 정도는 돌린다. */
    private static final Duration FAKE_CLI_DURATION = Duration.ofMillis(600);
    private static final long SAMPLE_INTERVAL_MS = 10L;

    @MockitoBean
    private CodingAgentRouter codingAgentRouter;

    @Autowired
    private CodingAgentExecutionService codingAgentExecutionService;
    @Autowired
    private AiProviderCredentialRepository credentialRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("CLI 가 도는 동안 트랜잭션도 커넥션 점유도 없다 — 옛 구조는 커넥션을 계속 쥐고 있었다")
    void codingAgentRunDoesNotHoldAConnectionWhileTheCliRuns() {
        Long userId = seedUserWithCredential();
        HikariPoolMXBean pool = ((HikariDataSource) dataSource).getHikariPoolMXBean();

        AtomicBoolean transactionActiveDuringRun = new AtomicBoolean(true);
        AtomicInteger minActiveDuringRun = new AtomicInteger(Integer.MAX_VALUE);

        CodingAgentPort port = Mockito.mock(CodingAgentPort.class);
        when(port.run(any())).thenAnswer(invocation -> {
            transactionActiveDuringRun.set(TransactionSynchronizationManager.isActualTransactionActive());
            minActiveDuringRun.set(sampleMinimumActiveConnections(pool));
            return CodingAgentResult.succeeded("done", "");
        });
        when(codingAgentRouter.route(any())).thenReturn(port);

        codingAgentExecutionService.run(userId, AiProvider.CLAUDE_CODE, "프롬프트", "/tmp/ws");

        // 옛 구조 재현 — readOnly 트랜잭션 안에서 DB 를 한 번 만진 뒤 같은 샘플링을 한다.
        TransactionTemplate readOnlyTransaction = new TransactionTemplate(transactionManager);
        readOnlyTransaction.setReadOnly(true);
        int minActiveInsideTransaction = readOnlyTransaction.execute(status -> {
            credentialRepository.findByUserIdAndProvider(userId, AiProvider.ANTHROPIC);
            return sampleMinimumActiveConnections(pool);
        });

        assertThat(transactionActiveDuringRun)
                .as("CLI 실행 중에는 트랜잭션이 열려 있으면 안 된다(#337)")
                .isFalse();
        assertThat(minActiveInsideTransaction)
                .as("옛 구조 재현: 트랜잭션이 커넥션을 붙들고 있으므로 active 가 0 으로 내려가지 않는다")
                .isGreaterThanOrEqualTo(1);
        assertThat(minActiveDuringRun)
                .as("지금 구조: CLI 가 도는 동안 이 스레드는 커넥션을 쥐고 있지 않다 "
                        + "(옛 구조 최솟값=%d)", minActiveInsideTransaction)
                .hasValue(0);
    }

    /**
     * 가짜 CLI 가 도는 동안 풀의 active 를 반복해서 재고 그중 최솟값을 돌려준다.
     * 최솟값을 쓰는 이유는 클래스 javadoc 참고 — 스케줄 워커의 순간 점유를 걸러낸다.
     */
    private int sampleMinimumActiveConnections(HikariPoolMXBean pool) {
        int minimum = Integer.MAX_VALUE;
        long deadline = System.nanoTime() + FAKE_CLI_DURATION.toNanos();
        while (System.nanoTime() < deadline) {
            minimum = Math.min(minimum, pool.getActiveConnections());
            try {
                Thread.sleep(SAMPLE_INTERVAL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return minimum;
    }

    private Long seedUserWithCredential() {
        User owner = userRepository.save(
                new User(new GithubId("i337-tx-boundary-" + System.nanoTime()), "octo", null));
        credentialRepository.save(new AiProviderCredential(
                owner.getId(), AiProvider.ANTHROPIC, "sk-ant-api03-test-key", "test"));
        return owner.getId();
    }
}
