package com.example.dvely.domainbinding.application.port.out;

public interface HostingCustomDomainPort {

    void setCustomDomain(String userToken, String repositoryFullName, String hostname);

    boolean isCustomDomainConfigured(String userToken, String repositoryFullName, String hostname);

    void removeCustomDomainIfMatches(String userToken, String repositoryFullName, String hostname);
}
