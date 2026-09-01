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
     * 저장된 런타임 타입만 읽는다(자동 감지 안 함). 컨테이너를 만들기 전에 메모리를 정하려는 용도다 —
     * JAVA_FULLSTACK 은 JVM+gradle 빌드가 무거워 큰 컨테이너가 필요한데, 메모리는 컨테이너 생성
     * 시점에 정해지고 자동 감지는 클론 후라 늦다. 그래서 "사용자가 설정에서 미리 고른" 값만 여기서
     * 본다. 미설정이면 비어 있고, 호출자는 기본 메모리로 만든다.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<PreviewRuntimeType> storedRuntimeType(Long projectId) {
        return repository.findByProjectId(projectId).map(PreviewRuntimeConfigEntity::runtimeTypeEnum);
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
