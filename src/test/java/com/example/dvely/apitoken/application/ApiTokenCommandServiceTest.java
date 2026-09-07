package com.example.dvely.apitoken.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.apitoken.application.command.ApiTokenCommandService;
import com.example.dvely.apitoken.application.result.IssuedApiTokenResult;
import com.example.dvely.apitoken.domain.model.ApiToken;
import com.example.dvely.apitoken.domain.repository.ApiTokenRepository;
import com.example.dvely.apitoken.domain.service.ApiTokenGenerator;
import com.example.dvely.apitoken.domain.value.ApiTokenScope;
import com.example.dvely.common.exception.NotFoundException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ApiTokenCommandServiceTest {

    private static final Long USER_ID = 7L;

    private ApiTokenRepository repository;
    private ApiTokenCommandService service;

    @BeforeEach
    void setUp() {
        repository = mock(ApiTokenRepository.class);
        service = new ApiTokenCommandService(repository);
        // Echo back with an id, as a real save would.
        when(repository.save(any())).thenAnswer(i -> {
            ApiToken t = i.getArgument(0);
            return new ApiToken(1L, t.getUserId(), t.getTokenHash(), t.getTokenPrefix(),
                    t.getScope(), t.getLabel(), t.getExpiresAt(), null, LocalDateTime.now());
        });
    }

    @Test
    void issuanceReturnsThePlaintextOnceAndStoresOnlyItsHash() {
        IssuedApiTokenResult issued = service.issue(USER_ID, ApiTokenScope.READ, "노트북", null);

        ArgumentCaptor<ApiToken> saved = ArgumentCaptor.captor();
        verify(repository).save(saved.capture());

        assertThat(issued.plaintext()).startsWith("qp_");
        assertThat(saved.getValue().getTokenHash())
                .isEqualTo(ApiTokenGenerator.hash(issued.plaintext()));
        // What went to storage must not be the token itself.
        assertThat(saved.getValue().getTokenHash()).isNotEqualTo(issued.plaintext());
    }

    @Test
    void theListedResultCarriesNoPlaintextField() {
        IssuedApiTokenResult issued = service.issue(USER_ID, ApiTokenScope.READ, null, null);

        // Only IssuedApiTokenResult has the plaintext; the nested token result is what list() also
        // returns, so nothing downstream of a list can leak one.
        assertThat(issued.token().toString()).doesNotContain(issued.plaintext());
        assertThat(issued.toString()).doesNotContain(issued.plaintext());
    }

    @Test
    void defaultsToNinetyDays() {
        LocalDateTime before = LocalDateTime.now();

        service.issue(USER_ID, ApiTokenScope.READ, null, null);

        ArgumentCaptor<ApiToken> saved = ArgumentCaptor.captor();
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getExpiresAt())
                .isBetween(before.plusDays(90).minusMinutes(1), LocalDateTime.now().plusDays(90));
    }

    @Test
    void honoursAnExplicitLifetime() {
        service.issue(USER_ID, ApiTokenScope.WRITE, null, 7);

        ArgumentCaptor<ApiToken> saved = ArgumentCaptor.captor();
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getExpiresAt()).isBefore(LocalDateTime.now().plusDays(8));
    }

    @Test
    void refusesAnUnboundedOrAbsurdLifetime() {
        // A hard ceiling means a token nobody remembers issuing eventually stops working by itself.
        assertThatThrownBy(() -> service.issue(USER_ID, ApiTokenScope.READ, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.issue(USER_ID, ApiTokenScope.READ, null, 366))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void revokingDeletesScopedToTheOwner() {
        when(repository.deleteByIdAndUserId(1L, USER_ID)).thenReturn(true);

        service.revoke(USER_ID, 1L);

        // Scoped delete rather than find-then-check: another user's token is simply not found, so
        // there is no branch where the ownership check could be skipped.
        verify(repository).deleteByIdAndUserId(1L, USER_ID);
    }

    @Test
    void revokingSomeoneElsesTokenIsANotFoundNotAForbidden() {
        when(repository.deleteByIdAndUserId(99L, USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.revoke(USER_ID, 99L))
                .isInstanceOf(NotFoundException.class);
    }
}
