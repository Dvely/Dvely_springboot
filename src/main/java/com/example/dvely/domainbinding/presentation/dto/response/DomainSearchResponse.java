package com.example.dvely.domainbinding.presentation.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record DomainSearchResponse(
        String keyword,
        List<Result> results
) {

    public record Result(
            String type,
            String hostname,
            boolean available,
            BigDecimal price,
            String currency
    ) {
    }
}
