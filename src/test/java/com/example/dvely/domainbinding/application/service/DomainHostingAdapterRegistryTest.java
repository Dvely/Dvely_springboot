package com.example.dvely.domainbinding.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dvely.domainbinding.application.port.out.DomainHostingAdapter;
import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import java.util.List;
import org.junit.jupiter.api.Test;

class DomainHostingAdapterRegistryTest {

    private DomainHostingAdapter adapterFor(DomainHostingTarget target) {
        DomainHostingAdapter adapter = mock(DomainHostingAdapter.class);
        when(adapter.target()).thenReturn(target);
        return adapter;
    }

    @Test
    void supportedTargets_returnsRegisteredAdaptersTargets_distinctAndEnumOrdered() {
        // 어댑터 등록 순서가 뒤섞여도 enum 선언 순서로 정렬해 돌려준다. GCP 는 어댑터가 없으니 제외된다.
        DomainHostingAdapterRegistry registry = new DomainHostingAdapterRegistry(List.of(
                adapterFor(DomainHostingTarget.AWS_S3_FRONTEND),
                adapterFor(DomainHostingTarget.GITHUB_PAGES),
                adapterFor(DomainHostingTarget.AWS)));

        assertThat(registry.supportedTargets()).containsExactly(
                DomainHostingTarget.GITHUB_PAGES,   // ordinal 순서
                DomainHostingTarget.AWS,
                DomainHostingTarget.AWS_S3_FRONTEND);
        // enum 에 있어도 어댑터 없는 값(GCP)은 담기지 않는다 — resolve 가 던지는 것과 일치.
        assertThat(registry.supportedTargets()).doesNotContain(DomainHostingTarget.GCP);
    }

    @Test
    void resolve_returnsMatchingAdapter_orThrowsForUnsupported() {
        DomainHostingAdapter github = adapterFor(DomainHostingTarget.GITHUB_PAGES);
        DomainHostingAdapterRegistry registry = new DomainHostingAdapterRegistry(List.of(github));

        assertThat(registry.resolve(DomainHostingTarget.GITHUB_PAGES)).isSameAs(github);
        assertThatThrownBy(() -> registry.resolve(DomainHostingTarget.GCP))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
