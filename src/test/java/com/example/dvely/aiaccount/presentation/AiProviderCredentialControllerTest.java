package com.example.dvely.aiaccount.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.aiaccount.application.facade.AiProviderCredentialFacade;
import com.example.dvely.aiaccount.application.result.AiProviderCredentialResult;
import com.example.dvely.aiaccount.presentation.dto.request.RegisterAiProviderCredentialRequest;
import com.example.dvely.aiaccount.presentation.dto.response.AiProviderCredentialResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiProviderCredentialControllerTest {

    private static final Long USER_ID = 1L;

    @Mock
    private AiProviderCredentialFacade facade;

    @InjectMocks
    private AiProviderCredentialController controller;

    private static AiProviderCredentialResult result(String provider) {
        return new AiProviderCredentialResult(
                1L, provider, "sk-ant****", "개인 계정", LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    void listUsesTheAuthenticatedUserIdRatherThanAnyRequestParameter() {
        // There is no path or query parameter for the owner anywhere in this controller, so no
        // caller can ask for someone else's credentials.
        when(facade.list(USER_ID)).thenReturn(List.of(result("ANTHROPIC")));

        List<AiProviderCredentialResponse> responses = controller.list(USER_ID);

        assertThat(responses).hasSize(1);
        assertThat(responses.getFirst().provider()).isEqualTo("ANTHROPIC");
        verify(facade).list(USER_ID);
    }

    @Test
    void listReturnsOnlyTheMaskedKey() {
        when(facade.list(USER_ID)).thenReturn(List.of(result("ANTHROPIC")));

        AiProviderCredentialResponse response = controller.list(USER_ID).getFirst();

        assertThat(response.maskedApiKey()).isEqualTo("sk-ant****");
        // The response type has no plaintext field at all, so this holds structurally.
        assertThat(response.toString()).doesNotContain("api03");
    }

    @Test
    void registerDelegatesTheProviderAndKeyToTheFacade() {
        when(facade.register(USER_ID, AiProvider.ANTHROPIC, "sk-ant-api03-key", "개인 계정"))
                .thenReturn(result("ANTHROPIC"));

        AiProviderCredentialResponse response = controller.register(
                USER_ID, AiProvider.ANTHROPIC,
                new RegisterAiProviderCredentialRequest("sk-ant-api03-key", "개인 계정"));

        assertThat(response.maskedApiKey()).isEqualTo("sk-ant****");
        verify(facade).register(USER_ID, AiProvider.ANTHROPIC, "sk-ant-api03-key", "개인 계정");
    }

    @Test
    void registerAcceptsAnAbsentLabel() {
        when(facade.register(USER_ID, AiProvider.OPENAI, "sk-proj-key", null))
                .thenReturn(result("OPENAI"));

        controller.register(USER_ID, AiProvider.OPENAI,
                new RegisterAiProviderCredentialRequest("sk-proj-key", null));

        verify(facade).register(USER_ID, AiProvider.OPENAI, "sk-proj-key", null);
    }

    @Test
    void deleteDelegatesUsingTheAuthenticatedUserId() {
        controller.delete(USER_ID, AiProvider.GLM);

        verify(facade).delete(USER_ID, AiProvider.GLM);
    }
}
