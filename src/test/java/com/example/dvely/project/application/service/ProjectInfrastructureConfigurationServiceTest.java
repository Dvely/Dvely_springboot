package com.example.dvely.project.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.cloudconnection.domain.value.CloudProvider;
import com.example.dvely.project.application.result.ProjectInfrastructureChangeResult;
import com.example.dvely.project.application.result.ProjectInfrastructureConfigurationResult;
import com.example.dvely.project.domain.exception.ProjectNotFoundException;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.model.ProjectApprovalPolicy;
import com.example.dvely.project.domain.model.ProjectCloudConnectionSetting;
import com.example.dvely.project.domain.model.ProjectInfrastructureSetting;
import com.example.dvely.project.domain.model.ProjectInfrastructureSettingChange;
import com.example.dvely.project.domain.repository.ProjectApprovalPolicyRepository;
import com.example.dvely.project.domain.repository.ProjectCloudConnectionSettingRepository;
import com.example.dvely.project.domain.repository.ProjectInfrastructureSettingChangeRepository;
import com.example.dvely.project.domain.repository.ProjectInfrastructureSettingRepository;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.ComputeTier;
import com.example.dvely.project.domain.value.DeploymentArchitecture;
import com.example.dvely.project.domain.value.InfrastructureChangeAction;
import com.example.dvely.project.domain.value.InfrastructureChangeStatus;
import com.example.dvely.project.domain.value.InfrastructureConfiguration;
import com.example.dvely.project.domain.value.NetworkAccess;
import com.example.dvely.project.domain.value.StorageType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectInfrastructureConfigurationServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectCloudConnectionSettingRepository cloudConnectionSettingRepository;

    @Mock
    private CloudConnectionRepository cloudConnectionRepository;

    @Mock
    private ProjectInfrastructureSettingRepository settingRepository;

    @Mock
    private ProjectInfrastructureSettingChangeRepository changeRepository;

    @Mock
    private ProjectApprovalPolicyRepository policyRepository;

    @Mock
    private ApprovalRepository approvalRepository;

    private ProjectInfrastructureConfigurationService service;

    private final InfrastructureConfiguration requested = new InfrastructureConfiguration(
            DeploymentArchitecture.CONTAINER, ComputeTier.SMALL, StorageType.OBJECT_STORAGE, NetworkAccess.PUBLIC
    );

    @BeforeEach
    void setUp() {
        service = new ProjectInfrastructureConfigurationService(
                projectRepository,
                cloudConnectionSettingRepository,
                cloudConnectionRepository,
                settingRepository,
                changeRepository,
                policyRepository,
                approvalRepository
        );
        // lenient(): both defaults are overridden or simply never reached by some tests (e.g.
        // the "other user" 404 test replaces the ownership stub; getHistory() never reaches the
        // pending-change lookup at all) — lenient avoids Mockito's strict-stubs failing those
        // tests over an intentionally-unused default rather than an actual test bug.
        lenient().when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(mock(Project.class)));
        // toResult()'s pending-change lookup is invoked by every get()/update() call, including
        // ones this test class doesn't care about pending state for — default to "none" so each
        // test only needs to stub it when the scenario actually has a pending change.
        lenient().when(changeRepository.findByProjectIdAndStatusOrderByIdAsc(11L, InfrastructureChangeStatus.PENDING_APPROVAL))
                .thenReturn(List.of());
    }

    // ---- GET ----

    @Test
    void get_noCloudConnectionSelected_isNotConfigurable() {
        when(cloudConnectionSettingRepository.findByProjectId(11L)).thenReturn(Optional.empty());
        when(settingRepository.findByProjectId(11L)).thenReturn(Optional.empty());

        ProjectInfrastructureConfigurationResult result = service.get(1L, 11L);

        assertThat(result.configurable()).isFalse();
        assertThat(result.settings()).isNull();
        assertThat(result.pendingChange()).isNull();
    }

    @Test
    void get_selectedConnectionNotConnected_isNotConfigurable() {
        when(cloudConnectionSettingRepository.findByProjectId(11L))
                .thenReturn(Optional.of(new ProjectCloudConnectionSetting(11L, 10L)));
        when(cloudConnectionRepository.findByIdAndOwnerUserId(10L, 1L))
                .thenReturn(Optional.of(connection(CloudConnectionStatus.VALIDATED)));
        when(settingRepository.findByProjectId(11L)).thenReturn(Optional.empty());

        ProjectInfrastructureConfigurationResult result = service.get(1L, 11L);

        assertThat(result.configurable()).isFalse();
    }

    @Test
    void get_connectedSelectionAndSavedSettings_returnsConfigurableWithSettings() {
        when(cloudConnectionSettingRepository.findByProjectId(11L))
                .thenReturn(Optional.of(new ProjectCloudConnectionSetting(11L, 10L)));
        when(cloudConnectionRepository.findByIdAndOwnerUserId(10L, 1L))
                .thenReturn(Optional.of(connection(CloudConnectionStatus.CONNECTED)));
        LocalDateTime updatedAt = LocalDateTime.now();
        when(settingRepository.findByProjectId(11L))
                .thenReturn(Optional.of(new ProjectInfrastructureSetting(11L, requested, updatedAt, updatedAt)));

        ProjectInfrastructureConfigurationResult result = service.get(1L, 11L);

        assertThat(result.configurable()).isTrue();
        assertThat(result.settings().deploymentArchitecture()).isEqualTo("CONTAINER");
        assertThat(result.settings().computeTier()).isEqualTo("SMALL");
        assertThat(result.settings().storageType()).isEqualTo("OBJECT_STORAGE");
        assertThat(result.settings().networkAccess()).isEqualTo("PUBLIC");
        assertThat(result.settings().updatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void get_withPendingChange_returnsPendingChangeInResponse() {
        // Review F5: this scenario was previously unreachable in the test suite because
        // setUp()'s lenient default always answered the pending-change lookup with an empty
        // list — every existing GET/PUT test therefore silently asserted (or simply never
        // checked) pendingChange()==null, even the "policy ON" PUT test whose whole point is
        // creating a pending change. Explicitly overriding the stub here proves the non-null
        // shape is actually wired through.
        connectAsConnected();
        when(settingRepository.findByProjectId(11L)).thenReturn(Optional.empty());
        ProjectInfrastructureSettingChange pending = ProjectInfrastructureSettingChange.pendingApproval(
                11L, InfrastructureChangeAction.CREATED, requested, 77L, 1L
        );
        when(changeRepository.findByProjectIdAndStatusOrderByIdAsc(11L, InfrastructureChangeStatus.PENDING_APPROVAL))
                .thenReturn(List.of(pending));

        ProjectInfrastructureConfigurationResult result = service.get(1L, 11L);

        assertThat(result.pendingChange()).isNotNull();
        assertThat(result.pendingChange().approvalId()).isEqualTo(77L);
        assertThat(result.pendingChange().action()).isEqualTo("CREATED");
        assertThat(result.pendingChange().deploymentArchitecture()).isEqualTo("CONTAINER");
        assertThat(result.pendingChange().computeTier()).isEqualTo("SMALL");
        assertThat(result.pendingChange().storageType()).isEqualTo("OBJECT_STORAGE");
        assertThat(result.pendingChange().networkAccess()).isEqualTo("PUBLIC");
    }

    @Test
    void get_otherUsersProject_throwsProjectNotFound() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(1L, 11L))
                .isInstanceOf(ProjectNotFoundException.class);
    }

    // ---- PUT: guards ----

    @Test
    void update_otherUsersProject_throwsProjectNotFound() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(1L, 11L, "CONTAINER", "SMALL", "OBJECT_STORAGE", "PUBLIC"))
                .isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    void update_noConnectedCloudConnectionSelected_throwsIllegalState() {
        when(cloudConnectionSettingRepository.findByProjectId(11L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(1L, 11L, "CONTAINER", "SMALL", "OBJECT_STORAGE", "PUBLIC"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("클라우드 연결을 먼저 선택해야 합니다");

        verify(settingRepository, never()).save(any());
        verify(approvalRepository, never()).save(any());
    }

    @Test
    void update_pendingChangeAlreadyExists_throwsIllegalStateWithApprovalId() {
        connectAsConnected();
        ProjectInfrastructureSettingChange pending = ProjectInfrastructureSettingChange.pendingApproval(
                11L, InfrastructureChangeAction.CREATED, requested, 77L, 1L
        );
        when(changeRepository.findByProjectIdAndStatusOrderByIdAsc(11L, InfrastructureChangeStatus.PENDING_APPROVAL))
                .thenReturn(List.of(pending));

        assertThatThrownBy(() -> service.update(1L, 11L, "CONTAINER", "SMALL", "OBJECT_STORAGE", "PUBLIC"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("승인 대기 중인 인프라 설정 변경이 이미 있습니다")
                .hasMessageContaining("approvalId=77");

        verify(settingRepository, never()).save(any());
        verify(approvalRepository, never()).save(any());
        verify(changeRepository, never()).save(any());
    }

    @Test
    void update_identicalToCurrentValue_isNoOpAndCreatesNothing() {
        connectAsConnected();
        when(settingRepository.findByProjectId(11L))
                .thenReturn(Optional.of(new ProjectInfrastructureSetting(11L, requested)));

        ProjectInfrastructureConfigurationResult result =
                service.update(1L, 11L, "CONTAINER", "SMALL", "OBJECT_STORAGE", "PUBLIC");

        assertThat(result.configurable()).isTrue();
        verify(settingRepository, never()).save(any());
        verify(approvalRepository, never()).save(any());
        verify(changeRepository, never()).save(any());
    }

    // ---- PUT: policy branches ----

    @Test
    void update_policyOff_appliesImmediatelyAndRecordsAppliedHistoryWithoutApproval() {
        connectAsConnected();
        when(settingRepository.findByProjectId(11L)).thenReturn(Optional.empty());
        when(policyRepository.findByProjectId(11L))
                .thenReturn(Optional.of(new ProjectApprovalPolicy(11L, true, true, true, false)));
        when(settingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(changeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.update(1L, 11L, "CONTAINER", "SMALL", "OBJECT_STORAGE", "PUBLIC");

        ArgumentCaptor<ProjectInfrastructureSetting> settingCaptor =
                ArgumentCaptor.forClass(ProjectInfrastructureSetting.class);
        verify(settingRepository).save(settingCaptor.capture());
        assertThat(settingCaptor.getValue().getConfiguration()).isEqualTo(requested);

        ArgumentCaptor<ProjectInfrastructureSettingChange> changeCaptor =
                ArgumentCaptor.forClass(ProjectInfrastructureSettingChange.class);
        verify(changeRepository).save(changeCaptor.capture());
        ProjectInfrastructureSettingChange savedChange = changeCaptor.getValue();
        assertThat(savedChange.getStatus()).isEqualTo(InfrastructureChangeStatus.APPLIED);
        assertThat(savedChange.getAction()).isEqualTo(InfrastructureChangeAction.CREATED);
        assertThat(savedChange.getApprovalId()).isNull();

        verify(approvalRepository, never()).save(any());
    }

    @Test
    void update_policyOn_createsStandaloneApprovalAndPendingChangeWithoutApplyingSetting() {
        connectAsConnected();
        ProjectInfrastructureSetting existingSetting = new ProjectInfrastructureSetting(
                11L,
                new InfrastructureConfiguration(
                        DeploymentArchitecture.SERVER, ComputeTier.MICRO, StorageType.NONE, NetworkAccess.PRIVATE
                )
        );
        when(settingRepository.findByProjectId(11L)).thenReturn(Optional.of(existingSetting));
        when(policyRepository.findByProjectId(11L))
                .thenReturn(Optional.of(new ProjectApprovalPolicy(11L, true, true, true, true)));
        when(approvalRepository.save(any())).thenAnswer(invocation -> {
            Approval toSave = invocation.getArgument(0);
            return new Approval(
                    99L, toSave.getOwnerUserId(), toSave.getProjectId(), toSave.getConversationId(),
                    toSave.getTaskId(), toSave.getType(), toSave.getStatus(), toSave.getSummary(),
                    LocalDateTime.now(), null
            );
        });
        // Review F5: captures what was actually persisted (with a generated changeId, as a real
        // save() would return) and feeds it back through the pending-change lookup — so the
        // assertions below on the *returned result* exercise the same toResult() re-read path a
        // real GET/PUT response goes through, instead of only checking what was passed to save().
        AtomicReference<ProjectInfrastructureSettingChange> persistedChange = new AtomicReference<>();
        when(changeRepository.save(any())).thenAnswer(invocation -> {
            ProjectInfrastructureSettingChange toSave = invocation.getArgument(0);
            ProjectInfrastructureSettingChange persisted = new ProjectInfrastructureSettingChange(
                    55L, toSave.getProjectId(), toSave.getAction(), toSave.getStatus(),
                    toSave.getConfiguration(), toSave.getApprovalId(), toSave.getActorUserId(),
                    LocalDateTime.now(), toSave.getDecidedAt()
            );
            persistedChange.set(persisted);
            return persisted;
        });
        when(changeRepository.findByProjectIdAndStatusOrderByIdAsc(11L, InfrastructureChangeStatus.PENDING_APPROVAL))
                .thenAnswer(invocation -> {
                    ProjectInfrastructureSettingChange change = persistedChange.get();
                    return change == null ? List.of() : List.of(change);
                });

        ProjectInfrastructureConfigurationResult result =
                service.update(1L, 11L, "CONTAINER", "SMALL", "OBJECT_STORAGE", "PUBLIC");

        ArgumentCaptor<Approval> approvalCaptor = ArgumentCaptor.forClass(Approval.class);
        verify(approvalRepository).save(approvalCaptor.capture());
        Approval savedApproval = approvalCaptor.getValue();
        assertThat(savedApproval.getType()).isEqualTo(ApprovalType.INFRA_OPERATION);
        assertThat(savedApproval.getTaskId()).isNull();
        assertThat(savedApproval.isStandalone()).isTrue();
        assertThat(savedApproval.getStatus()).isEqualTo(ApprovalStatus.PENDING);
        // Review F3 / design §3.2: exact summary format, not just the bare configuration text.
        assertThat(savedApproval.getSummary())
                .isEqualTo("인프라 설정 변경 요청: " + requested.summaryText());

        ArgumentCaptor<ProjectInfrastructureSettingChange> changeCaptor =
                ArgumentCaptor.forClass(ProjectInfrastructureSettingChange.class);
        verify(changeRepository).save(changeCaptor.capture());
        ProjectInfrastructureSettingChange savedChange = changeCaptor.getValue();
        assertThat(savedChange.getStatus()).isEqualTo(InfrastructureChangeStatus.PENDING_APPROVAL);
        assertThat(savedChange.getAction()).isEqualTo(InfrastructureChangeAction.UPDATED);
        assertThat(savedChange.getApprovalId()).isEqualTo(99L);

        verify(settingRepository, never()).save(any());

        // Review F5: the response itself — not just the save() arguments — must carry the
        // pending change. settings must stay at the pre-existing value (SERVER/MICRO/NONE/
        // PRIVATE) since nothing was applied yet.
        assertThat(result.pendingChange()).isNotNull();
        assertThat(result.pendingChange().changeId()).isEqualTo(55L);
        assertThat(result.pendingChange().approvalId()).isEqualTo(99L);
        assertThat(result.pendingChange().action()).isEqualTo("UPDATED");
        assertThat(result.pendingChange().deploymentArchitecture()).isEqualTo("CONTAINER");
        assertThat(result.settings()).isNotNull();
        assertThat(result.settings().deploymentArchitecture()).isEqualTo("SERVER");
    }

    @Test
    void update_missingPolicyRow_defaultsToApprovalRequired() {
        connectAsConnected();
        when(settingRepository.findByProjectId(11L)).thenReturn(Optional.empty());
        when(policyRepository.findByProjectId(11L)).thenReturn(Optional.empty());
        when(approvalRepository.save(any())).thenAnswer(invocation -> {
            Approval toSave = invocation.getArgument(0);
            return new Approval(
                    99L, toSave.getOwnerUserId(), toSave.getProjectId(), toSave.getConversationId(),
                    toSave.getTaskId(), toSave.getType(), toSave.getStatus(), toSave.getSummary(),
                    LocalDateTime.now(), null
            );
        });
        when(changeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.update(1L, 11L, "CONTAINER", "SMALL", "OBJECT_STORAGE", "PUBLIC");

        ArgumentCaptor<Approval> approvalCaptor = ArgumentCaptor.forClass(Approval.class);
        verify(approvalRepository).save(approvalCaptor.capture());
        // Review F3: the CREATED-branch wording ("저장") is distinct from UPDATED's ("변경"),
        // covered separately by update_policyOn_createsStandaloneApprovalAndPendingChangeWithoutApplyingSetting.
        assertThat(approvalCaptor.getValue().getSummary())
                .isEqualTo("인프라 설정 저장 요청: " + requested.summaryText());
        verify(settingRepository, never()).save(any());
    }

    @Test
    void update_firstEverSave_actionIsCreated() {
        connectAsConnected();
        when(settingRepository.findByProjectId(11L)).thenReturn(Optional.empty());
        when(policyRepository.findByProjectId(11L))
                .thenReturn(Optional.of(new ProjectApprovalPolicy(11L, true, true, true, false)));
        when(settingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(changeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.update(1L, 11L, "CONTAINER", "SMALL", "OBJECT_STORAGE", "PUBLIC");

        ArgumentCaptor<ProjectInfrastructureSettingChange> changeCaptor =
                ArgumentCaptor.forClass(ProjectInfrastructureSettingChange.class);
        verify(changeRepository).save(changeCaptor.capture());
        assertThat(changeCaptor.getValue().getAction()).isEqualTo(InfrastructureChangeAction.CREATED);
    }

    @Test
    void update_reSaveOverExistingSetting_actionIsUpdated() {
        connectAsConnected();
        ProjectInfrastructureSetting existingSetting = new ProjectInfrastructureSetting(
                11L,
                new InfrastructureConfiguration(
                        DeploymentArchitecture.SERVER, ComputeTier.MICRO, StorageType.NONE, NetworkAccess.PRIVATE
                )
        );
        when(settingRepository.findByProjectId(11L)).thenReturn(Optional.of(existingSetting));
        when(policyRepository.findByProjectId(11L))
                .thenReturn(Optional.of(new ProjectApprovalPolicy(11L, true, true, true, false)));
        when(settingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(changeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.update(1L, 11L, "CONTAINER", "SMALL", "OBJECT_STORAGE", "PUBLIC");

        ArgumentCaptor<ProjectInfrastructureSettingChange> changeCaptor =
                ArgumentCaptor.forClass(ProjectInfrastructureSettingChange.class);
        verify(changeRepository).save(changeCaptor.capture());
        assertThat(changeCaptor.getValue().getAction()).isEqualTo(InfrastructureChangeAction.UPDATED);
    }

    // ---- history ----

    @Test
    void getHistory_otherUsersProject_throwsProjectNotFound() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getHistory(1L, 11L, null))
                .isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    void getHistory_clampsNullZeroNegativeAndOversizedLimits() {
        when(changeRepository.findByProjectIdOrderByCreatedAtDescIdDesc(eq(11L), anyInt()))
                .thenReturn(List.of());

        service.getHistory(1L, 11L, null);
        service.getHistory(1L, 11L, 0);
        service.getHistory(1L, 11L, -5);
        service.getHistory(1L, 11L, 201);

        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(changeRepository, times(4))
                .findByProjectIdOrderByCreatedAtDescIdDesc(eq(11L), limitCaptor.capture());
        assertThat(limitCaptor.getAllValues()).containsExactly(50, 50, 50, 200);
    }

    @Test
    void getHistory_mapsEveryStatusIncludingPendingAndRejected() {
        ProjectInfrastructureSettingChange applied =
                ProjectInfrastructureSettingChange.applied(11L, InfrastructureChangeAction.CREATED, requested, 1L);
        ProjectInfrastructureSettingChange rejected = ProjectInfrastructureSettingChange.pendingApproval(
                11L, InfrastructureChangeAction.UPDATED, requested, 77L, 1L
        );
        rejected.markRejected();
        when(changeRepository.findByProjectIdOrderByCreatedAtDescIdDesc(11L, 50))
                .thenReturn(List.of(rejected, applied));

        List<ProjectInfrastructureChangeResult> history = service.getHistory(1L, 11L, null);

        assertThat(history).extracting(ProjectInfrastructureChangeResult::status)
                .containsExactly("REJECTED", "APPLIED");
    }

    private void connectAsConnected() {
        when(cloudConnectionSettingRepository.findByProjectId(11L))
                .thenReturn(Optional.of(new ProjectCloudConnectionSetting(11L, 10L)));
        when(cloudConnectionRepository.findByIdAndOwnerUserId(10L, 1L))
                .thenReturn(Optional.of(connection(CloudConnectionStatus.CONNECTED)));
    }

    private CloudConnection connection(CloudConnectionStatus status) {
        return new CloudConnection(
                10L,
                1L,
                CloudProvider.AWS,
                "production",
                "123456789012",
                "ap-northeast-2",
                null,
                "ACCESS_KEY",
                "AKIA1234567890ABCDEF",
                "abcdefghijklmnopqrstuvwxyz1234567890ABCD",
                null,
                null,
                null,
                null,
                null,
                status,
                LocalDateTime.now(),
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }
}
