package com.example.dvely.aiaccount.application.query;

import com.example.dvely.aiaccount.application.result.AiProviderCredentialResult;
import com.example.dvely.aiaccount.domain.model.AiProviderCredential;
import com.example.dvely.aiaccount.domain.repository.AiProviderCredentialRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the BYOK credential domain. Every query is scoped to the calling user — there is no
 * "list all" here or in the repository, so no endpoint can be written that returns another user's
 * credentials, masked or not.
 */
@Service
@RequiredArgsConstructor
public class AiProviderCredentialQueryService {

    private final AiProviderCredentialRepository credentialRepository;

    @Transactional(readOnly = true)
    public List<AiProviderCredentialResult> list(Long userId) {
        return credentialRepository.findByUserIdOrderByProviderAsc(userId).stream()
                .map(AiProviderCredentialQueryService::toResult)
                .toList();
    }

    /**
     * The single place a domain object becomes something a caller may see. Masking happens here so
     * that the plaintext never enters the result type at all.
     */
    public static AiProviderCredentialResult toResult(AiProviderCredential credential) {
        return new AiProviderCredentialResult(
                credential.getId(),
                credential.getProvider().name(),
                credential.maskedApiKey(),
                credential.getLabel(),
                credential.getCreatedAt(),
                credential.getUpdatedAt()
        );
    }
}
