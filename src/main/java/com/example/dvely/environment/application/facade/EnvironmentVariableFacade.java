package com.example.dvely.environment.application.facade;

import com.example.dvely.environment.application.command.EnvironmentVariableCommandService;
import com.example.dvely.environment.application.query.EnvironmentVariableQueryService;
import com.example.dvely.environment.application.result.EnvironmentVariableHistoryResult;
import com.example.dvely.environment.application.result.EnvironmentVariableResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EnvironmentVariableFacade {

    private final EnvironmentVariableQueryService queryService;
    private final EnvironmentVariableCommandService commandService;

    public List<EnvironmentVariableResult> getVariables(Long userId, Long projectId, String scope) {
        return queryService.getVariables(userId, projectId, scope);
    }

    public List<EnvironmentVariableHistoryResult> getHistory(Long userId, Long projectId, Integer limit) {
        return queryService.getHistory(userId, projectId, limit);
    }

    public EnvironmentVariableResult create(Long userId,
                                            Long projectId,
                                            String scope,
                                            String key,
                                            String value,
                                            boolean secret) {
        return commandService.create(userId, projectId, scope, key, value, secret);
    }

    public EnvironmentVariableResult update(Long userId,
                                            Long projectId,
                                            Long variableId,
                                            String value,
                                            Boolean secret) {
        return commandService.update(userId, projectId, variableId, value, secret);
    }

    public void delete(Long userId, Long projectId, Long variableId) {
        commandService.delete(userId, projectId, variableId);
    }
}
