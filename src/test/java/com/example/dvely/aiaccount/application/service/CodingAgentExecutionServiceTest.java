package com.example.dvely.aiaccount.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.port.out.CodingAgentCommand;
import com.example.dvely.agent.application.port.out.CodingAgentPort;
import com.example.dvely.agent.application.port.out.CodingAgentResult;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.codingagent.CodingAgentProperties;
import com.example.dvely.agent.infrastructure.codingagent.CodingAgentRouter;
import com.example.dvely.aiaccount.domain.model.AiProviderCredential;
import com.example.dvely.aiaccount.domain.repository.AiProviderCredentialRepository;
import com.example.dvely.common.exception.AiCredentialNotRegisteredException;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CodingAgentExecutionServiceTest {

    private static final Long USER_ID = 7L;
    private static final String ANTHROPIC_KEY = "sk-ant-api03-userkey";
    private static final String OPENAI_KEY = "sk-proj-userkey";

    private AiProviderCredentialRepository repository;
    private CodingAgentRouter router;
    private CodingAgentPort port;
    private CodingAgentExecutionService service;

    @BeforeEach
    void setUp() {
        repository = mock(AiProviderCredentialRepository.class);
        router = mock(CodingAgentRouter.class);
        port = mock(CodingAgentPort.class);
        service = new CodingAgentExecutionService(repository, router, new CodingAgentProperties());

        when(router.route(any())).thenReturn(port);
        when(port.run(any())).thenReturn(CodingAgentResult.succeeded("done", ""));
    }

    private void givenCredential(AiProvider vendor, String key) {
        when(repository.findByUserIdAndProvider(USER_ID, vendor))
                .thenReturn(Optional.of(new AiProviderCredential(USER_ID, vendor, key, null)));
    }

    @Test
    void looksUpTheAnthropicKeyForClaudeCode() {
        givenCredential(AiProvider.ANTHROPIC, ANTHROPIC_KEY);

        service.run(USER_ID, AiProvider.CLAUDE_CODE, "고쳐줘", "/host/checkout");

        ArgumentCaptor<CodingAgentCommand> command = ArgumentCaptor.captor();
        verify(port).run(command.capture());
        assertThat(command.getValue().apiKey()).isEqualTo(ANTHROPIC_KEY);
        verify(repository).findByUserIdAndProvider(USER_ID, AiProvider.ANTHROPIC);
    }

    @Test
    void looksUpTheOpenAiKeyForCodex() {
        givenCredential(AiProvider.OPENAI, OPENAI_KEY);

        service.run(USER_ID, AiProvider.CODEX, "고쳐줘", "/host/checkout");

        ArgumentCaptor<CodingAgentCommand> command = ArgumentCaptor.captor();
        verify(port).run(command.capture());
        assertThat(command.getValue().apiKey()).isEqualTo(OPENAI_KEY);
        verify(repository).findByUserIdAndProvider(USER_ID, AiProvider.OPENAI);
    }

    @Test
    void refusesWithAnActionableErrorWhenTheUserHasNoKeyForThatVendor() {
        when(repository.findByUserIdAndProvider(USER_ID, AiProvider.ANTHROPIC))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.run(USER_ID, AiProvider.CLAUDE_CODE, "고쳐줘", "/host/checkout"))
                .isInstanceOf(AiCredentialNotRegisteredException.class)
                .hasMessageContaining("ANTHROPIC");
    }

    @Test
    void neverFallsBackToADeploymentKeyWhenTheUserHasNotRegisteredOne() {
        when(repository.findByUserIdAndProvider(USER_ID, AiProvider.OPENAI))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.run(USER_ID, AiProvider.CODEX, "고쳐줘", "/host/checkout"))
                .isInstanceOf(AiCredentialNotRegisteredException.class);

        // Spending the operator's credit on a user's behalf is exactly what the providers' terms
        // forbid, so "no key" must stop the run rather than quietly substitute one.
        verify(port, never()).run(any());
    }

    @Test
    void rejectsANonCodingAgentProviderBeforeTouchingTheCredentialStore() {
        assertThatThrownBy(() -> service.run(USER_ID, AiProvider.GLM, "고쳐줘", "/host/checkout"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).findByUserIdAndProvider(any(), any());
    }

    @Test
    void passesThePromptAndWorkspaceThrough() {
        givenCredential(AiProvider.ANTHROPIC, ANTHROPIC_KEY);

        service.run(USER_ID, AiProvider.CLAUDE_CODE, "빌드 로그 분석", "/host/checkout");

        ArgumentCaptor<CodingAgentCommand> command = ArgumentCaptor.captor();
        verify(port).run(command.capture());
        assertThat(command.getValue().prompt()).isEqualTo("빌드 로그 분석");
        assertThat(command.getValue().workspaceDir()).isEqualTo("/host/checkout");
    }

    @Test
    void usesTheConfiguredTimeoutByDefaultAndAnExplicitOneWhenGiven() {
        givenCredential(AiProvider.ANTHROPIC, ANTHROPIC_KEY);

        service.run(USER_ID, AiProvider.CLAUDE_CODE, "a", "/w");
        service.run(USER_ID, AiProvider.CLAUDE_CODE, "a", "/w", Duration.ofMinutes(1));

        ArgumentCaptor<CodingAgentCommand> command = ArgumentCaptor.captor();
        verify(port, org.mockito.Mockito.times(2)).run(command.capture());
        assertThat(command.getAllValues().get(0).timeout())
                .isEqualTo(new CodingAgentProperties().getTimeout());
        assertThat(command.getAllValues().get(1).timeout()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void returnsWhateverTheAdapterReported() {
        givenCredential(AiProvider.ANTHROPIC, ANTHROPIC_KEY);
        when(port.run(any())).thenReturn(CodingAgentResult.timedOut("partial", ""));

        CodingAgentResult result = service.run(USER_ID, AiProvider.CLAUDE_CODE, "a", "/w");

        assertThat(result.timedOut()).isTrue();
    }
}
