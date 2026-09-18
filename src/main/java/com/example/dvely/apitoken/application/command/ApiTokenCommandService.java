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

    /**
     * Lifetimes differ by scope, because what a leaked token can do differs by scope.
     *
     * <p>A {@code READ} token exposes what its owner can already see. A {@code WRITE} token can
     * deploy, rewrite environment variables, and bind domains — and it does so from a headless
     * client, where nobody is watching a screen to notice. The window in which a stolen one is
     * still useful should be short enough that rotation is a real defence rather than a policy
     * nobody gets around to.</p>
     *
     * <p>A hard ceiling matters more than the exact number: a token nobody remembers issuing
     * eventually stops working on its own.</p>
     */
    private static final int READ_DEFAULT_DAYS = 90;
    private static final int READ_MAX_DAYS = 365;
    private static final int WRITE_DEFAULT_DAYS = 30;
    private static final int WRITE_MAX_DAYS = 90;

    private final ApiTokenRepository apiTokenRepository;

    @Transactional
    public IssuedApiTokenResult issue(Long userId, ApiTokenScope scope, String label, Integer expiresInDays) {
        boolean write = scope == ApiTokenScope.WRITE;
        int defaultDays = write ? WRITE_DEFAULT_DAYS : READ_DEFAULT_DAYS;
        int maxDays = write ? WRITE_MAX_DAYS : READ_MAX_DAYS;

        int days = expiresInDays == null ? defaultDays : expiresInDays;
        if (days < 1 || days > maxDays) {
            // The scope is named in the message: asking for 365 days is reasonable until you know
            // that this token can deploy, and a bare "1~90" does not say why the answer changed.
            throw new IllegalArgumentException(
                    "만료일은 1일 이상 " + maxDays + "일 이하여야 합니다(" + scope + " 스코프).");
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
