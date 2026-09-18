package com.example.dvely.agent.application.service;

import com.example.dvely.provisioning.domain.value.ServerDeployMode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.dto.ClarificationRequest;
import com.example.dvely.agent.application.exception.AgentInputRequiredException;
import com.example.dvely.agent.application.service.CodeAgentService.CodeResult;
import com.example.dvely.agent.domain.value.AgentType;
import com.example.dvely.agent.infrastructure.store.InputWaitStore;
import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.project.domain.model.ProjectCloudConnectionSetting;
import com.example.dvely.project.domain.repository.ProjectCloudConnectionSettingRepository;
import com.example.dvely.provisioning.application.command.DatabaseProvisioningCommandService;
import com.example.dvely.provisioning.application.command.ServerProvisioningCommandService;
import com.example.dvely.provisioning.application.result.ProvisionSubmitResult;
import com.example.dvely.provisioning.application.result.ServerProvisionSubmitResult;
import com.example.dvely.provisioning.domain.model.ProvisionedDatabase;
import com.example.dvely.provisioning.domain.repository.ProvisionedDatabaseRepository;
import com.example.dvely.provisioning.domain.value.DatabaseEngine;
import com.example.dvely.provisioning.domain.value.ProvisionMethod;
import com.example.dvely.provisioning.domain.value.ProvisionOrigin;
import com.example.dvely.provisioning.domain.value.WebFrontendSpec;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    @Mock private ProjectCloudConnectionSettingRepository cloudConnectionSettingRepository;
    @Mock private CloudConnectionRepository cloudConnectionRepository;
    @Mock private InputWaitStore inputWaitStore;

    @InjectMocks private BackendDeployAgentService service;

    private static final Long USER = 7L;
    private static final Long PROJECT = 10L;
    private static final Long CONV = 39L;   // #1 저위험: 배포 승인에 실리는 대화 id
    private static final String TASK = "task-1";
    private static final WebFrontendSpec NO_WEB = new WebFrontendSpec(null, null, null);

    private AgentStep step(Map<String, String> params) {
        return new AgentStep(AgentType.BACKEND_DEPLOY, params);
    }

    /** 실제 배포에 쓸 수 있는 연결(선택됨 + CONNECTED)이 있는 상태로 만든다 — 사전 게이트를 통과시킨다. */
    private void givenConnectedCloud() {
        ProjectCloudConnectionSetting setting = mock(ProjectCloudConnectionSetting.class);
        when(setting.getCloudConnectionId()).thenReturn(3L);
        CloudConnection connection = mock(CloudConnection.class);
        when(connection.getStatus()).thenReturn(CloudConnectionStatus.CONNECTED);
        when(cloudConnectionSettingRepository.findByProjectId(PROJECT)).thenReturn(Optional.of(setting));
        when(cloudConnectionRepository.findByIdAndOwnerUserId(3L, USER)).thenReturn(Optional.of(connection));
    }

    @Test
    void provisionsDatabaseAndServerWhenDbRequestedAndNoneExists() {
        givenConnectedCloud();
        when(databaseRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT)).thenReturn(List.of());
        when(databaseCommandService.provision(USER, PROJECT, ProvisionMethod.RDS, DatabaseEngine.MYSQL, CONV))
                .thenReturn(new ProvisionSubmitResult(true, null, null, List.of(1L)));
        when(serverCommandService.submit(USER, PROJECT, null, ServerDeployMode.NATIVE, null, NO_WEB, false, CONV))
                .thenReturn(new ServerProvisionSubmitResult(true, 5L, List.of(2L)));

        CodeResult result = service.execute(step(Map.of("dbEngine", "MYSQL")), USER, PROJECT, CONV, TASK);

        verify(databaseCommandService).provision(USER, PROJECT, ProvisionMethod.RDS, DatabaseEngine.MYSQL, CONV);
        verify(serverCommandService).submit(USER, PROJECT, null, ServerDeployMode.NATIVE, null, NO_WEB, false, CONV);
        assertThat(result.summary()).contains("데이터베이스");
    }

    @Test
    void skipsDatabaseWhenActiveRdsAlreadyExists() {
        givenConnectedCloud();
        when(databaseRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT))
                .thenReturn(List.of(ProvisionedDatabase.pending(PROJECT, ProvisionMethod.RDS,
                        DatabaseEngine.MYSQL, ProvisionOrigin.MANUAL)));
        when(serverCommandService.submit(USER, PROJECT, null, ServerDeployMode.NATIVE, null, NO_WEB, false, CONV))
                .thenReturn(new ServerProvisionSubmitResult(true, 5L, List.of(2L)));

        service.execute(step(Map.of("dbEngine", "MYSQL")), USER, PROJECT, CONV, TASK);

        verify(databaseCommandService, never()).provision(any(), any(), any(), any(), any());
        verify(serverCommandService).submit(USER, PROJECT, null, ServerDeployMode.NATIVE, null, NO_WEB, false, CONV);
    }

    @Test
    void serverOnlyWhenNoDbEngine() {
        givenConnectedCloud();
        when(serverCommandService.submit(USER, PROJECT, "t3.small", ServerDeployMode.NATIVE, null, NO_WEB, false, CONV))
                .thenReturn(new ServerProvisionSubmitResult(true, 5L, List.of(2L)));

        service.execute(step(Map.of("instanceType", "t3.small")), USER, PROJECT, CONV, TASK);

        verify(databaseRepository, never()).findByProjectIdOrderByCreatedAtDesc(anyLong());
        verify(databaseCommandService, never()).provision(any(), any(), any(), any(), any());
        verify(serverCommandService).submit(USER, PROJECT, "t3.small", ServerDeployMode.NATIVE, null, NO_WEB, false, CONV);
    }

    @Test
    void skipsWhenProjectMissing() {
        CodeResult result = service.execute(step(Map.of("dbEngine", "MYSQL")), USER, null, CONV, TASK);

        assertThat(result.summary()).contains("프로젝트");
        verify(serverCommandService, never()).submit(any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void pausesForCloudConnectionGuideWhenNoCloudConnected() {
        // 연결이 없으면(설정 미스텁 → 없음) 배포를 시작하지 않고 CONNECT_CLOUD 조치를 내 태스크를
        // WAITING_INPUT 으로 멈춘다 — FE 가 연결 가이드를 슬라이드해 띄우게 한다.
        assertThatThrownBy(() -> service.execute(step(Map.of()), USER, PROJECT, CONV, TASK))
                .isInstanceOf(AgentInputRequiredException.class)
                .satisfies(e -> {
                    ClarificationRequest c = ((AgentInputRequiredException) e).getClarification();
                    assertThat(c).isNotNull();
                    assertThat(c.actionType()).isEqualTo(ClarificationRequest.ActionType.CONNECT_CLOUD);
                });
        verify(serverCommandService, never()).submit(any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void connectedButNotVerifiedStillPausesForGuide() {
        // 연결이 선택돼 있어도 CONNECTED 가 아니면(검증 전/실패) 프로비저닝이 못 하므로 가이드로 보낸다.
        ProjectCloudConnectionSetting setting = mock(ProjectCloudConnectionSetting.class);
        when(setting.getCloudConnectionId()).thenReturn(3L);
        CloudConnection connection = mock(CloudConnection.class);
        when(connection.getStatus()).thenReturn(CloudConnectionStatus.INVALID_CREDENTIAL);
        when(cloudConnectionSettingRepository.findByProjectId(PROJECT)).thenReturn(Optional.of(setting));
        when(cloudConnectionRepository.findByIdAndOwnerUserId(3L, USER)).thenReturn(Optional.of(connection));

        assertThatThrownBy(() -> service.execute(step(Map.of()), USER, PROJECT, CONV, TASK))
                .isInstanceOf(AgentInputRequiredException.class);
        verify(serverCommandService, never()).submit(any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void returnsActionableMessageWhenSubmitRejectsForOtherReason() {
        // 연결은 있는데 submit 이 다른 사유로 거부하면(NotFound/IllegalState) 태스크를 실패로 떨구지 않고
        // 그 메시지를 그대로 보여준다.
        givenConnectedCloud();
        when(serverCommandService.submit(USER, PROJECT, null, ServerDeployMode.NATIVE, null, NO_WEB, false, CONV))
                .thenThrow(new NotFoundException("배포할 소스가 아직 준비되지 않았습니다."));

        CodeResult result = service.execute(step(Map.of()), USER, PROJECT, CONV, TASK);

        assertThat(result.summary()).contains("소스");
    }
}
