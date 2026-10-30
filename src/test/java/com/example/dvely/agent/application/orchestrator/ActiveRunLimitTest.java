package com.example.dvely.agent.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.common.limit.UserLimitExceededException;
import com.example.dvely.common.limit.UserLimitProperties;
import com.example.dvely.common.response.ErrorCode;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ActiveRunLimitTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final ActiveRunLimit limit =
            new ActiveRunLimit(userRepository, taskStore, new UserLimitProperties(2, 3));

    @Test
    void locksTheUserRowBeforeCounting() {
        // 잠금 전에 세면 같은 사용자의 동시 요청이 둘 다 빈자리를 본다.
        when(taskStore.countWorkingRuns(1L)).thenReturn(1L);

        limit.check(1L);

        InOrder order = inOrder(userRepository, taskStore);
        order.verify(userRepository).findByIdForUpdate(1L);
        order.verify(taskStore).countWorkingRuns(1L);
    }

    @Test
    void passesBelowTheLimit() {
        when(taskStore.countWorkingRuns(1L)).thenReturn(1L);

        assertThatCode(() -> limit.check(1L)).doesNotThrowAnyException();
    }

    @Test
    void refusesAtTheLimitWithTheRunCode() {
        when(taskStore.countWorkingRuns(1L)).thenReturn(2L);

        assertThatThrownBy(() -> limit.check(1L))
                .isInstanceOfSatisfying(UserLimitExceededException.class, exception -> {
                    org.assertj.core.api.Assertions.assertThat(exception.errorCode())
                            .isEqualTo(ErrorCode.ACTIVE_RUN_LIMIT_EXCEEDED);
                    org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                            .contains("2개").contains("다시 시도");
                });
    }
}
