package com.example.dvely.project.domain.service;

import com.example.dvely.cloudconnection.domain.value.CloudProvider;
import com.example.dvely.project.domain.value.ComputeTier;
import com.example.dvely.project.domain.value.CostEstimate;
import com.example.dvely.project.domain.value.CostResourceType;
import com.example.dvely.project.domain.value.DeploymentArchitecture;
import com.example.dvely.project.domain.value.InfrastructureConfiguration;
import com.example.dvely.project.domain.value.NetworkAccess;
import com.example.dvely.project.domain.value.StorageType;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Turns a provider-neutral {@link InfrastructureConfiguration} into a monthly USD cost estimate
 * (design D1/D2/§2) using a static price table baked into this class — no network calls, no
 * persistence, deterministic given its two inputs. This is a design choice, not a shortcut: real
 * instance-type mapping and live pricing are EPIC 15's concern (actual provisioning), and by the
 * time that lands the desired-state tiers here may not even map 1:1 to what gets provisioned.
 * Until then, a static table is the only thing that is decidable, testable, and cheap to
 * recompute on every read (design D2 — estimates are never persisted).
 *
 * <p>Adjusting a price is a one-line change in {@link #buildComputeTable()}/
 * {@link #buildStorageTable()}/{@link #buildNetworkTable()} — the lookup/estimate logic below
 * never encodes a number itself, so a pricing review never needs to touch anything but those
 * three methods (and {@link #PRICE_TABLE_VERSION}, which every response echoes so a client can
 * tell which revision of the numbers it's looking at).
 */
@Component
public class InfrastructureCostEstimator {

    /** Bump whenever any constant in the three price tables below changes (design §2). */
    public static final String PRICE_TABLE_VERSION = "2026-07.static.1";

    /**
     * Usage assumptions the price table numbers are built on (design §2) — echoed verbatim in
     * every response so "estimate" never reads as a promise of exact billing.
     */
    public static final List<String> ASSUMPTIONS = List.of(
            "월 730시간 상시 가동을 가정합니다.",
            "스토리지 사용량 50GB를 가정합니다.",
            "아웃바운드 트래픽 100GB(PUBLIC) / 10GB(PRIVATE)를 가정합니다."
    );

    // Flattened (rather than 3-deep nested maps) so a lookup miss fails with one descriptive key
    // instead of a chain of null-checks, and so each price table method reads as a flat list
    // matching design §2's table layout one-for-one.
    private record ComputeKey(CloudProvider provider, DeploymentArchitecture architecture, ComputeTier tier) {
    }

    private record StorageKey(CloudProvider provider, StorageType storageType) {
    }

    private record NetworkKey(CloudProvider provider, NetworkAccess networkAccess) {
    }

    private static final Map<ComputeKey, BigDecimal> COMPUTE_MONTHLY_USD = buildComputeTable();
    private static final Map<StorageKey, BigDecimal> STORAGE_MONTHLY_USD = buildStorageTable();
    private static final Map<NetworkKey, BigDecimal> NETWORK_MONTHLY_USD = buildNetworkTable();

    /**
     * Computes the monthly estimate for one project's desired configuration under one provider.
     * Pure function: same inputs always produce the same {@link CostEstimate}, no state is read
     * or mutated. {@code totalMonthlyCost} is the exact sum of the three resource costs (BigDecimal
     * addition preserves each constant's scale-2 precision, so no rounding step is needed here).
     */
    public CostEstimate estimate(CloudProvider provider, InfrastructureConfiguration configuration) {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(configuration, "configuration must not be null");

        CostEstimate.ResourceCost compute = computeResourceCost(provider, configuration);
        CostEstimate.ResourceCost storage = storageResourceCost(provider, configuration);
        CostEstimate.ResourceCost network = networkResourceCost(provider, configuration);
        BigDecimal total = compute.monthlyCost().add(storage.monthlyCost()).add(network.monthlyCost());

        return new CostEstimate(total, List.of(compute, storage, network), ASSUMPTIONS, PRICE_TABLE_VERSION);
    }

    private CostEstimate.ResourceCost computeResourceCost(CloudProvider provider,
                                                            InfrastructureConfiguration configuration) {
        ComputeKey key = new ComputeKey(provider, configuration.deploymentArchitecture(), configuration.computeTier());
        BigDecimal price = priceFor(COMPUTE_MONTHLY_USD, key, "compute");
        String description = configuration.deploymentArchitecture() + " · " + configuration.computeTier()
                + " (" + provider + ")";
        return new CostEstimate.ResourceCost(CostResourceType.COMPUTE, description, price);
    }

    private CostEstimate.ResourceCost storageResourceCost(CloudProvider provider,
                                                            InfrastructureConfiguration configuration) {
        StorageKey key = new StorageKey(provider, configuration.storageType());
        BigDecimal price = priceFor(STORAGE_MONTHLY_USD, key, "storage");
        String description = configuration.storageType() + " (" + provider + ")";
        return new CostEstimate.ResourceCost(CostResourceType.STORAGE, description, price);
    }

    private CostEstimate.ResourceCost networkResourceCost(CloudProvider provider,
                                                            InfrastructureConfiguration configuration) {
        NetworkKey key = new NetworkKey(provider, configuration.networkAccess());
        BigDecimal price = priceFor(NETWORK_MONTHLY_USD, key, "network");
        String description = configuration.networkAccess() + " (" + provider + ")";
        return new CostEstimate.ResourceCost(CostResourceType.NETWORK, description, price);
    }

    private static <K> BigDecimal priceFor(Map<K, BigDecimal> table, K key, String tableName) {
        BigDecimal price = table.get(key);
        if (price == null) {
            // Every (provider, architecture/storage/network choice) combination that
            // InfrastructureConfiguration.parse can produce must have a price row — reaching here
            // means the project domain's enum surface and this price table have drifted apart,
            // which is a static-data bug to fix in this file, not a client input error.
            throw new IllegalStateException("가격표에 정의되지 않은 " + tableName + " 조합입니다: " + key);
        }
        return price;
    }

    // design §2 table, row per (provider, architecture): MICRO/SMALL/MEDIUM/LARGE monthly USD.
    private static Map<ComputeKey, BigDecimal> buildComputeTable() {
        Map<ComputeKey, BigDecimal> table = new HashMap<>();
        putComputeRow(table, CloudProvider.AWS, DeploymentArchitecture.SERVER, "8.50", "17.00", "34.00", "68.00");
        putComputeRow(table, CloudProvider.GCP, DeploymentArchitecture.SERVER, "7.00", "14.00", "28.00", "56.00");
        putComputeRow(table, CloudProvider.AWS, DeploymentArchitecture.CONTAINER, "9.50", "19.00", "37.50", "75.00");
        putComputeRow(table, CloudProvider.GCP, DeploymentArchitecture.CONTAINER, "7.50", "15.50", "31.00", "61.50");
        // SERVERLESS: "저트래픽 요청 과금 가정 (양사 공통 근사)" — AWS and GCP intentionally share
        // one row in design §2 rather than two near-identical rows.
        putComputeRow(table, CloudProvider.AWS, DeploymentArchitecture.SERVERLESS, "3.00", "6.00", "12.00", "24.00");
        putComputeRow(table, CloudProvider.GCP, DeploymentArchitecture.SERVERLESS, "3.00", "6.00", "12.00", "24.00");
        return Map.copyOf(table);
    }

    private static void putComputeRow(Map<ComputeKey, BigDecimal> table,
                                       CloudProvider provider,
                                       DeploymentArchitecture architecture,
                                       String micro, String small, String medium, String large) {
        table.put(new ComputeKey(provider, architecture, ComputeTier.MICRO), new BigDecimal(micro));
        table.put(new ComputeKey(provider, architecture, ComputeTier.SMALL), new BigDecimal(small));
        table.put(new ComputeKey(provider, architecture, ComputeTier.MEDIUM), new BigDecimal(medium));
        table.put(new ComputeKey(provider, architecture, ComputeTier.LARGE), new BigDecimal(large));
    }

    private static Map<StorageKey, BigDecimal> buildStorageTable() {
        return Map.of(
                new StorageKey(CloudProvider.AWS, StorageType.NONE), new BigDecimal("0.00"),
                new StorageKey(CloudProvider.AWS, StorageType.OBJECT_STORAGE), new BigDecimal("1.15"),
                new StorageKey(CloudProvider.GCP, StorageType.NONE), new BigDecimal("0.00"),
                new StorageKey(CloudProvider.GCP, StorageType.OBJECT_STORAGE), new BigDecimal("1.00")
        );
    }

    private static Map<NetworkKey, BigDecimal> buildNetworkTable() {
        return Map.of(
                new NetworkKey(CloudProvider.AWS, NetworkAccess.PUBLIC), new BigDecimal("9.00"),
                new NetworkKey(CloudProvider.AWS, NetworkAccess.PRIVATE), new BigDecimal("0.90"),
                new NetworkKey(CloudProvider.GCP, NetworkAccess.PUBLIC), new BigDecimal("12.00"),
                new NetworkKey(CloudProvider.GCP, NetworkAccess.PRIVATE), new BigDecimal("1.20")
        );
    }
}
