package com.example.dvely.preview.infrastructure.persistence.entity;

import com.example.dvely.preview.domain.value.PreviewRuntimeType;
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
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 프로젝트당 프리뷰 런타임 설정(1행). runtime_type 등은 문자열 컬럼에 enum.name() 으로 저장하고
 * 읽을 때 valueOf 로 되돌린다 — provisioned_databases 와 같은 방식이라 enum 순서 변경에 안전하다.
 */
@Entity
@Table(name = "preview_runtime_configs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PreviewRuntimeConfigEntity {

    private static final String DEFAULT_API_PREFIX = "/api";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "project_id", nullable = false, unique = true)
    private Long projectId;

    @Column(name = "runtime_type", nullable = false, length = 20)
    private String runtimeType;

    @Column(name = "start_command")
    private String startCommand;

    @Column(name = "api_path_prefix", nullable = false, length = 64)
    private String apiPathPrefix;

    @Column(name = "health_path")
    private String healthPath;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private PreviewRuntimeConfigEntity(Long projectId, PreviewRuntimeType runtimeType,
                                       String startCommand, String apiPathPrefix, String healthPath) {
        this.projectId = projectId;
        this.runtimeType = runtimeType.name();
        this.startCommand = startCommand;
        this.apiPathPrefix = (apiPathPrefix == null || apiPathPrefix.isBlank())
                ? DEFAULT_API_PREFIX : apiPathPrefix;
        this.healthPath = healthPath;
    }

    public static PreviewRuntimeConfigEntity of(Long projectId, PreviewRuntimeType runtimeType,
                                                String startCommand, String apiPathPrefix, String healthPath) {
        return new PreviewRuntimeConfigEntity(projectId, runtimeType, startCommand, apiPathPrefix, healthPath);
    }

    /** upsert 의 update 쪽. project_id 는 그대로 두고 나머지를 갈아끼운다. */
    public void update(PreviewRuntimeType runtimeType, String startCommand,
                       String apiPathPrefix, String healthPath) {
        this.runtimeType = runtimeType.name();
        this.startCommand = startCommand;
        this.apiPathPrefix = (apiPathPrefix == null || apiPathPrefix.isBlank())
                ? DEFAULT_API_PREFIX : apiPathPrefix;
        this.healthPath = healthPath;
    }

    public PreviewRuntimeType runtimeTypeEnum() {
        return PreviewRuntimeType.valueOf(runtimeType);
    }
}
