package com.example.dvely.apitoken.application.command;

import com.example.dvely.apitoken.application.query.ApiTokenQueryService;
import com.example.dvely.apitoken.application.result.IssuedApiTokenResult;
import com.example.dvely.apitoken.domain.model.ApiToken;
import com.example.dvely.apitoken.domain.model.IssuedApiToken;
import com.example.dvely.apitoken.domain.repository.ApiTokenRepository;
import com.example.dvely.apitoken.domain.service.ApiTokenGenerator;
import com.example.dvely.apitoken.domain.value.ApiTokenScope;
import com.example.dvely.common.exception.NotFoundException;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ApiTokenCommandService {

    /** Default lifetime when the caller does not choose one. */
    private static final int DEFAULT_EXPIRY_DAYS = 90;

    /**
     * A year is long for a credential that authenticates an automated client, but a hard ceiling
     * beats an unbounded one: a token nobody remembers issuing eventually stops working on its own.
     */
    private static final int MAX_EXPIRY_DAYS = 365;

    private final ApiTokenRepository apiTokenRepository;

    @Transactional
    public IssuedApiTokenResult issue(Long userId, ApiTokenScope scope, String label, Integer expiresInDays) {
        int days = expiresInDays == null ? DEFAULT_EXPIRY_DAYS : expiresInDays;
        if (days < 1 || days > MAX_EXPIRY_DAYS) {
            throw new IllegalArgumentException(
                    "만료일은 1일 이상 " + MAX_EXPIRY_DAYS + "일 이하여야 합니다.");
        }

        IssuedApiToken issued = ApiTokenGenerator.issue(
                userId, scope, label, LocalDateTime.now().plusDays(days));
        ApiToken saved = apiTokenRepository.save(issued.stored());

        // The only return of the plaintext, anywhere.
        return new IssuedApiTokenResult(ApiTokenQueryService.toResult(saved), issued.plaintext());
    }

    @Transactional
    public void revoke(Long userId, Long apiTokenId) {
        // Scoped delete rather than find-then-check: a token belonging to someone else is simply
        // not found, so there is no branch where the ownership check could be forgotten.
        if (!apiTokenRepository.deleteByIdAndUserId(apiTokenId, userId)) {
            throw new NotFoundException("토큰을 찾을 수 없습니다.");
        }
    }
}
