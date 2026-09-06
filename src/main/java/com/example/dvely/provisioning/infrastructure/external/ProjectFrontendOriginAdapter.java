package com.example.dvely.provisioning.infrastructure.external;

import com.example.dvely.domainbinding.domain.model.DomainBinding;
import com.example.dvely.domainbinding.domain.repository.DomainBindingRepository;
import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import com.example.dvely.domainbinding.domain.value.DomainStatus;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.provisioning.application.port.out.FrontendOriginPort;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * FrontendOriginPort 구현 — 프로젝트의 프론트 배포 URL(Project.currentUrl)과 프론트(비-AWS) CONNECTED
 * 도메인에서 CORS 허용 오리진(scheme://host[:port])을 뽑아 백엔드에 넘긴다. 백엔드는 이 오리진에서 오는
 * 브라우저 요청만 CORS 로 받는다(*.qeploy.com 관리형 프론트, 커스텀 프론트 도메인, GitHub Pages 등).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectFrontendOriginAdapter implements FrontendOriginPort {

    private final ProjectRepository projectRepository;
    private final DomainBindingRepository domainBindingRepository;

    @Override
    public List<String> resolveAllowedOrigins(Long projectId) {
        Set<String> origins = new LinkedHashSet<>();
        projectRepository.findById(projectId).ifPresent(project -> {
            String origin = toOrigin(project.getCurrentUrl());
            if (origin != null) {
                origins.add(origin);
            }
        });
        // 프론트(비-AWS) CONNECTED 도메인도 오리진으로 — 커스텀 프론트 도메인 등.
        for (DomainBinding d : domainBindingRepository.findByProjectIdOrderByCreatedAtDesc(projectId)) {
            if (d.getHostingTarget() != DomainHostingTarget.AWS && d.getStatus() == DomainStatus.CONNECTED) {
                origins.add("https://" + d.getHostname());
            }
        }
        return List.copyOf(origins);
    }

    /** URL 에서 CORS 오리진(scheme://host[:port])만 뽑는다. 경로·쿼리는 버린다. 파싱 실패 시 null. */
    private String toOrigin(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI u = URI.create(url.trim());
            if (u.getScheme() == null || u.getHost() == null) {
                return null;
            }
            String origin = u.getScheme() + "://" + u.getHost();
            if (u.getPort() != -1) {
                origin += ":" + u.getPort();
            }
            return origin;
        } catch (RuntimeException e) {
            log.debug("CORS 오리진 파싱 실패: {}", url);
            return null;
        }
    }
}
