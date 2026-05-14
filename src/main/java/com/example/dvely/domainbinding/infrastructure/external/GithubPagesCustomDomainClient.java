package com.example.dvely.domainbinding.infrastructure.external;

import com.example.dvely.domainbinding.application.port.out.HostingCustomDomainPort;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
@Component
public class GithubPagesCustomDomainClient implements HostingCustomDomainPort {

    private static final String API_BASE = "https://api.github.com";

    @Override
    public void setCustomDomain(String userToken, String repositoryFullName, String hostname) {
        String[] parts = splitRepo(repositoryFullName);
        try {
            restClient(userToken)
                    .put()
                    .uri(API_BASE + "/repos/{owner}/{repo}/pages", parts[0], parts[1])
                    .body(Map.of("cname", hostname))
                    .retrieve()
                    .toBodilessEntity();
            log.info("GitHub Pages custom domain 설정 완료: repo={}, cname={}", repositoryFullName, hostname);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException(githubError("GitHub Pages custom domain 설정 실패: " + hostname, e), e);
        }
    }

    @Override
    public boolean isCustomDomainConfigured(String userToken, String repositoryFullName, String hostname) {
        String current = getCurrentCustomDomain(userToken, repositoryFullName);
        return hostname.equalsIgnoreCase(current == null ? "" : current);
    }

    @Override
    public void removeCustomDomainIfMatches(String userToken, String repositoryFullName, String hostname) {
        if (!isCustomDomainConfigured(userToken, repositoryFullName, hostname)) {
            return;
        }

        String[] parts = splitRepo(repositoryFullName);
        Map<String, Object> body = new HashMap<>();
        body.put("cname", null);

        try {
            restClient(userToken)
                    .put()
                    .uri(API_BASE + "/repos/{owner}/{repo}/pages", parts[0], parts[1])
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("GitHub Pages custom domain 제거 완료: repo={}, cname={}", repositoryFullName, hostname);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException(githubError("GitHub Pages custom domain 제거 실패", e), e);
        }
    }

    private String getCurrentCustomDomain(String userToken, String repositoryFullName) {
        String[] parts = splitRepo(repositoryFullName);
        try {
            PagesResponse response = restClient(userToken)
                    .get()
                    .uri(API_BASE + "/repos/{owner}/{repo}/pages", parts[0], parts[1])
                    .retrieve()
                    .body(PagesResponse.class);
            return response == null ? null : response.cname();
        } catch (RestClientResponseException e) {
            throw new IllegalStateException(githubError("GitHub Pages custom domain 조회 실패", e), e);
        }
    }

    private String[] splitRepo(String repositoryFullName) {
        String[] parts = repositoryFullName.split("/", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("올바르지 않은 저장소 형식입니다: " + repositoryFullName);
        }
        return parts;
    }

    private RestClient restClient(String userToken) {
        return RestClient.builder()
                .defaultHeader("Authorization", "Bearer " + userToken)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    private String githubError(String prefix, RestClientResponseException e) {
        return prefix + " (status=" + e.getStatusCode().value() + ", body=" + e.getResponseBodyAsString() + ")";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PagesResponse(
            @JsonProperty("cname") String cname
    ) {
    }
}
