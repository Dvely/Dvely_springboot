package com.example.dvely.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * application.yaml 의 logging.pattern.level 이 실제 콘솔 줄에 task=·deploy= 를 붙이는지(#511 B12).
 * 패턴은 Spring Boot 가 기동하며 logback 에 넣으므로 컨텍스트를 띄워 실제 출력을 본다.
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class WorkLogPatternTest {

    private static final Logger log = LoggerFactory.getLogger(WorkLogPatternTest.class);

    @Test
    void workIdsAppearOnlyOnLinesInsideTheirScope(CapturedOutput output) {
        try (WorkLogContext ignored = WorkLogContext.task("task-abc")) {
            log.warn("pattern-marker-task");
        }
        try (WorkLogContext ignored = WorkLogContext.deployment(42L)) {
            log.warn("pattern-marker-deploy");
        }
        log.warn("pattern-marker-none");

        assertThat(line(output, "pattern-marker-task")).contains("] task=task-abc ").doesNotContain("deploy=");
        assertThat(line(output, "pattern-marker-deploy")).contains("] deploy=42 ").doesNotContain("task=");
        assertThat(line(output, "pattern-marker-none")).contains("WARN [] ").doesNotContain("task=").doesNotContain("deploy=");
    }

    private static String line(CapturedOutput output, String marker) {
        return output.getOut().lines().filter(l -> l.contains(marker)).findFirst().orElseThrow();
    }
}
