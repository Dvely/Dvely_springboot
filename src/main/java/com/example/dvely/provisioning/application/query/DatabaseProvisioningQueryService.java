package com.example.dvely.provisioning.application.query;

import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.provisioning.application.result.ProvisionedDatabaseResult;
import com.example.dvely.provisioning.domain.repository.ProvisionedDatabaseRepository;
import com.example.dvely.provisioning.domain.value.ProvisionStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 프로비저닝 자원 조회. 순수 DB 조회다 — 외부 API(AWS 등)를 때리지 않으므로 FE 가 상시 폴링해도
 * 안전하다(개요와 다르다). RDS 상태는 워커가 백그라운드로 폴링해 DB 에 갱신해둔 값을 읽는다.
 */
@Service
@RequiredArgsConstructor
public class DatabaseProvisioningQueryService {

    private final ProvisionedDatabaseRepository databaseRepository;
    private final ProjectRepository projectRepository;

    @Transactional(readOnly = true)
    public List<ProvisionedDatabaseResult> list(Long ownerUserId, Long projectId) {
        ensureOwned(ownerUserId, projectId);
        // EXPIRED 는 목록에서 제외한다. 프리뷰 30분 TTL 이라 하루면 수십 개가 쌓이는데, 그걸 다
        // 내려주면 "지금 쓸 수 있는 DB"가 지나간 것들에 묻힌다. 행은 감사·이력으로 남기되(워커가
        // markExpired 로 상태만 넘김) 기본 목록에는 활성 자원만 준다.
        return databaseRepository.findByProjectIdOrderByCreatedAtDesc(projectId)
                .stream()
                .filter(d -> d.getStatus() != ProvisionStatus.EXPIRED)
                .map(ProvisionedDatabaseResult::from).toList();
    }

    private void ensureOwned(Long ownerUserId, Long projectId) {
        projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, ownerUserId)
                .orElseThrow(() -> new NotFoundException(
                        "프로젝트를 찾을 수 없습니다. projectId=" + projectId));
    }
}
