package com.example.dvely.apitoken.application.facade;

import com.example.dvely.apitoken.application.command.ApiTokenCommandService;
import com.example.dvely.apitoken.application.query.ApiTokenQueryService;
import com.example.dvely.apitoken.application.result.ApiTokenResult;
import com.example.dvely.apitoken.application.result.IssuedApiTokenResult;
import com.example.dvely.apitoken.domain.value.ApiTokenScope;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ApiTokenFacade {

    private final ApiTokenQueryService queryService;
    private final ApiTokenCommandService commandService;

    public List<ApiTokenResult> list(Long userId) {
        return queryService.list(userId);
    }

    public IssuedApiTokenResult issue(Long userId, ApiTokenScope scope, String label, Integer expiresInDays) {
        return commandService.issue(userId, scope, label, expiresInDays);
    }

    public void revoke(Long userId, Long apiTokenId) {
        commandService.revoke(userId, apiTokenId);
    }
}
