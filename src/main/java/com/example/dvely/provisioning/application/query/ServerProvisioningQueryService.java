package com.example.dvely.provisioning.application.query;

import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.provisioning.domain.model.ProvisionedServer;
import com.example.dvely.provisioning.application.port.out.BackendDomainPort;
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
    private final BackendDomainPort backendDomainPort;

    /** 서버 목록 + 프로젝트에 연결된 백엔드 도메인 호스트네임(있으면). 한 프로젝트의 서버들은 같은 도메인을 공유한다. */
    public record ServerListResult(List<ProvisionedServer> servers, String domainHostname) {}

    @Transactional(readOnly = true)
    public ServerListResult list(Long ownerUserId, Long projectId) {
        projectRepository.findByIdAndOwnerUserId(projectId, ownerUserId)
                .orElseThrow(() -> new NotFoundException(
                        "프로젝트를 찾을 수 없거나 접근 권한이 없습니다. projectId=" + projectId));
        List<ProvisionedServer> servers = serverRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        String domainHostname = backendDomainPort.resolveConnectedBackendDomain(projectId).orElse(null);
        return new ServerListResult(servers, domainHostname);
    }
}
