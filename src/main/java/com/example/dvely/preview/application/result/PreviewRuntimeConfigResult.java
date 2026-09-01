package com.example.dvely.preview.application.result;

import com.example.dvely.preview.domain.value.PreviewRuntimeType;
import com.example.dvely.preview.infrastructure.persistence.entity.PreviewRuntimeConfigEntity;

/**
 * 프리뷰 런타임 설정 조회/저장 결과. source 로 이 값이 어디서 왔는지 구분한다:
 * STORED(사용자가 저장) · DEFAULT(설정 없음, 안전 기본값 STATIC) · DETECTED(클론 내용 자동 감지).
 */
public record PreviewRuntimeConfigResult(
        Long projectId,
        String runtimeType,
        String startCommand,
        String apiPathPrefix,
        String healthPath,
        String dbEngine,
        String source
) {
    public static PreviewRuntimeConfigResult stored(PreviewRuntimeConfigEntity e) {
        return new PreviewRuntimeConfigResult(e.getProjectId(), e.getRuntimeType(), e.getStartCommand(),
                e.getApiPathPrefix(), e.getHealthPath(), e.getDbEngine(), "STORED");
    }

    public static PreviewRuntimeConfigResult defaultStatic(Long projectId) {
        return new PreviewRuntimeConfigResult(projectId, PreviewRuntimeType.STATIC.name(),
                null, "/api", null, "MYSQL", "DEFAULT");
    }

    public static PreviewRuntimeConfigResult detected(Long projectId, PreviewRuntimeType type) {
        return new PreviewRuntimeConfigResult(projectId, type.name(), null, "/api", null, "MYSQL", "DETECTED");
    }

    public PreviewRuntimeType runtimeTypeEnum() {
        return PreviewRuntimeType.valueOf(runtimeType);
    }
}
