package com.example.dvely.domainbinding.domain.repository;

import com.example.dvely.domainbinding.domain.model.DomainBinding;
import com.example.dvely.domainbinding.domain.value.DomainStatus;
import java.util.List;
import java.util.Optional;

public interface DomainBindingRepository {

    DomainBinding save(DomainBinding domainBinding);

    Optional<DomainBinding> findById(Long id);

    List<DomainBinding> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    /**
     * 해당 상태의 도메인을 오래된 순으로 최대 {@code limit} 건 읽는다. 검증 워커가 매 주기마다
     * 외부 API(Cloudflare · 호스팅)를 도메인 수만큼 때리므로 한 번에 집는 양을 묶어야 한다.
     */
    List<DomainBinding> findByStatus(DomainStatus status, int limit);

    /**
     * S3 CDN 프로비저닝 워커의 다중 인스턴스 리스 claim. 리스가 비었거나 만료됐거나 내가 쥔 것이면 true —
     * 그때만 이 도메인의 CloudFront 배포·ACM 인증서 생성을 진행한다(두 인스턴스가 중복 자원을 만들지 않게).
     */
    boolean claimForCdnProvision(Long id, String owner);

    boolean existsByHostnameIgnoreCase(String hostname);

    /** 이 호스트네임이 특정 호스팅 대상으로 등록돼 있는지. 백엔드(AWS) 도메인의 TLS 발급 허가 판단에 쓴다. */
    boolean existsByHostnameIgnoreCaseAndHostingTarget(String hostname,
            com.example.dvely.domainbinding.domain.value.DomainHostingTarget hostingTarget);

    void deleteById(Long id);
}
