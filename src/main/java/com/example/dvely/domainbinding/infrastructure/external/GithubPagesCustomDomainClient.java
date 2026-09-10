package com.example.dvely.domainbinding.infrastructure.external;

import com.example.dvely.domainbinding.application.port.out.HostingCustomDomainPort;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
@Component
public class GithubPagesCustomDomainClient implements HostingCustomDomainPort {

    private static final String API_BASE = "https://api.github.com";

    private final RestClient githubRestClient;

    public GithubPagesCustomDomainClient(@Qualifier("githubRestClient") RestClient githubRestClient) {
        this.githubRestClient = githubRestClient;
    }

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
    public SiteStatus getSiteStatus(String userToken, String repositoryFullName) {
        String[] parts = splitRepo(repositoryFullName);
        try {
            PagesResponse response = restClient(userToken)
                    .get()
                    .uri(API_BASE + "/repos/{owner}/{repo}/pages", parts[0], parts[1])
                    .retrieve()
                    .body(PagesResponse.class);
            if (response == null) {
                return new SiteStatus(null, false, null, null);
            }
            HttpsCertificate certificate = response.httpsCertificate();
            return new SiteStatus(
                    response.cname(),
                    response.httpsEnforced(),
                    certificate == null ? null : certificate.state(),
                    certificate == null ? null : parseDate(certificate.expiresAt())
            );
        } catch (RestClientResponseException e) {
            throw new IllegalStateException(githubError("GitHub Pages custom domain 조회 실패", e), e);
        }
    }

    @Override
    public void setHttpsEnforced(String userToken, String repositoryFullName, boolean httpsEnforced) {
        String[] parts = splitRepo(repositoryFullName);
        try {
            restClient(userToken)
                    .put()
                    .uri(API_BASE + "/repos/{owner}/{repo}/pages", parts[0], parts[1])
                    .body(Map.of("https_enforced", httpsEnforced))
                    .retrieve()
                    .toBodilessEntity();
            log.info("GitHub Pages HTTPS 강제 설정 완료: repo={}, enabled={}", repositoryFullName, httpsEnforced);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException(githubError("GitHub Pages HTTPS 강제 설정 실패", e), e);
        }
    }

    @Override
    public void removeCustomDomainIfMatches(String userToken, String repositoryFullName, String hostname) {
        if (!getSiteStatus(userToken, repositoryFullName).hasCustomDomain(hostname)) {
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

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            log.warn("GitHub Pages 인증서 만료일 파싱 실패: value={}", value);
            return null;
        }
    }

    private String[] splitRepo(String repositoryFullName) {
        String[] parts = repositoryFullName.split("/", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("올바르지 않은 저장소 형식입니다: " + repositoryFullName);
        }
        return parts;
    }

    /**
     * 사용자 토큰마다 파생시킨다.
     *
     * <p>{@code mutate()} 는 원본의 요청 팩토리를 <b>그대로 물려준다</b>(참조 복사). 그래서
     * 커넥션 풀·셀렉터 스레드와 상한은 공용 빈 하나를 계속 공유하고, 인스턴스에 고정되는 것은
     * 이 호출의 토큰뿐이다 — 토큰을 공용 빈의 기본 헤더로 박으면 다른 사용자의 요청에 남의
     * 토큰이 실린다.</p>
     */
    private RestClient restClient(String userToken) {
        return githubRestClient.mutate()
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
            @JsonProperty("cname") String cname,
            @JsonProperty("https_enforced") boolean httpsEnforced,
            @JsonProperty("https_certificate") HttpsCertificate httpsCertificate
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record HttpsCertificate(
            @JsonProperty("state") String state,
            @JsonProperty("expires_at") String expiresAt
    ) {
    }
}
