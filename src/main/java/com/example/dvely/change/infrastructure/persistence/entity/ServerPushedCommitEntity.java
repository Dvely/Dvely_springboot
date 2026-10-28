package com.example.dvely.change.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/** 서버 측 git 이 실제로 push 한 커밋(V69, #532). 반영 범위가 커밋 메시지 대신 이것을 믿는다. */
@Entity
@Table(name = "server_pushed_commits")
@IdClass(ServerPushedCommitEntity.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServerPushedCommitEntity {

    public static final String TASK = "TASK";
    public static final String REVERT = "REVERT";

    @Id
    @Column(name = "commit_sha", length = 40)
    private String commitSha;

    @Id
    @Column(name = "repository", length = 255)
    private String repository;

    @Column(name = "task_id", length = 64)
    private String taskId;

    @Column(name = "kind", nullable = false, length = 10)
    private String kind;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public ServerPushedCommitEntity(String commitSha, String repository, String taskId, String kind) {
        this.commitSha = commitSha;
        this.repository = repository;
        this.taskId = taskId;
        this.kind = kind;
    }

    public boolean isRevert() {
        return REVERT.equals(kind);
    }

    public record Key(String commitSha, String repository) implements Serializable {
        public Key() {
            this(null, null);
        }
    }
}
