package com.example.dvely.project.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.cloudconnection.domain.value.CloudProvider;
import com.example.dvely.project.application.result.ProjectCostBudgetResult;
import com.example.dvely.project.domain.exception.ProjectNotFoundException;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.model.ProjectBudgetSetting;
import com.example.dvely.project.domain.model.ProjectCloudConnectionSetting;
import com.example.dvely.project.domain.model.ProjectInfrastructureSetting;
import com.example.dvely.project.domain.repository.ProjectBudgetSettingRepository;
import com.example.dvely.project.domain.repository.ProjectCloudConnectionSettingRepository;
import com.example.dvely.project.domain.repository.ProjectInfrastructureSettingRepository;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.service.InfrastructureCostEstimator;
import com.example.dvely.project.domain.value.ComputeTier;
import com.example.dvely.project.domain.value.DeploymentArchitecture;
import com.example.dvely.project.domain.value.InfrastructureConfiguration;
import com.example.dvely.project.domain.value.NetworkAccess;
import com.example.dvely.project.domain.value.StorageType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectCostBudgetServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectCloudConnectionSettingRepository cloudConnectionSettingRepository;

    @Mock
    private CloudConnectionRepository cloudConnectionRepository;

    @Mock
    private ProjectInfrastructureSettingRepository infrastructureSettingRepository;

    @Mock
    private ProjectBudgetSettingRepository budgetSettingRepository;

    private ProjectCostBudgetService service;

    // AWS/SERVER/MICRO/NONE/PRIVATE => 8.50 (compute) + 0.00 (storage) + 0.90 (network) = 9.40
    private final InfrastructureConfiguration minimalConfiguration = new InfrastructureConfiguration(
            DeploymentArchitecture.SERVER, ComputeTier.MICRO, StorageType.NONE, NetworkAccess.PRIVATE
    );

    @BeforeEach
    void setUp() {
        // Real estimator, not mocked: it is a stateless pure function (design D7) so there is
        // nothing worth mocking, and using the real implementation here lets these tests assert
        // on the actual dollar amounts the service returns end-to-end.
        service = new ProjectCostBudgetService(
                projectRepository,
                cloudConnectionSettingRepository,
                cloudConnectionRepository,
                infrastructureSettingRepository,
                budgetSettingRepository,
                new InfrastructureCostEstimator()
        );
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(mock(Project.class)));
    }

    // ---- GET: ownership ----

    @Test
    void get_otherUsersProject_throwsProjectNotFound() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(1L, 11L))
                .isInstanceOf(ProjectNotFoundException.class);
    }

    // ---- GET: costAvailable derivation ----

    @Test
    void get_noInfrastructureConfigured_costUnavailableWithNullEstimateAndEmptyResourceCosts() {
        when(infrastructureSettingRepository.findByProjectId(11L)).thenReturn(Optional.empty());
        when(cloudConnectionSettingRepository.findByProjectId(11L)).thenReturn(Optional.empty());
        when(budgetSettingRepository.findByProjectId(11L)).thenReturn(Optional.empty());

        ProjectCostBudgetResult result = service.get(1L, 11L);

        assertThat(result.costAvailable()).isFalse();
        assertThat(result.provider()).isNull();
        assertThat(result.estimatedMonthlyCost()).isNull();
        assertThat(result.resourceCosts()).isEmpty();
        // assumptions/priceTableVersion describe the static price table itself, not a per-project
        // computation, so they stay populated even when nothing is computable yet.
        assertThat(result.assumptions()).hasSize(3);
        assertThat(result.priceTableVersion()).isEqualTo(InfrastructureCostEstimator.PRICE_TABLE_VERSION);
        assertThat(result.currency()).isEqualTo("USD");
    }

    @Test
    void get_selectedConnectionNotConnected_costUnavailable() {
        when(infrastructureSettingRepository.findByProjectId(11L))
                .thenReturn(Optional.of(new ProjectInfrastructureSetting(11L, minimalConfiguration)));
        when(cloudConnectionSettingRepository.findByProjectId(11L))
                .thenReturn(Optional.of(new ProjectCloudConnectionSetting(11L, 10L)));
        when(cloudConnectionRepository.findByIdAndOwnerUserId(10L, 1L))
                .thenReturn(Optional.of(connection(CloudConnectionStatus.VALIDATED)));
        when(budgetSettingRepository.findByProjectId(11L)).thenReturn(Optional.empty());

        ProjectCostBudgetResult result = service.get(1L, 11L);

        assertThat(result.costAvailable()).isFalse();
        assertThat(result.estimatedMonthlyCost()).isNull();
    }

    @Test
    void get_configuredAndConnected_computesEstimateMatchingResourceCostSum() {
        when(infrastructureSettingRepository.findByProjectId(11L))
                .thenReturn(Optional.of(new ProjectInfrastructureSetting(11L, minimalConfiguration)));
        when(cloudConnectionSettingRepository.findByProjectId(11L))
                .thenReturn(Optional.of(new ProjectCloudConnectionSetting(11L, 10L)));
        when(cloudConnectionRepository.findByIdAndOwnerUserId(10L, 1L))
                .thenReturn(Optional.of(connection(CloudConnectionStatus.CONNECTED)));
        when(budgetSettingRepository.findByProjectId(11L)).thenReturn(Optional.empty());

        ProjectCostBudgetResult result = service.get(1L, 11L);

        assertThat(result.costAvailable()).isTrue();
        assertThat(result.provider()).isEqualTo("AWS");
        assertThat(result.estimatedMonthlyCost()).isEqualByComparingTo("9.40");
        assertThat(result.resourceCosts()).hasSize(3);
        BigDecimal sum = result.resourceCosts().stream()
                .map(ProjectCostBudgetResult.ResourceCost::monthlyCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(result.estimatedMonthlyCost()).isEqualByComparingTo(sum);
    }

    // ---- GET: budgetStatus derivation ----

    @Test
    void get_noBudgetSet_statusIsNoBudget() {
        when(infrastructureSettingRepository.findByProjectId(11L))
                .thenReturn(Optional.of(new ProjectInfrastructureSetting(11L, minimalConfiguration)));
        when(cloudConnectionSettingRepository.findByProjectId(11L))
                .thenReturn(Optional.of(new ProjectCloudConnectionSetting(11L, 10L)));
        when(cloudConnectionRepository.findByIdAndOwnerUserId(10L, 1L))
                .thenReturn(Optional.of(connection(CloudConnectionStatus.CONNECTED)));
        when(budgetSettingRepository.findByProjectId(11L)).thenReturn(Optional.empty());

        ProjectCostBudgetResult result = service.get(1L, 11L);

        assertThat(result.budgetStatus()).isEqualTo("NO_BUDGET");
        assertThat(result.budget()).isNull();
        assertThat(result.budgetUsagePercent()).isNull();
    }

    @Test
    void get_estimateExceedsBudget_statusIsOverBudgetWithUsagePercent() {
        when(infrastructureSettingRepository.findByProjectId(11L))
                .thenReturn(Optional.of(new ProjectInfrastructureSetting(11L, minimalConfiguration)));
        when(cloudConnectionSettingRepository.findByProjectId(11L))
                .thenReturn(Optional.of(new ProjectCloudConnectionSetting(11L, 10L)));
        when(cloudConnectionRepository.findByIdAndOwnerUserId(10L, 1L))
                .thenReturn(Optional.of(connection(CloudConnectionStatus.CONNECTED)));
        LocalDateTime updatedAt = LocalDateTime.now();
        when(budgetSettingRepository.findByProjectId(11L)).thenReturn(Optional.of(
                new ProjectBudgetSetting(11L, new BigDecimal("5.00"), "USD", updatedAt, updatedAt)));

        ProjectCostBudgetResult result = service.get(1L, 11L);

        // estimate 9.40 > budget 5.00
        assertThat(result.budgetStatus()).isEqualTo("OVER_BUDGET");
        // (9.40 / 5.00) * 100 = 188.0
        assertThat(result.budgetUsagePercent()).isEqualByComparingTo("188.0");
    }

    @Test
    void get_estimateExactlyEqualsBudget_statusIsWithinBudget_boundaryCase() {
        when(infrastructureSettingRepository.findByProjectId(11L))
                .thenReturn(Optional.of(new ProjectInfrastructureSetting(11L, minimalConfiguration)));
        when(cloudConnectionSettingRepository.findByProjectId(11L))
                .thenReturn(Optional.of(new ProjectCloudConnectionSetting(11L, 10L)));
        when(cloudConnectionRepository.findByIdAndOwnerUserId(10L, 1L))
                .thenReturn(Optional.of(connection(CloudConnectionStatus.CONNECTED)));
        LocalDateTime updatedAt = LocalDateTime.now();
        // Budget exactly equals the 9.40 estimate — design D3: strict `>` for OVER_BUDGET, so an
        // exact match must stay WITHIN_BUDGET, not tip over.
        when(budgetSettingRepository.findByProjectId(11L)).thenReturn(Optional.of(
                new ProjectBudgetSetting(11L, new BigDecimal("9.40"), "USD", updatedAt, updatedAt)));

        ProjectCostBudgetResult result = service.get(1L, 11L);

        assertThat(result.budgetStatus()).isEqualTo("WITHIN_BUDGET");
        assertThat(result.budgetUsagePercent()).isEqualByComparingTo("100.0");
    }

    @Test
    void get_budgetSetButCostUnavailable_statusIsNotEvaluable() {
        when(infrastructureSettingRepository.findByProjectId(11L)).thenReturn(Optional.empty());
        when(cloudConnectionSettingRepository.findByProjectId(11L)).thenReturn(Optional.empty());
        LocalDateTime updatedAt = LocalDateTime.now();
        when(budgetSettingRepository.findByProjectId(11L)).thenReturn(Optional.of(
                new ProjectBudgetSetting(11L, new BigDecimal("50.00"), "USD", updatedAt, updatedAt)));

        ProjectCostBudgetResult result = service.get(1L, 11L);

        assertThat(result.costAvailable()).isFalse();
        assertThat(result.budgetStatus()).isEqualTo("NOT_EVALUABLE");
        assertThat(result.budgetUsagePercent()).isNull();
        assertThat(result.budget()).isNotNull();
        assertThat(result.budget().monthlyBudgetAmount()).isEqualByComparingTo("50.00");
    }

    // ---- PUT: upsert ----

    @Test
    void update_otherUsersProject_throwsProjectNotFound() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(1L, 11L, new BigDecimal("10.00"), "USD"))
                .isInstanceOf(ProjectNotFoundException.class);
        verify(budgetSettingRepository, never()).save(any());
    }

    @Test
    void update_noExistingBudget_createsNewRow() {
        when(infrastructureSettingRepository.findByProjectId(11L)).thenReturn(Optional.empty());
        when(cloudConnectionSettingRepository.findByProjectId(11L)).thenReturn(Optional.empty());
        when(budgetSettingRepository.findByProjectId(11L)).thenReturn(Optional.empty());
        when(budgetSettingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectCostBudgetResult result = service.update(1L, 11L, new BigDecimal("40.00"), "USD");

        ArgumentCaptor<ProjectBudgetSetting> captor = ArgumentCaptor.forClass(ProjectBudgetSetting.class);
        verify(budgetSettingRepository).save(captor.capture());
        assertThat(captor.getValue().getMonthlyBudgetAmount()).isEqualByComparingTo("40.00");
        assertThat(captor.getValue().getCurrency()).isEqualTo("USD");
        // design D5: budget PUT is allowed even though this project has no infra configured at all.
        assertThat(result.costAvailable()).isFalse();
    }

    @Test
    void update_existingBudget_overwritesAmountAndCurrency() {
        LocalDateTime originalUpdatedAt = LocalDateTime.now().minusDays(1);
        when(infrastructureSettingRepository.findByProjectId(11L)).thenReturn(Optional.empty());
        when(cloudConnectionSettingRepository.findByProjectId(11L)).thenReturn(Optional.empty());
        when(budgetSettingRepository.findByProjectId(11L)).thenReturn(Optional.of(
                new ProjectBudgetSetting(11L, new BigDecimal("20.00"), "USD", originalUpdatedAt, originalUpdatedAt)));
        when(budgetSettingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.update(1L, 11L, new BigDecimal("60.00"), null);

        ArgumentCaptor<ProjectBudgetSetting> captor = ArgumentCaptor.forClass(ProjectBudgetSetting.class);
        verify(budgetSettingRepository).save(captor.capture());
        // null currency defaults to USD (design D4).
        assertThat(captor.getValue().getMonthlyBudgetAmount()).isEqualByComparingTo("60.00");
        assertThat(captor.getValue().getCurrency()).isEqualTo("USD");
    }

    @Test
    void update_unsupportedCurrency_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.update(1L, 11L, new BigDecimal("10.00"), "usd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("지원하지 않는 통화입니다");
        assertThatThrownBy(() -> service.update(1L, 11L, new BigDecimal("10.00"), "KRW"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("지원하지 않는 통화입니다");

        verify(budgetSettingRepository, never()).save(any());
    }

    // ---- DELETE: idempotent ----

    @Test
    void clear_otherUsersProject_throwsProjectNotFound() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.clear(1L, 11L)).isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    void clear_existingBudget_deletesIt() {
        LocalDateTime updatedAt = LocalDateTime.now();
        when(budgetSettingRepository.findByProjectId(11L)).thenReturn(Optional.of(
                new ProjectBudgetSetting(11L, new BigDecimal("10.00"), "USD", updatedAt, updatedAt)));

        service.clear(1L, 11L);

        verify(budgetSettingRepository).deleteByProjectId(11L);
    }

    @Test
    void clear_noExistingBudget_isNoOpAndStillSucceeds() {
        when(budgetSettingRepository.findByProjectId(11L)).thenReturn(Optional.empty());

        service.clear(1L, 11L);

        verify(budgetSettingRepository, never()).deleteByProjectId(11L);
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
