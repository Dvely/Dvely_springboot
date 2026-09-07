package com.example.dvely.aiaccount.application.facade;

import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.aiaccount.application.command.AiProviderCredentialCommandService;
import com.example.dvely.aiaccount.application.query.AiProviderCredentialQueryService;
import com.example.dvely.aiaccount.application.result.AiProviderCredentialResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiProviderCredentialFacade {

    private final AiProviderCredentialQueryService queryService;
    private final AiProviderCredentialCommandService commandService;

    public List<AiProviderCredentialResult> list(Long userId) {
        return queryService.list(userId);
    }

    public AiProviderCredentialResult register(Long userId,
                                               AiProvider provider,
                                               String apiKey,
                                               String label) {
        return commandService.register(userId, provider, apiKey, label);
    }

    public void delete(Long userId, AiProvider provider) {
        commandService.delete(userId, provider);
    }
}
