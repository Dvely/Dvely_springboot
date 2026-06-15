package com.example.dvely.domainbinding.application.port.out;

import java.time.LocalDate;

public interface HostingCustomDomainPort {

    void setCustomDomain(String userToken, String repositoryFullName, String hostname);

    SiteStatus getSiteStatus(String userToken, String repositoryFullName);

    void setHttpsEnforced(String userToken, String repositoryFullName, boolean httpsEnforced);

    void removeCustomDomainIfMatches(String userToken, String repositoryFullName, String hostname);

    record SiteStatus(
            String customDomain,
            boolean httpsEnforced,
            String certificateState,
            LocalDate certificateExpiresAt
    ) {
        public boolean hasCustomDomain(String hostname) {
            return hostname != null && hostname.equalsIgnoreCase(customDomain == null ? "" : customDomain);
        }
    }
}
