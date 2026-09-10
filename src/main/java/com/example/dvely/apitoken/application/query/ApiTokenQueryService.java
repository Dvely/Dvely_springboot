package com.example.dvely.apitoken.application.query;

import com.example.dvely.apitoken.application.result.ApiTokenResult;
import com.example.dvely.apitoken.domain.model.ApiToken;
import com.example.dvely.apitoken.domain.repository.ApiTokenRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side. Every query is user-scoped; there is no "list all" anywhere in this domain. */
@Service
@RequiredArgsConstructor
public class ApiTokenQueryService {

    private final ApiTokenRepository apiTokenRepository;

    @Transactional(readOnly = true)
    public List<ApiTokenResult> list(Long userId) {
        return apiTokenRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(ApiTokenQueryService::toResult).toList();
    }

    /** The single place a stored token becomes something a caller may see. */
    public static ApiTokenResult toResult(ApiToken token) {
        return new ApiTokenResult(
                token.getId(),
                token.getTokenPrefix(),
                token.getScope().name(),
                token.getLabel(),
                token.getExpiresAt(),
                token.getLastUsedAt(),
                token.getCreatedAt()
        );
    }
}
