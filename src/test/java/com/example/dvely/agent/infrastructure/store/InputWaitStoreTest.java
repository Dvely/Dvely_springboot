package com.example.dvely.agent.infrastructure.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

class InputWaitStoreTest {

    private InputWaitStore store;

    @BeforeEach
    void setUp() {
        store = new InputWaitStore();
    }

    @Test
    void supply_정상_흐름() throws ExecutionException, InterruptedException {
        CompletableFuture<String> future = store.register("task-1");

        boolean result = store.supply("task-1", "hello");

        assertThat(result).isTrue();
        assertThat(future.get()).isEqualTo("hello");
    }

    @Test
    void supply_없는_taskId는_false() {
        boolean result = store.supply("unknown", "value");

        assertThat(result).isFalse();
    }

    @Test
    void supply_cancel_이후_호출시_false() {
        store.register("task-2");
        store.cancel("task-2");

        boolean result = store.supply("task-2", "value");

        assertThat(result).isFalse();
    }

    @Test
    void supply_이미_완료된_future에_재호출시_false() {
        CompletableFuture<String> future = store.register("task-3");
        future.complete("already-done");

        // pending에서 제거 후 complete 재시도 → false 반환
        boolean result = store.supply("task-3", "late-value");

        assertThat(result).isFalse();
    }

    @Test
    void register_동일_taskId_중복_등록시_기존_future_반환() {
        CompletableFuture<String> first = store.register("task-4");
        CompletableFuture<String> second = store.register("task-4");

        assertThat(first).isSameAs(second);
    }

    @Test
    void cancel_pending_future를_취소시킴() {
        CompletableFuture<String> future = store.register("task-5");

        store.cancel("task-5");

        assertThat(future.isCancelled()).isTrue();
    }
}
