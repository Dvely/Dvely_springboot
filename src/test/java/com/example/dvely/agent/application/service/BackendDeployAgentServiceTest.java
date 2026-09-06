package com.example.dvely.agent.application.service;

import com.example.dvely.provisioning.domain.value.ServerDeployMode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.service.CodeAgentService.CodeResult;
import com.example.dvely.agent.domain.value.AgentType;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.provisioning.application.command.DatabaseProvisioningCommandService;
import com.example.dvely.provisioning.application.command.ServerProvisioningCommandService;
import com.example.dvely.provisioning.application.result.ProvisionSubmitResult;
import com.example.dvely.provisioning.application.result.ServerProvisionSubmitResult;
import com.example.dvely.provisioning.domain.model.ProvisionedDatabase;
import com.example.dvely.provisioning.domain.repository.ProvisionedDatabaseRepository;
import com.example.dvely.provisioning.domain.value.DatabaseEngine;
import com.example.dvely.provisioning.domain.value.ProvisionMethod;
import com.example.dvely.provisioning.domain.value.ProvisionOrigin;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BackendDeployAgentServiceTest {

    @Mock private ServerProvisioningCommandService serverCommandService;
    @Mock private DatabaseProvisioningCommandService databaseCommandService;
    @Mock private ProvisionedDatabaseRepository databaseRepository;

    @InjectMocks private BackendDeployAgentService service;

    private static final Long USER = 7L;
    private static final Long PROJECT = 10L;

    private AgentStep step(Map<String, String> params) {
        return new AgentStep(AgentType.BACKEND_DEPLOY, params);
    }

    @Test
    void provisionsDatabaseAndServerWhenDbRequestedAndNoneExists() {
        when(databaseRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT)).thenReturn(List.of());
        when(databaseCommandService.provision(USER, PROJECT, ProvisionMethod.RDS, DatabaseEngine.MYSQL))
                .thenReturn(new ProvisionSubmitResult(true, null, null, List.of(1L)));
        when(serverCommandService.submit(USER, PROJECT, null, ServerDeployMode.NATIVE, null, new com.example.dvely.provisioning.domain.value.WebFrontendSpec(null, null, null), false))
                .thenReturn(new ServerProvisionSubmitResult(true, 5L, List.of(2L)));

        CodeResult result = service.execute(step(Map.of("dbEngine", "MYSQL")), USER, PROJECT);

        verify(databaseCommandService).provision(USER, PROJECT, ProvisionMethod.RDS, DatabaseEngine.MYSQL);
        verify(serverCommandService).submit(USER, PROJECT, null, ServerDeployMode.NATIVE, null, new com.example.dvely.provisioning.domain.value.WebFrontendSpec(null, null, null), false);
        assertThat(result.summary()).contains("데이터베이스");
    }

    @Test
    void skipsDatabaseWhenActiveRdsAlreadyExists() {
        when(databaseRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT))
                .thenReturn(List.of(ProvisionedDatabase.pending(PROJECT, ProvisionMethod.RDS,
                        DatabaseEngine.MYSQL, ProvisionOrigin.MANUAL)));
        when(serverCommandService.submit(USER, PROJECT, null, ServerDeployMode.NATIVE, null, new com.example.dvely.provisioning.domain.value.WebFrontendSpec(null, null, null), false))
                .thenReturn(new ServerProvisionSubmitResult(true, 5L, List.of(2L)));

        service.execute(step(Map.of("dbEngine", "MYSQL")), USER, PROJECT);

        verify(databaseCommandService, never()).provision(any(), any(), any(), any());
        verify(serverCommandService).submit(USER, PROJECT, null, ServerDeployMode.NATIVE, null, new com.example.dvely.provisioning.domain.value.WebFrontendSpec(null, null, null), false);
    }

    @Test
    void serverOnlyWhenNoDbEngine() {
        when(serverCommandService.submit(USER, PROJECT, "t3.small", ServerDeployMode.NATIVE, null, new com.example.dvely.provisioning.domain.value.WebFrontendSpec(null, null, null), false))
                .thenReturn(new ServerProvisionSubmitResult(true, 5L, List.of(2L)));

        service.execute(step(Map.of("instanceType", "t3.small")), USER, PROJECT);

        verify(databaseRepository, never()).findByProjectIdOrderByCreatedAtDesc(anyLong());
        verify(databaseCommandService, never()).provision(any(), any(), any(), any());
        verify(serverCommandService).submit(USER, PROJECT, "t3.small", ServerDeployMode.NATIVE, null, new com.example.dvely.provisioning.domain.value.WebFrontendSpec(null, null, null), false);
    }

    @Test
    void skipsWhenProjectMissing() {
        CodeResult result = service.execute(step(Map.of("dbEngine", "MYSQL")), USER, null);

        assertThat(result.summary()).contains("프로젝트");
        verify(serverCommandService, never()).submit(any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void returnsActionableMessageWhenNoCloudConnected() {
        when(serverCommandService.submit(USER, PROJECT, null, ServerDeployMode.NATIVE, null, new com.example.dvely.provisioning.domain.value.WebFrontendSpec(null, null, null), false))
                .thenThrow(new NotFoundException("백엔드 서버는 연결된 클라우드가 있어야 만들 수 있습니다."));

        CodeResult result = service.execute(step(Map.of()), USER, PROJECT);

        assertThat(result.summary()).contains("클라우드");
    }
}
