package com.example.dvely.agent.infrastructure.store;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class InputWaitStore {

    private final ConcurrentHashMap<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();

    /** 호출 즉시 반환되는 Future를 등록하고 반환. DeployAgentService가 .get()으로 블록한다. */
    public CompletableFuture<String> register(String taskId) {
        CompletableFuture<String> future = new CompletableFuture<>();
        CompletableFuture<String> existing = pending.putIfAbsent(taskId, future);
        if (existing != null) {
            log.warn("[InputWaitStore] 이미 등록된 taskId, 기존 future 반환: taskId={}", taskId);
            return existing;
        }
        log.debug("[InputWaitStore] 입력 대기 등록: taskId={}", taskId);
        return future;
    }

    /** 사용자 응답이 도착했을 때 호출. 블록 중인 thread를 재개한다. */
    public boolean supply(String taskId, String value) {
        CompletableFuture<String> future = pending.remove(taskId);
        if (future == null) {
            log.warn("[InputWaitStore] 대기 중인 taskId 없음: {}", taskId);
            return false;
        }
        boolean completed = future.complete(value);
        if (completed) {
            log.info("[InputWaitStore] 입력 전달 완료: taskId={} value={}", taskId, value);
        } else {
            log.warn("[InputWaitStore] future 이미 완료됨 (타임아웃/취소 선행): taskId={}", taskId);
        }
        return completed;
    }

    /** 타임아웃 등으로 정리할 때 사용 */
    public void cancel(String taskId) {
        CompletableFuture<String> future = pending.remove(taskId);
        if (future != null) future.cancel(true);
    }
}
