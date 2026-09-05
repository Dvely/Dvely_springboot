package com.example.dvely.domainbinding.infrastructure.external;

import com.example.dvely.domainbinding.infrastructure.persistence.repository.SpringDataDomainBindingRepository;
import com.example.dvely.provisioning.application.port.out.ActiveCdnDistributionPort;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link ActiveCdnDistributionPort} 구현 — 바인딩 추적은 도메인바인딩 소관이라 여기서 domain_bindings 를 읽어
 * 현재 참조 중인 CloudFront 배포 id 를 돌려준다. 고아 스윕(프로비저닝)이 이 집합을 안전 경계로 쓴다.
 */
@Component
@RequiredArgsConstructor
public class ActiveCdnDistributionAdapter implements ActiveCdnDistributionPort {

    private final SpringDataDomainBindingRepository domainBindingRepository;

    @Override
    public Set<String> trackedDistributionIds() {
        return new HashSet<>(domainBindingRepository.findAllCloudfrontDistributionIds());
    }
}
