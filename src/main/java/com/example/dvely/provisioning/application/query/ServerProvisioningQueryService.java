package com.example.dvely.provisioning.application.query;

import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.provisioning.domain.model.ProvisionedServer;
import com.example.dvely.provisioning.domain.repository.ProvisionedServerRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 프로젝트의 EC2 서버 목록 조회. 순수 DB 조회라 외부 API 를 때리지 않아 상시 폴링해도 안전하다. */
@Service
@RequiredArgsConstructor
public class ServerProvisioningQueryService {

    private final ProvisionedServerRepository serverRepository;
    private final ProjectRepository projectRepository;

    @Transactional(readOnly = true)
    public List<ProvisionedServer> list(Long ownerUserId, Long projectId) {
        projectRepository.findByIdAndOwnerUserId(projectId, ownerUserId)
                .orElseThrow(() -> new NotFoundException(
                        "프로젝트를 찾을 수 없거나 접근 권한이 없습니다. projectId=" + projectId));
        return serverRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }
}
