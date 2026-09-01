package com.example.dvely.preview.application.service;

import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.preview.application.result.PreviewRuntimeConfigResult;
import com.example.dvely.preview.domain.value.PreviewRuntimeType;
import com.example.dvely.preview.infrastructure.persistence.entity.PreviewRuntimeConfigEntity;
import com.example.dvely.preview.infrastructure.persistence.repository.SpringDataPreviewRuntimeConfigRepository;
import com.example.dvely.project.domain.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 프리뷰 런타임 설정의 조회/저장, 그리고 프로비저닝 시점의 "실제로 쓸 설정" 해석을 담당한다.
 *
 * 조회/저장은 프로젝트 소유자만(인프라 탭 다른 엔드포인트와 같은 소유권 검사). 프로비저닝 시점의
 * resolveForProvision 은 이미 부팅 중인 세션 내부에서 호출되므로 소유권을 다시 보지 않고,
 * 저장된 설정이 없으면 컨테이너 클론 내용으로 자동 감지한다.
 */
@Service
@RequiredArgsConstructor
public class PreviewRuntimeConfigService {

    private final SpringDataPreviewRuntimeConfigRepository repository;
    private final ProjectRepository projectRepository;
    private final PreviewRuntimeDetector detector;

    @Transactional(readOnly = true)
    public PreviewRuntimeConfigResult get(Long ownerUserId, Long projectId) {
        ensureOwned(ownerUserId, projectId);
        return repository.findByProjectId(projectId)
                .map(PreviewRuntimeConfigResult::stored)
                .orElseGet(() -> PreviewRuntimeConfigResult.defaultStatic(projectId));
    }

    @Transactional
    public PreviewRuntimeConfigResult upsert(Long ownerUserId, Long projectId, PreviewRuntimeType runtimeType,
                                             String startCommand, String apiPathPrefix, String healthPath,
                                             String dbEngine) {
        ensureOwned(ownerUserId, projectId);
        // JAVA_FULLSTACK 실행은 아직 없다(② 예정). 저장을 허용하면 저장은 성공하고 그 다음 프리뷰
        // 부팅에서야 깨져, 실패 지점이 설정과 멀어져 원인 추적이 어렵다. 그래서 설정 시점에 막는다
        // (RDS·DOCKER 를 프로비저닝 요청 시점에 막는 것과 같은 취지). ②에서 이 가드를 푼다.
        if (runtimeType == PreviewRuntimeType.JAVA_FULLSTACK) {
            throw new IllegalArgumentException("JAVA_FULLSTACK 런타임은 아직 지원되지 않습니다. (곧 지원)");
        }
        PreviewRuntimeConfigEntity entity = repository.findByProjectId(projectId)
                .map(existing -> {
                    existing.update(runtimeType, startCommand, apiPathPrefix, healthPath, dbEngine);
                    return existing;
                })
                .orElseGet(() -> PreviewRuntimeConfigEntity.of(
                        projectId, runtimeType, startCommand, apiPathPrefix, healthPath, dbEngine));
        return PreviewRuntimeConfigResult.stored(repository.save(entity));
    }

    /**
     * 프로비저닝(프리뷰 부팅) 시점에 실제로 쓸 런타임 설정을 해석한다. 저장된 설정이 있으면 그것을,
     * 없으면 컨테이너의 클론 내용으로 자동 감지한 값을 돌려준다. 소유권은 부팅 흐름이 이미 검증했다.
     */
    @Transactional(readOnly = true)
    public PreviewRuntimeConfigResult resolveForProvision(Long projectId, String containerId) {
        return repository.findByProjectId(projectId)
                .map(PreviewRuntimeConfigResult::stored)
                .orElseGet(() -> {
                    PreviewRuntimeType detected = detector.detect(containerId);
                    return PreviewRuntimeConfigResult.detected(projectId, detected);
                });
    }

    private void ensureOwned(Long ownerUserId, Long projectId) {
        projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, ownerUserId)
                .orElseThrow(() -> new NotFoundException(
                        "프로젝트를 찾을 수 없습니다. projectId=" + projectId));
    }
}
