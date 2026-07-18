package com.example.dvely.project.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dvely.cloudconnection.domain.value.CloudProvider;
import com.example.dvely.project.domain.value.ComputeTier;
import com.example.dvely.project.domain.value.CostEstimate;
import com.example.dvely.project.domain.value.CostResourceType;
import com.example.dvely.project.domain.value.DeploymentArchitecture;
import com.example.dvely.project.domain.value.InfrastructureConfiguration;
import com.example.dvely.project.domain.value.NetworkAccess;
import com.example.dvely.project.domain.value.StorageType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Design §2's price table, verified against representative cells rather than every one of the
 * 3 architectures x 4 tiers x 2 providers x 2 storage x 2 network combinations — the exhaustive
 * cross-product would just re-type the design table without adding assurance beyond what the
 * sum-equals-items invariant and boundary-tier cases already prove.
 */
class InfrastructureCostEstimatorTest {

    private final InfrastructureCostEstimator estimator = new InfrastructureCostEstimator();

    @Test
    void awsServerSmall_matchesDesignPriceTableExactly() {
        InfrastructureConfiguration configuration = new InfrastructureConfiguration(
                DeploymentArchitecture.SERVER, ComputeTier.SMALL, StorageType.OBJECT_STORAGE, NetworkAccess.PUBLIC
        );

        CostEstimate estimate = estimator.estimate(CloudProvider.AWS, configuration);

        // 17.00 (compute) + 1.15 (storage) + 9.00 (network) = 27.15
        assertThat(estimate.totalMonthlyCost()).isEqualByComparingTo("27.15");
        assertThat(estimate.priceTableVersion()).isEqualTo("2026-07.static.1");
        assertThat(estimate.assumptions()).hasSize(3);
    }

    @Test
    void gcpContainerLarge_matchesDesignPriceTableExactly() {
        InfrastructureConfiguration configuration = new InfrastructureConfiguration(
                DeploymentArchitecture.CONTAINER, ComputeTier.LARGE, StorageType.OBJECT_STORAGE, NetworkAccess.PUBLIC
        );

        CostEstimate estimate = estimator.estimate(CloudProvider.GCP, configuration);

        // 61.50 (compute) + 1.00 (storage) + 12.00 (network) = 74.50
        assertThat(estimate.totalMonthlyCost()).isEqualByComparingTo("74.50");
    }

    @Test
    void serverless_isSameApproximationAcrossAwsAndGcp() {
        InfrastructureConfiguration configuration = new InfrastructureConfiguration(
                DeploymentArchitecture.SERVERLESS, ComputeTier.MICRO, StorageType.NONE, NetworkAccess.PRIVATE
        );

        CostEstimate aws = estimator.estimate(CloudProvider.AWS, configuration);
        CostEstimate gcp = estimator.estimate(CloudProvider.GCP, configuration);

        BigDecimal awsCompute = aws.resourceCosts().stream()
                .filter(item -> item.resourceType() == CostResourceType.COMPUTE)
                .findFirst().orElseThrow().monthlyCost();
        BigDecimal gcpCompute = gcp.resourceCosts().stream()
                .filter(item -> item.resourceType() == CostResourceType.COMPUTE)
                .findFirst().orElseThrow().monthlyCost();

        assertThat(awsCompute).isEqualByComparingTo("3.00");
        assertThat(gcpCompute).isEqualByComparingTo("3.00");
    }

    @Test
    void noneStorageAndPrivateNetwork_minimalConfiguration_stillProducesThreeRows() {
        InfrastructureConfiguration configuration = new InfrastructureConfiguration(
                DeploymentArchitecture.SERVER, ComputeTier.MICRO, StorageType.NONE, NetworkAccess.PRIVATE
        );

        CostEstimate estimate = estimator.estimate(CloudProvider.AWS, configuration);

        assertThat(estimate.resourceCosts()).hasSize(3);
        assertThat(estimate.resourceCosts())
                .extracting(CostEstimate.ResourceCost::resourceType)
                .containsExactlyInAnyOrder(CostResourceType.COMPUTE, CostResourceType.STORAGE, CostResourceType.NETWORK);
        BigDecimal storageCost = estimate.resourceCosts().stream()
                .filter(item -> item.resourceType() == CostResourceType.STORAGE)
                .findFirst().orElseThrow().monthlyCost();
        // NONE storage is a real, zero-cost row (design §2) rather than an omitted one — a
        // consumer summing resourceCosts must never need to special-case a missing entry.
        assertThat(storageCost).isEqualByComparingTo("0.00");
        // 8.50 (compute) + 0.00 (storage) + 0.90 (network) = 9.40
        assertThat(estimate.totalMonthlyCost()).isEqualByComparingTo("9.40");
    }

    @Test
    void totalMonthlyCost_alwaysEqualsSumOfResourceCosts_acrossEveryTierAndArchitecture() {
        for (CloudProvider provider : CloudProvider.values()) {
            for (DeploymentArchitecture architecture : DeploymentArchitecture.values()) {
                for (ComputeTier tier : ComputeTier.values()) {
                    for (StorageType storageType : StorageType.values()) {
                        for (NetworkAccess networkAccess : NetworkAccess.values()) {
                            InfrastructureConfiguration configuration =
                                    new InfrastructureConfiguration(architecture, tier, storageType, networkAccess);
                            CostEstimate estimate = estimator.estimate(provider, configuration);

                            BigDecimal sumOfItems = estimate.resourceCosts().stream()
                                    .map(CostEstimate.ResourceCost::monthlyCost)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add);

                            assertThat(estimate.totalMonthlyCost())
                                    .as("total for %s/%s/%s/%s/%s", provider, architecture, tier, storageType, networkAccess)
                                    .isEqualByComparingTo(sumOfItems);
                            // design D6: every stored/returned amount is BigDecimal scale 2.
                            assertThat(estimate.totalMonthlyCost().scale()).isEqualTo(2);
                            estimate.resourceCosts().forEach(item ->
                                    assertThat(item.monthlyCost().scale()).isEqualTo(2));
                        }
                    }
                }
            }
        }
    }

    @Test
    void computeDescription_matchesDesignExampleFormat() {
        InfrastructureConfiguration configuration = new InfrastructureConfiguration(
                DeploymentArchitecture.SERVER, ComputeTier.SMALL, StorageType.NONE, NetworkAccess.PUBLIC
        );

        CostEstimate estimate = estimator.estimate(CloudProvider.AWS, configuration);

        String computeDescription = estimate.resourceCosts().stream()
                .filter(item -> item.resourceType() == CostResourceType.COMPUTE)
                .findFirst().orElseThrow().description();
        assertThat(computeDescription).isEqualTo("SERVER · SMALL (AWS)");
    }

    @Test
    void estimate_rejectsNullProviderAndConfiguration() {
        InfrastructureConfiguration configuration = new InfrastructureConfiguration(
                DeploymentArchitecture.SERVER, ComputeTier.SMALL, StorageType.NONE, NetworkAccess.PUBLIC
        );

        assertThatThrownBy(() -> estimator.estimate(null, configuration))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> estimator.estimate(CloudProvider.AWS, null))
                .isInstanceOf(NullPointerException.class);
    }
}
