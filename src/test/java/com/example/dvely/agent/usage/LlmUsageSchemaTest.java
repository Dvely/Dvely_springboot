package com.example.dvely.agent.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.agent.domain.value.LlmUsage;
import com.example.dvely.agent.infrastructure.persistence.entity.LlmUsageEntity;
import com.example.dvely.agent.infrastructure.persistence.repository.SpringDataLlmUsageRepository;
import com.example.dvely.agent.infrastructure.usage.LlmUsagePhase;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code ddl-auto: validate} 는 nullable 여부도 FK 부재도 보지 않는다. V63 이 설계대로 내려앉았는지는
 * 실제 스키마를 직접 물어보는 것 말고 확인할 방법이 없다.
 *
 * <p>이 단위의 완료 기준("같은 시나리오의 전/후 토큰 합계를 비교할 수 있다")이 실제로 성립하는지도
 * 여기서 확인한다 — 행을 쓰고 태스크 단위로 합산해 본다.</p>
 */
@SpringBootTest
class LlmUsageSchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SpringDataLlmUsageRepository repository;

    @Test
    void v63MigrationApplied() {
        String applied = jdbcTemplate.queryForObject(
                "select coalesce(max(success), 0) from flyway_schema_history where version = '63'",
                String.class);

        assertThat("1".equals(applied) || "true".equalsIgnoreCase(applied)).isTrue();
    }

    @Test
    void attributionColumnsAreNullableSoACallOutsideAnyTaskIsStillCounted() {
        // 배포 실패 분석처럼 태스크 없이 도는 호출이 있다. NOT NULL 이면 그 호출은 기록될 수
        // 없고, 합계가 조용히 작아진다.
        Map<String, String> nullability = jdbcTemplate.queryForList(
                        """
                                select column_name, is_nullable
                                from information_schema.columns
                                where table_schema = database() and table_name = 'llm_usage'
                                """)
                .stream()
                .collect(Collectors.toMap(
                        row -> String.valueOf(row.get("COLUMN_NAME")).toLowerCase(),
                        row -> String.valueOf(row.get("IS_NULLABLE")).toUpperCase()));

        assertThat(nullability).containsEntry("task_id", "YES")
                .containsEntry("user_id", "YES")
                .containsEntry("project_id", "YES")
                .containsEntry("phase", "NO")
                .containsEntry("provider", "NO")
                .containsEntry("input_tokens", "NO")
                .containsEntry("cache_read_input_tokens", "NO");
    }

    @Test
    void hasNoForeignKeysSoItNeverJoinsAnotherTablesLockGraph() {
        // agent_runs 는 보존 정책으로 삭제된다. FK 가 있으면 비용 이력이 함께 사라지고, 계측
        // INSERT 가 실행 중인 태스크 행의 잠금을 기다리게 된다.
        Integer foreignKeys = jdbcTemplate.queryForObject(
                """
                        select count(*)
                        from information_schema.table_constraints
                        where table_schema = database()
                          and table_name = 'llm_usage'
                          and constraint_type = 'FOREIGN KEY'
                        """,
                Integer.class);

        assertThat(foreignKeys).isZero();
    }

    @Test
    void sumsATasksTokensAcrossEveryRoundItSpent() {
        String taskId = "usage-schema-test-" + System.nanoTime();
        repository.save(new LlmUsageEntity(taskId, 1L, 11L, LlmUsagePhase.DECISION,
                "GLM", "z-ai/glm-4.6", new LlmUsage(3_000, 400, 0, 0)));
        repository.save(new LlmUsageEntity(taskId, 1L, 11L, LlmUsagePhase.AGENT_RUN,
                "ANTHROPIC", "claude-opus-4-5-20251101", new LlmUsage(1_000, 200, 4_000, 12_000)));
        repository.save(new LlmUsageEntity(taskId, 1L, 11L, LlmUsagePhase.AGENT_RUN,
                "ANTHROPIC", "claude-opus-4-5-20251101", new LlmUsage(900, 150, 0, 16_000)));

        List<LlmUsageEntity> rounds = repository.findAllByTaskIdOrderByIdAsc(taskId);

        assertThat(rounds).hasSize(3);
        // 3,400 + 17,200 + 17,050 — 이 합계를 전/후로 비교하는 것이 이 단위의 완료 기준이다.
        assertThat(repository.sumTotalTokensByTaskId(taskId)).isEqualTo(37_650);
        // 라운드별로 남기 때문에 "캐시가 실제로 살아 있는가" 도 읽을 수 있다.
        assertThat(rounds.get(2).getCacheReadInputTokens()).isEqualTo(16_000);

        repository.deleteAll(rounds);
    }

    @Test
    void countsZeroForATaskThatHasNotSpentAnything() {
        assertThat(repository.sumTotalTokensByTaskId("task-that-never-ran")).isZero();
    }
}
