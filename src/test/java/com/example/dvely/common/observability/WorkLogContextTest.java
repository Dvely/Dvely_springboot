package com.example.dvely.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class WorkLogContextTest {

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void theIdIsSetOnlyInsideTheScope() {
        try (WorkLogContext ignored = WorkLogContext.task("task-1")) {
            assertThat(MDC.get(WorkLogContext.TASK_ID)).isEqualTo("task-1");
        }
        // 실행기 스레드는 풀로 돌아가 다음 작업을 받는다 — 남으면 다음 작업 로그에 남의 id 가 붙는다
        assertThat(MDC.get(WorkLogContext.TASK_ID)).isNull();
    }

    @Test
    void closingRestoresTheOuterValue() {
        try (WorkLogContext outer = WorkLogContext.deployment(1L)) {
            try (WorkLogContext inner = WorkLogContext.deployment(2L)) {
                assertThat(MDC.get(WorkLogContext.DEPLOYMENT_ID)).isEqualTo("2");
            }
            assertThat(MDC.get(WorkLogContext.DEPLOYMENT_ID)).isEqualTo("1");
        }
        assertThat(MDC.get(WorkLogContext.DEPLOYMENT_ID)).isNull();
    }

    @Test
    void lineBreaksCannotForgeLogLines() {
        try (WorkLogContext ignored = WorkLogContext.task("a\r\nWARN fake")) {
            assertThat(MDC.get(WorkLogContext.TASK_ID)).isEqualTo("a__WARN fake");
        }
    }
}
