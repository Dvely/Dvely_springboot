package com.example.dvely.domainbinding.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cloudflare")
public record CloudflareProperties(
        String apiToken,
        String zoneId,
        String managedDomain,
        String managedTarget,
        String apiBaseUrl,
        Integer ttl,
        Boolean proxied
) {

    private static final String DEFAULT_MANAGED_DOMAIN = "qeploy.com";
    private static final String DEFAULT_API_BASE_URL = "https://api.cloudflare.com/client/v4";

    public String managedDomainOrDefault() {
        if (managedDomain == null || managedDomain.isBlank()) {
            return DEFAULT_MANAGED_DOMAIN;
        }
        return managedDomain.trim().toLowerCase();
    }

    public String apiBaseUrlOrDefault() {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            return DEFAULT_API_BASE_URL;
        }
        return apiBaseUrl.trim();
    }

    public int ttlOrAuto() {
        if (ttl == null || ttl < 1) {
            return 1;
        }
        return ttl;
    }

    public boolean proxiedOrDefault() {
        return proxied == null || proxied;
    }

    public boolean hasManagedTarget() {
        return managedTarget != null && !managedTarget.isBlank();
    }

    public String managedTargetOrNull() {
        return hasManagedTarget() ? managedTarget.trim().toLowerCase() : null;
    }

    public boolean configured() {
        return apiToken != null && !apiToken.isBlank()
                && zoneId != null && !zoneId.isBlank();
    }
}
