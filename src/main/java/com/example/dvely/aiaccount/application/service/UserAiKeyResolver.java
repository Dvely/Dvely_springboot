package com.example.dvely.aiaccount.application.service;

import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.aiaccount.domain.repository.AiProviderCredentialRepository;
import com.example.dvely.common.exception.AiCredentialNotRegisteredException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The one place that turns "this user, this provider" into a usable API key.
 *
 * <p>There is deliberately no deployment-wide fallback here, and no other way to obtain a key for
 * an LLM call: a run is billed to whoever asked for it or it does not happen. That rule used to
 * hold only for coding agents while vendor providers quietly spent the operator's credit — the
 * public launch turned that into a bill anyone with a login could raise (#364).</p>
 *
 * <p>Lookup is by {@link AiProvider#credentialVendor()} rather than by the requested provider, so
 * {@code CLAUDE_CODE} and {@code ANTHROPIC} resolve to the same stored credential. A user pastes
 * each vendor key once and both the CLI mode and the chat-completions mode run on it.</p>
 */
@Service
@RequiredArgsConstructor
public class UserAiKeyResolver {

    private final AiProviderCredentialRepository credentialRepository;

    /**
     * @param userId   the caller; never null on an authenticated path, but checked because an
     *                 internal caller with no user would otherwise look up {@code null} and get a
     *                 confusing "not registered" instead of a clear programming error
     * @param provider what the request asked to run on
     * @return that user's plaintext key for the vendor {@code provider} authenticates with
     * @throws AiCredentialNotRegisteredException when the user has not registered that vendor's key
     */
    public String require(Long userId, AiProvider provider) {
        if (userId == null) {
            throw new IllegalArgumentException(
                    "userId 없이 AI 키를 조회할 수 없습니다. 서버 키로 도는 경로는 없습니다: " + provider);
        }
        AiProvider vendor = provider.credentialVendor();
        return credentialRepository.findByUserIdAndProvider(userId, vendor)
                .orElseThrow(() -> new AiCredentialNotRegisteredException(
                        vendor + " API 키가 등록되지 않았습니다. 설정에서 본인 키를 먼저 등록해주세요."))
                .getApiKey();
    }
}
