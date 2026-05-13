package com.example.dvely.domainbinding.application.result;

import com.example.dvely.domainbinding.domain.value.DomainType;
import java.math.BigDecimal;

public record DomainSearchCandidateResult(
        DomainType type,
        String hostname,
        boolean available,
        BigDecimal price,
        String currency
) {
}
