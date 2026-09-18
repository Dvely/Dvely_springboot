package com.example.dvely.agent.infrastructure.persistence.entity;

import com.example.dvely.agent.domain.value.LlmUsage;
import com.example.dvely.agent.infrastructure.usage.LlmUsagePhase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * LLM 호출 한 건의 토큰 사용량.
 *
 * <p>비밀은 담지 않는다 — 프롬프트도 응답도 여기 오지 않고, 어느 제공자의 어느 모델이 몇 토큰을
 * 읽고 썼는지만 남는다. 그래서 {@code toString} 을 따로 두지 않아도 위험하지 않지만, 이 도메인의
 * 규칙대로 Lombok {@code @ToString}/{@code @Data} 는 쓰지 않는다.</p>
 */
@Entity
@Table(name = "llm_usage")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LlmUsageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "llm_usage_id")
    private Long id;

    /** 스코프 없이 난 호출은 null — 귀속은 없어도 합계에서 빠지지는 않는다. */
    @Column(name = "task_id", length = 64)
    private String taskId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "phase", nullable = false, length = 30)
    private String phase;

    @Column(name = "provider", nullable = false, length = 30)
    private String provider;

    @Column(name = "model", nullable = false, length = 120)
    private String model;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    @Column(name = "cache_creation_input_tokens", nullable = false)
    private long cacheCreationInputTokens;

    @Column(name = "cache_read_input_tokens", nullable = false)
    private long cacheReadInputTokens;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public LlmUsageEntity(String taskId,
                          Long userId,
                          Long projectId,
                          LlmUsagePhase phase,
                          String provider,
                          String model,
                          LlmUsage usage) {
        this.taskId = taskId;
        this.userId = userId;
        this.projectId = projectId;
        this.phase = phase.name();
        this.provider = provider;
        this.model = model;
        this.inputTokens = usage.inputTokens();
        this.outputTokens = usage.outputTokens();
        this.cacheCreationInputTokens = usage.cacheCreationInputTokens();
        this.cacheReadInputTokens = usage.cacheReadInputTokens();
    }
}
