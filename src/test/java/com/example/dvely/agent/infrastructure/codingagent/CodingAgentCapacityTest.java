package com.example.dvely.agent.infrastructure.codingagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dvely.common.limit.GlobalLimitProperties;
import com.example.dvely.common.limit.UserLimitExceededException;
import com.example.dvely.common.response.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 서버 전체 코딩 에이전트 상한(#624). */
class CodingAgentCapacityTest {

    private final CodingAgentCapacity capacity = new CodingAgentCapacity(new GlobalLimitProperties(4, 2));

    @Test
    void refusesTheOneOverTheLimitWithoutWaiting() {
        CodingAgentCapacity.Slot first = capacity.acquire(1L);
        CodingAgentCapacity.Slot second = capacity.acquire(2L);

        assertThatThrownBy(() -> capacity.acquire(3L))
                .isInstanceOfSatisfying(UserLimitExceededException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.CODING_AGENT_CAPACITY_EXCEEDED);
                    assertThat(e.errorCode().getStatus()).isEqualTo(429);
                    // 사용자에게 그대로 보이는 문장 — 무엇이 됐고 무엇을 하면 되는지
                    assertThat(e.getMessage()).contains("변경은 반영하지 않았습니다").contains("잠시 뒤 다시 시도");
                });
        assertThat(capacity.inUse()).isEqualTo(2);

        first.close();
        second.close();
    }

    @Test
    void closingGivesTheSlotBackOnceNoMatterHowOftenItIsClosed() {
        CodingAgentCapacity.Slot slot = capacity.acquire(1L);
        slot.close();
        slot.close();   // 두 번 닫아도 자리가 둘이 되지 않는다

        assertThat(capacity.inUse()).isZero();
        try (var _ = capacity.acquire(1L); var _ = capacity.acquire(2L)) {
            assertThatThrownBy(() -> capacity.acquire(3L)).isInstanceOf(UserLimitExceededException.class);
        }
        assertThat(capacity.inUse()).isZero();
    }

    @Test
    void manyAtOnceNeverHoldMoreThanTheLimit() throws Exception {
        int callers = 16;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger refused = new AtomicInteger();
        List<Future<CodingAgentCapacity.Slot>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
            for (int i = 0; i < callers; i++) {
                long userId = i;
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        return capacity.acquire(userId);
                    } catch (UserLimitExceededException e) {
                        refused.incrementAndGet();
                        return null;
                    }
                }));
            }
            start.countDown();
            List<CodingAgentCapacity.Slot> held = new ArrayList<>();
            for (Future<CodingAgentCapacity.Slot> result : results) {
                CodingAgentCapacity.Slot slot = result.get();
                if (slot != null) {
                    held.add(slot);
                }
            }

            assertThat(held).hasSize(2);
            assertThat(refused).hasValue(callers - 2);
            held.forEach(CodingAgentCapacity.Slot::close);
        }
        assertThat(capacity.inUse()).isZero();
    }
}
