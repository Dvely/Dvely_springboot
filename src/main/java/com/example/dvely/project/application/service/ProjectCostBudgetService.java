package com.example.dvely.project.application.service;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.project.application.result.ProjectCostBudgetResult;
import com.example.dvely.project.domain.exception.ProjectNotFoundException;
import com.example.dvely.project.domain.model.ProjectBudgetSetting;
import com.example.dvely.project.domain.model.ProjectInfrastructureSetting;
import com.example.dvely.project.domain.repository.ProjectBudgetSettingRepository;
import com.example.dvely.project.domain.repository.ProjectCloudConnectionSettingRepository;
import com.example.dvely.project.domain.repository.ProjectInfrastructureSettingRepository;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.service.InfrastructureCostEstimator;
import com.example.dvely.project.domain.value.BudgetStatus;
import com.example.dvely.project.domain.value.CostEstimate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GET/PUT/DELETE for {@code .../settings/cost-budget} (design §4, BI-144~147, BI-167). Reads the
 * infra configuration and cloud connection straight from their repositories rather than through
 * {@code ProjectInfrastructureConfigurationService}/{@code ProjectInfrastructureSettingsService}
 * — the same "no cross-unit service coupling" principle those two units already apply to each
 * other (see {@code ProjectInfrastructureConfigurationService}'s class javadoc), extended here to
 * a third independently-evolving read. No external calls are made anywhere in this class (the
 * price table is a code constant), so both GET and PUT are expected to stay well under
 * design §4's p95 &lt; 50ms budget.
 */
@Service
@RequiredArgsConstructor
public class ProjectCostBudgetService {

    private static final String CURRENCY_USD = "USD";
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final int USAGE_PERCENT_SCALE = 1;

    private final ProjectRepository projectRepository;
    private final ProjectCloudConnectionSettingRepository cloudConnectionSettingRepository;
    private final CloudConnectionRepository cloudConnectionRepository;
    private final ProjectInfrastructureSettingRepository infrastructureSettingRepository;
    private final ProjectBudgetSettingRepository budgetSettingRepository;
    private final InfrastructureCostEstimator costEstimator;

    @Transactional(readOnly = true)
    public ProjectCostBudgetResult get(Long ownerUserId, Long projectId) {
        assertProjectOwner(ownerUserId, projectId);
        return buildResult(ownerUserId, projectId);
    }

    @Transactional
    public ProjectCostBudgetResult update(Long ownerUserId,
                                          Long projectId,
                                          BigDecimal monthlyBudgetAmount,
                                          String currency) {
        // design D5: budget PUT is allowed even when infra isn't configured yet — no
        // costAvailable/CONNECTED guard here, unlike ProjectInfrastructureConfigurationService.update.
        assertProjectOwner(ownerUserId, projectId);
        ProjectBudgetSetting setting = budgetSettingRepository.findByProjectId(projectId)
                .orElseGet(() -> new ProjectBudgetSetting(projectId, monthlyBudgetAmount, currency));
        setting.update(monthlyBudgetAmount, currency);
        budgetSettingRepository.save(setting);
        return buildResult(ownerUserId, projectId);
    }

    @Transactional
    public void clear(Long ownerUserId, Long projectId) {
        assertProjectOwner(ownerUserId, projectId);
        // Guard-then-delete (infra settings' clear() precedent) rather than an unconditional
        // deleteById: keeps DELETE idempotent (design §1.3 — 204 even with nothing to delete)
        // without relying on the adapter to swallow a "row not found" failure.
        budgetSettingRepository.findByProjectId(projectId)
                .ifPresent(setting -> budgetSettingRepository.deleteByProjectId(projectId));
    }

    private ProjectCostBudgetResult buildResult(Long ownerUserId, Long projectId) {
        Optional<ProjectInfrastructureSetting> infrastructureSetting =
                infrastructureSettingRepository.findByProjectId(projectId);
        Optional<CloudConnection> connectedConnection = resolveConnectedConnection(ownerUserId, projectId);
        boolean costAvailable = infrastructureSetting.isPresent() && connectedConnection.isPresent();

        CostEstimate estimate = costAvailable
                ? costEstimator.estimate(connectedConnection.get().getProvider(), infrastructureSetting.get().getConfiguration())
                : null;

        String provider = costAvailable ? connectedConnection.get().getProvider().name() : null;
        BigDecimal estimatedMonthlyCost = costAvailable ? estimate.totalMonthlyCost() : null;
        List<ProjectCostBudgetResult.ResourceCost> resourceCosts = costAvailable
                ? estimate.resourceCosts().stream()
                        .map(item -> new ProjectCostBudgetResult.ResourceCost(
                                item.resourceType().name(), item.description(), item.monthlyCost()))
                        .toList()
                : List.of();
        // Assumptions/priceTableVersion describe the estimator's static price table itself, not a
        // per-project computation, so they stay populated even when costAvailable is false.
        List<String> assumptions = costAvailable ? estimate.assumptions() : InfrastructureCostEstimator.ASSUMPTIONS;
        String priceTableVersion = costAvailable
                ? estimate.priceTableVersion()
                : InfrastructureCostEstimator.PRICE_TABLE_VERSION;

        Optional<ProjectBudgetSetting> budgetSetting = budgetSettingRepository.findByProjectId(projectId);
        ProjectCostBudgetResult.Budget budget = budgetSetting
                .map(b -> new ProjectCostBudgetResult.Budget(b.getMonthlyBudgetAmount(), b.getCurrency(), b.getUpdatedAt()))
                .orElse(null);

        BudgetEvaluation evaluation = evaluateBudget(budgetSetting, estimatedMonthlyCost);

        return new ProjectCostBudgetResult(
                projectId,
                costAvailable,
                provider,
                CURRENCY_USD,
                estimatedMonthlyCost,
                resourceCosts,
                assumptions,
                priceTableVersion,
                budget,
                evaluation.status().name(),
                evaluation.usagePercent()
        );
    }

    // design D3: NO_BUDGET/NOT_EVALUABLE/OVER_BUDGET/WITHIN_BUDGET with a strict `>` for
    // OVER_BUDGET — estimated == budget stays WITHIN_BUDGET. Comparison uses compareTo (never
    // equals), per design D6, since two BigDecimals holding the same numeric value can carry
    // different scales and equals() would then wrongly treat them as different.
    private BudgetEvaluation evaluateBudget(Optional<ProjectBudgetSetting> budgetSetting,
                                            BigDecimal estimatedMonthlyCost) {
        if (budgetSetting.isEmpty()) {
            return new BudgetEvaluation(BudgetStatus.NO_BUDGET, null);
        }
        if (estimatedMonthlyCost == null) {
            return new BudgetEvaluation(BudgetStatus.NOT_EVALUABLE, null);
        }
        BigDecimal budgetAmount = budgetSetting.get().getMonthlyBudgetAmount();
        BudgetStatus status = estimatedMonthlyCost.compareTo(budgetAmount) > 0
                ? BudgetStatus.OVER_BUDGET
                : BudgetStatus.WITHIN_BUDGET;
        BigDecimal usagePercent = estimatedMonthlyCost
                .multiply(ONE_HUNDRED)
                .divide(budgetAmount, USAGE_PERCENT_SCALE, RoundingMode.HALF_UP);
        return new BudgetEvaluation(status, usagePercent);
    }

    private record BudgetEvaluation(BudgetStatus status, BigDecimal usagePercent) {
    }

    // Mirrors ProjectInfrastructureConfigurationService#isConnectedCloudSelected: never throws —
    // any missing link (no selection, connection deleted, not CONNECTED) resolves to
    // Optional.empty() rather than propagating, because GET must always return 200 (design D5).
    private Optional<CloudConnection> resolveConnectedConnection(Long ownerUserId, Long projectId) {
        return cloudConnectionSettingRepository.findByProjectId(projectId)
                .flatMap(setting -> cloudConnectionRepository
                        .findByIdAndOwnerUserId(setting.getCloudConnectionId(), ownerUserId))
                .filter(connection -> connection.getStatus() == CloudConnectionStatus.CONNECTED);
    }

    private void assertProjectOwner(Long ownerUserId, Long projectId) {
        projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, ownerUserId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId, ownerUserId));
    }
}
