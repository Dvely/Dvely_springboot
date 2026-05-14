package com.example.dvely.domainbinding.infrastructure.external;

import com.example.dvely.domainbinding.application.port.out.CloudflareDnsPort;
import com.example.dvely.domainbinding.infrastructure.config.CloudflareProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class CloudflareDnsClient implements CloudflareDnsPort {

    private final CloudflareProperties properties;

    @Override
    public String createCnameRecord(String hostname, String target) {
        ensureConfigured();
        RestClient restClient = restClient();
        CloudflareRecordResponse response = restClient.post()
                .uri("/zones/{zoneId}/dns_records", properties.zoneId())
                .body(Map.of(
                        "type", "CNAME",
                        "name", hostname,
                        "content", target,
                        "ttl", properties.ttlOrAuto(),
                        "proxied", properties.proxiedOrDefault()
                ))
                .retrieve()
                .body(CloudflareRecordResponse.class);
        if (response == null || !response.success() || response.result() == null) {
            throw new IllegalStateException("Cloudflare DNS 레코드 생성 실패: " + errorMessage(response));
        }
        return response.result().id();
    }

    @Override
    public boolean recordExists(String hostname, String recordId) {
        ensureConfigured();
        return findRecord(hostname).stream()
                .anyMatch(record -> record.name().equalsIgnoreCase(hostname)
                        && (recordId == null || recordId.isBlank() || record.id().equals(recordId)));
    }

    @Override
    public void deleteRecord(String hostname, String recordId) {
        ensureConfigured();
        String targetRecordId = recordId;
        if (targetRecordId == null || targetRecordId.isBlank()) {
            targetRecordId = findRecord(hostname).stream()
                    .findFirst()
                    .map(CloudflareDnsRecord::id)
                    .orElse(null);
        }
        if (targetRecordId == null || targetRecordId.isBlank()) {
            return;
        }
        restClient().delete()
                .uri("/zones/{zoneId}/dns_records/{recordId}", properties.zoneId(), targetRecordId)
                .retrieve()
                .toBodilessEntity();
    }

    private List<CloudflareDnsRecord> findRecord(String hostname) {
        CloudflareRecordListResponse response = restClient().get()
                .uri(uriBuilder -> uriBuilder
                        .path("/zones/{zoneId}/dns_records")
                        .queryParam("name", hostname)
                        .build(properties.zoneId()))
                .retrieve()
                .body(CloudflareRecordListResponse.class);
        if (response == null || !response.success()) {
            throw new IllegalStateException("Cloudflare DNS 레코드 조회 실패: " + errorMessage(response));
        }
        return response.result() == null ? List.of() : response.result();
    }

    private RestClient restClient() {
        return RestClient.builder()
                .baseUrl(properties.apiBaseUrlOrDefault())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiToken())
                .build();
    }

    private void ensureConfigured() {
        if (!properties.configured()) {
            throw new IllegalStateException(
                    "Cloudflare 설정이 없습니다. CLOUDFLARE_API_TOKEN과 CLOUDFLARE_ZONE_ID를 설정해 주세요.");
        }
    }

    private String errorMessage(CloudflareRecordResponse response) {
        if (response == null || response.errors() == null || response.errors().isEmpty()) {
            return "unknown";
        }
        return response.errors().stream()
                .map(error -> error.code() + " " + error.message())
                .toList()
                .toString();
    }

    private String errorMessage(CloudflareRecordListResponse response) {
        if (response == null || response.errors() == null || response.errors().isEmpty()) {
            return "unknown";
        }
        return response.errors().stream()
                .map(error -> error.code() + " " + error.message())
                .toList()
                .toString();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CloudflareRecordResponse(
            boolean success,
            CloudflareDnsRecord result,
            List<CloudflareError> errors
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CloudflareRecordListResponse(
            boolean success,
            List<CloudflareDnsRecord> result,
            List<CloudflareError> errors
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CloudflareDnsRecord(
            String id,
            String name,
            String type,
            String content
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CloudflareError(
            int code,
            String message
    ) {
    }
}
