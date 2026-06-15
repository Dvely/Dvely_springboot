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

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public AgentRunEventEntity(String taskId, String type, TaskStatus status, String message) {
        this.taskId = taskId;
        this.type = type;
        this.status = status.name();
        this.message = message;
    }

    public AgentTaskEvent toResult() {
        return new AgentTaskEvent(
                id,
                taskId,
                type,
                TaskStatus.valueOf(status),
                message,
                createdAt
        );
    }
}
