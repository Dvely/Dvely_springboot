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

    /**
     * 이 서버가 도메인 연결을 실제로 지원하는 배포 대상들 — 어댑터가 등록된 것뿐이다({@link #resolve}가 안
     * 던지는 것과 정확히 일치). enum 에는 있어도 어댑터가 없는 값(예: 어댑터 미구현 대상)은 제외되므로,
     * enum 이나 @Schema 목록보다 정확하다. FE 가 이걸 읽어 지원 안 하는 옵션을 노출하지 않는다.
     */
    public List<DomainHostingTarget> supportedTargets() {
        return adapters.stream()
                .map(DomainHostingAdapter::target)
                .distinct()
                .sorted()   // enum 선언 순서(ordinal)
                .toList();
    }
}
