package com.example.dvely.agent.infrastructure.store;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InputWaitStore {

    private final TaskStore taskStore;

    public Optional<String> consume(String taskId) {
        return taskStore.consumeInput(taskId);
    }

    public boolean supply(String taskId, Long ownerUserId, String value) {
        return taskStore.supplyInput(taskId, ownerUserId, value);
    }
}
