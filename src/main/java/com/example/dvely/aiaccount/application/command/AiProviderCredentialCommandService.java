package com.example.dvely.aiaccount.application.command;

import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.aiaccount.application.query.AiProviderCredentialQueryService;
import com.example.dvely.aiaccount.application.result.AiProviderCredentialResult;
import com.example.dvely.aiaccount.domain.model.AiProviderCredential;
import com.example.dvely.aiaccount.domain.repository.AiProviderCredentialRepository;
import com.example.dvely.common.exception.NotFoundException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Write side of the BYOK credential domain.
 *
 * <p>Registration is a replace, not a create: the storage invariant is one key per (user, vendor),
 * so "register" and "rotate" are the same operation and the endpoint is a PUT. Modelling it as a
 * create that conflicts would make key rotation — the thing a user does after a leak, in a hurry —
 * a two-call dance.</p>
 */
@Service
@RequiredArgsConstructor
public class AiProviderCredentialCommandService {

    private final AiProviderCredentialRepository credentialRepository;

    @Transactional
    public AiProviderCredentialResult register(Long userId,
                                               AiProvider provider,
                                               String apiKey,
                                               String label) {
        Optional<AiProviderCredential> existing =
                credentialRepository.findByUserIdAndProvider(userId, provider);

        if (existing.isPresent()) {
            return AiProviderCredentialQueryService.toResult(rotate(existing.get(), apiKey, label));
        }

        try {
            AiProviderCredential created =
                    new AiProviderCredential(userId, provider, apiKey, label);
            return AiProviderCredentialQueryService.toResult(credentialRepository.save(created));
        } catch (DataIntegrityViolationException e) {
            // Two concurrent registrations both found nothing and both tried to insert; the unique
            // (user, provider) key let exactly one through. Re-reading and updating keeps PUT
            // genuinely idempotent instead of surfacing a race as a 500 to whichever caller lost.
            AiProviderCredential winner = credentialRepository
                    .findByUserIdAndProvider(userId, provider)
                    .orElseThrow(() -> e);
            return AiProviderCredentialQueryService.toResult(rotate(winner, apiKey, label));
        }
    }

    @Transactional
    public void delete(Long userId, AiProvider provider) {
        if (!credentialRepository.deleteByUserIdAndProvider(userId, provider)) {
            throw new NotFoundException(provider + " API 키가 등록되어 있지 않습니다.");
        }
    }

    private AiProviderCredential rotate(AiProviderCredential credential, String apiKey, String label) {
        credential.changeApiKey(apiKey);
        credential.changeLabel(label);
        return credentialRepository.save(credential);
    }
}
