package com.example.dvely.environment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dvely.environment.domain.model.EnvironmentVariable;
import com.example.dvely.environment.domain.repository.EnvironmentVariableRepository;
import com.example.dvely.environment.domain.value.EnvironmentScope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EnvironmentValueResolverServiceTest {

    private final EnvironmentVariableRepository repository = mock(EnvironmentVariableRepository.class);
    private final EnvironmentValueResolverService service = new EnvironmentValueResolverService(repository);

    @Test
    void resolvesSecretAndNonSecretValuesAsPlaintext() {
        // Unlike EnvironmentVariableQueryService, this internal-only seam must return the real
        // plaintext for secret variables too — that's its entire purpose (see design D10 / §6).
        EnvironmentVariable secretVar = new EnvironmentVariable(11L, EnvironmentScope.PRODUCTION, "STRIPE_SECRET_KEY", "sk_live_xxx", true);
        EnvironmentVariable plainVar = new EnvironmentVariable(11L, EnvironmentScope.PRODUCTION, "API_BASE_URL", "https://api.example.com", false);
        when(repository.findByProjectIdAndScopeOrderByKeyAsc(11L, EnvironmentScope.PRODUCTION))
                .thenReturn(List.of(plainVar, secretVar));

        Map<String, String> resolved = service.resolve(11L, EnvironmentScope.PRODUCTION);

        assertThat(resolved).containsEntry("STRIPE_SECRET_KEY", "sk_live_xxx");
        assertThat(resolved).containsEntry("API_BASE_URL", "https://api.example.com");
    }

    @Test
    void keepsRepositoryKeyOrderingAsAnOrderedMap() {
        EnvironmentVariable first = new EnvironmentVariable(11L, EnvironmentScope.PREVIEW, "A_KEY", "1", false);
        EnvironmentVariable second = new EnvironmentVariable(11L, EnvironmentScope.PREVIEW, "B_KEY", "2", false);
        when(repository.findByProjectIdAndScopeOrderByKeyAsc(11L, EnvironmentScope.PREVIEW))
                .thenReturn(List.of(first, second));

        Map<String, String> resolved = service.resolve(11L, EnvironmentScope.PREVIEW);

        assertThat(resolved).isInstanceOf(LinkedHashMap.class);
        assertThat(resolved.keySet()).containsExactly("A_KEY", "B_KEY");
    }

    @Test
    void returnsEmptyMapWhenNoVariablesExistForScope() {
        when(repository.findByProjectIdAndScopeOrderByKeyAsc(11L, EnvironmentScope.PREVIEW)).thenReturn(List.of());

        assertThat(service.resolve(11L, EnvironmentScope.PREVIEW)).isEmpty();
    }
}
