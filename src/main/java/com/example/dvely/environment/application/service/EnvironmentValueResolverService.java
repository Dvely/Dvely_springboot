package com.example.dvely.environment.application.service;

import com.example.dvely.environment.application.port.in.EnvironmentValueResolver;
import com.example.dvely.environment.domain.repository.EnvironmentVariableRepository;
import com.example.dvely.environment.domain.value.EnvironmentScope;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link EnvironmentValueResolver}: reads straight through the repository. No extra
 * decryption step is needed here — {@code AesEncryptor} already runs at the JPA layer, so the
 * domain objects this repository returns hold plaintext in memory already.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EnvironmentValueResolverService implements EnvironmentValueResolver {

    private final EnvironmentVariableRepository environmentVariableRepository;

    @Override
    public Map<String, String> resolve(Long projectId, EnvironmentScope scope) {
        Map<String, String> values = new LinkedHashMap<>();
        environmentVariableRepository.findByProjectIdAndScopeOrderByKeyAsc(projectId, scope)
                .forEach(variable -> values.put(variable.getKey(), variable.getValue()));
        return values;
    }
}
