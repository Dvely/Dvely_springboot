package com.example.dvely.agent.infrastructure.persistence.entity;

import com.example.dvely.agent.application.dto.AgentTaskEvent;
import com.example.dvely.agent.application.dto.TaskStatus;
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

@Entity
@Table(name = "agent_run_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentRunEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long id;

    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @Column(name = "event_type", nullable = false, length = 40)
    private String type;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    /** 스텝 이벤트일 때 1-based 순번. 태스크 생명주기 이벤트에는 없다(null). */
    @Column(name = "step_index")
    private Integer stepIndex;

    /** 스텝 이벤트일 때 계획의 총 스텝 수. */
    @Column(name = "step_total")
    private Integer stepTotal;

    /** 스텝 이벤트일 때 그 스텝의 에이전트 종류(CODE/DEPLOY/...). */
    @Column(name = "agent_type", length = 30)
    private String agentType;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public AgentRunEventEntity(String taskId, String type, TaskStatus status, String message) {
        this(taskId, type, status, message, null, null, null);
    }

    public AgentRunEventEntity(String taskId,
                               String type,
                               TaskStatus status,
                               String message,
                               Integer stepIndex,
                               Integer stepTotal,
                               String agentType) {
        this.taskId = taskId;
        this.type = type;
        this.status = status.name();
        this.message = message;
        this.stepIndex = stepIndex;
        this.stepTotal = stepTotal;
        this.agentType = agentType;
    }

    public AgentTaskEvent toResult() {
        return new AgentTaskEvent(
                id,
                taskId,
                type,
                TaskStatus.valueOf(status),
                message,
                stepIndex,
                stepTotal,
                agentType,
                createdAt
        );
    }
}
