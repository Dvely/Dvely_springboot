package com.example.dvely.aiaccount.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.aiaccount.application.result.AiProviderCredentialResult;
import com.example.dvely.aiaccount.domain.model.AiProviderCredential;
import com.example.dvely.aiaccount.domain.repository.AiProviderCredentialRepository;
import com.example.dvely.common.exception.NotFoundException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

class AiProviderCredentialCommandServiceTest {

    private static final Long USER_ID = 7L;
    private static final String KEY = "sk-ant-api03-originalkey";
    private static final String NEW_KEY = "sk-ant-api03-rotatedkey";

    private AiProviderCredentialRepository repository;
    private AiProviderCredentialCommandService service;

    @BeforeEach
    void setUp() {
        repository = mock(AiProviderCredentialRepository.class);
        service = new AiProviderCredentialCommandService(repository);
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void registersANewCredentialWhenNoneExists() {
        when(repository.findByUserIdAndProvider(USER_ID, AiProvider.ANTHROPIC))
                .thenReturn(Optional.empty());

        AiProviderCredentialResult result =
                service.register(USER_ID, AiProvider.ANTHROPIC, KEY, "개인 계정");

        assertThat(result.provider()).isEqualTo("ANTHROPIC");
        assertThat(result.label()).isEqualTo("개인 계정");
    }

    @Test
    void registeringAgainRotatesTheExistingKeyRatherThanConflicting() {
        // One key per (user, vendor) means register and rotate are the same operation; making a
        // repeat call a 409 would turn a post-leak rotation into a two-call dance.
        AiProviderCredential existing =
                new AiProviderCredential(USER_ID, AiProvider.ANTHROPIC, KEY, "old");
        when(repository.findByUserIdAndProvider(USER_ID, AiProvider.ANTHROPIC))
                .thenReturn(Optional.of(existing));

        service.register(USER_ID, AiProvider.ANTHROPIC, NEW_KEY, "new");

        ArgumentCaptor<AiProviderCredential> saved = ArgumentCaptor.captor();
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getApiKey()).isEqualTo(NEW_KEY);
        assertThat(saved.getValue().getLabel()).isEqualTo("new");
    }

    @Test
    void neverReturnsThePlaintextKey() {
        when(repository.findByUserIdAndProvider(USER_ID, AiProvider.ANTHROPIC))
                .thenReturn(Optional.empty());

        AiProviderCredentialResult result =
                service.register(USER_ID, AiProvider.ANTHROPIC, KEY, null);

        assertThat(result.maskedApiKey()).isEqualTo("sk-ant****");
        assertThat(result.toString()).doesNotContain(KEY);
    }

    @Test
    void resolvesAConcurrentFirstRegistrationRaceIntoARotation() {
        // Both callers saw "nothing registered" and both inserted; the unique key let one through.
        // The loser must still end up with a registered key, not a 500.
        when(repository.findByUserIdAndProvider(USER_ID, AiProvider.ANTHROPIC))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new AiProviderCredential(USER_ID, AiProvider.ANTHROPIC, KEY, null)));
        when(repository.save(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate"))
                .thenAnswer(i -> i.getArgument(0));

        AiProviderCredentialResult result =
                service.register(USER_ID, AiProvider.ANTHROPIC, NEW_KEY, null);

        assertThat(result.provider()).isEqualTo("ANTHROPIC");
    }

    @Test
    void rejectsAnExecutionModeAsAProvider() {
        when(repository.findByUserIdAndProvider(USER_ID, AiProvider.CLAUDE_CODE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(USER_ID, AiProvider.CLAUDE_CODE, KEY, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ANTHROPIC");
    }

    @Test
    void deletesARegisteredCredential() {
        when(repository.deleteByUserIdAndProvider(USER_ID, AiProvider.OPENAI)).thenReturn(true);

        service.delete(USER_ID, AiProvider.OPENAI);

        verify(repository).deleteByUserIdAndProvider(USER_ID, AiProvider.OPENAI);
    }

    @Test
    void deletingSomethingThatWasNeverRegisteredIsANotFound() {
        when(repository.deleteByUserIdAndProvider(USER_ID, AiProvider.OPENAI)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(USER_ID, AiProvider.OPENAI))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void rejectsAMalformedKeyBeforeItReachesStorage() {
        when(repository.findByUserIdAndProvider(USER_ID, AiProvider.ANTHROPIC))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(USER_ID, AiProvider.ANTHROPIC, "has space", null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).save(any());
    }
}
