package com.example.dvely.domainbinding.application.result;

import java.util.List;

public record DomainSearchResult(
        String keyword,
        List<DomainSearchCandidateResult> results
) {
}
