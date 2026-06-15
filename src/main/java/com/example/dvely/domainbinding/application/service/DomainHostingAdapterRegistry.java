package com.example.dvely.domainbinding.application.service;

import com.example.dvely.domainbinding.application.port.out.DomainHostingAdapter;
import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DomainHostingAdapterRegistry {

    private final List<DomainHostingAdapter> adapters;

    public DomainHostingAdapter resolve(DomainHostingTarget target) {
        return adapters.stream()
                .filter(adapter -> adapter.target() == target)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        target + " 배포 대상의 도메인 연결은 아직 지원되지 않습니다."
                ));
    }
}
