package com.example.dvely.project.domain.value;

import java.util.Objects;

/**
 * The four provider-neutral choices (design D2) that together describe a project's desired
 * infrastructure shape. This record is always moved as a unit — a snapshot of it is what gets
 * stored on every history row (design D4) and what gets compared to detect a no-op PUT
 * (design D10, via generated {@code equals}).
 */
public record InfrastructureConfiguration(
        DeploymentArchitecture deploymentArchitecture,
        ComputeTier computeTier,
        StorageType storageType,
        NetworkAccess networkAccess
) {

    public InfrastructureConfiguration {
        Objects.requireNonNull(deploymentArchitecture, "deploymentArchitecture must not be null");
        Objects.requireNonNull(computeTier, "computeTier must not be null");
        Objects.requireNonNull(storageType, "storageType must not be null");
        Objects.requireNonNull(networkAccess, "networkAccess must not be null");
    }

    /**
     * Parses the four raw request strings into a validated configuration. Each field is
     * converted independently (rather than one try-catch around all four) so the error message
     * names exactly which field was invalid — {@code Enum.valueOf}'s own message only echoes
     * the bad value, not the field it came from, which is useless once there are four enum
     * fields on one request (same reasoning as U3's {@code EnvironmentVariableQueryService#parseScope}).
     */
    public static InfrastructureConfiguration parse(String deploymentArchitecture,
                                                     String computeTier,
                                                     String storageType,
                                                     String networkAccess) {
        return new InfrastructureConfiguration(
                parseField(DeploymentArchitecture.class, deploymentArchitecture, "deploymentArchitecture"),
                parseField(ComputeTier.class, computeTier, "computeTier"),
                parseField(StorageType.class, storageType, "storageType"),
                parseField(NetworkAccess.class, networkAccess, "networkAccess")
        );
    }

    private static <E extends Enum<E>> E parseField(Class<E> enumType, String raw, String fieldName) {
        try {
            return Enum.valueOf(enumType, raw);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException("지원하지 않는 " + fieldName + "입니다: " + raw);
        }
    }

    /** Human-readable summary used as the Approval's {@code summary} column (design §3.2). */
    public String summaryText() {
        return "아키텍처=" + deploymentArchitecture
                + ", 컴퓨팅=" + computeTier
                + ", 스토리지=" + storageType
                + ", 네트워크=" + networkAccess;
    }
}
