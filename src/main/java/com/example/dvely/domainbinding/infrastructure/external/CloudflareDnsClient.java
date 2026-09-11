package com.example.dvely.domainbinding.infrastructure.external;

import com.example.dvely.domainbinding.application.port.out.CloudflareDnsPort;
import com.example.dvely.domainbinding.infrastructure.config.CloudflareProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class CloudflareDnsClient implements CloudflareDnsPort {

    private final CloudflareProperties properties;
    private final RestClient restClient;

    /**
     * baseUrl 만 미리 붙여 1 회 조립한다.
     *
     * <p>API 토큰은 기본 헤더로 박지 않는다. 이 토큰은 서버 설정값이라 사용자마다 다르지는
     * 않지만, 조립 시점에 {@code properties.apiToken()} 이 아직 비어 있으면 {@code "Bearer null"}
     * 이 인스턴스에 굳어 버린다 — 설정이 채워져도 그 클라이언트는 계속 잘못된 값을 보낸다.
     * 호출마다 싣는 편이 {@code ensureConfigured()} 의 검사와 어긋나지 않는다.</p>
     */
    public CloudflareDnsClient(CloudflareProperties properties,
                               @Qualifier("cloudflareRestClient") RestClient cloudflareRestClient) {
        this.properties = properties;
        this.restClient = cloudflareRestClient.mutate()
                .baseUrl(properties.apiBaseUrlOrDefault())
                .build();
    }

    @Override
    public String createCnameRecord(String hostname, String target) {
        return createCnameRecord(hostname, target, properties.proxiedOrDefault());
    }

    @Override
    public String createCnameRecord(String hostname, String target, boolean proxied) {
        ensureConfigured();
        CloudflareRecordResponse response = restClient.post()
                .uri("/zones/{zoneId}/dns_records", properties.zoneId())
                .header(HttpHeaders.AUTHORIZATION, authorization())
                .body(Map.of(
                        "type", "CNAME",
                        "name", hostname,
                        "content", target,
                        "ttl", properties.ttlOrAuto(),
                        "proxied", proxied
                ))
                .retrieve()
                .body(CloudflareRecordResponse.class);
        if (response == null || !response.success() || response.result() == null) {
            throw new IllegalStateException("Cloudflare DNS 레코드 생성 실패: " + errorMessage(response));
        }
        return response.result().id();
    }

    @Override
    public String createARecord(String hostname, String ipAddress, boolean proxied) {
        ensureConfigured();
        CloudflareRecordResponse response = restClient.post()
                .uri("/zones/{zoneId}/dns_records", properties.zoneId())
                .header(HttpHeaders.AUTHORIZATION, authorization())
                .body(Map.of(
                        "type", "A",
                        "name", hostname,
                        "content", ipAddress,
                        "ttl", properties.ttlOrAuto(),
                        "proxied", proxied
                ))
                .retrieve()
                .body(CloudflareRecordResponse.class);
        if (response == null || !response.success() || response.result() == null) {
            throw new IllegalStateException("Cloudflare A 레코드 생성 실패: " + errorMessage(response));
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
        restClient.delete()
                .uri("/zones/{zoneId}/dns_records/{recordId}", properties.zoneId(), targetRecordId)
                .header(HttpHeaders.AUTHORIZATION, authorization())
                .retrieve()
                .toBodilessEntity();
    }

    private List<CloudflareDnsRecord> findRecord(String hostname) {
        CloudflareRecordListResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/zones/{zoneId}/dns_records")
                        .queryParam("name", hostname)
                        .build(properties.zoneId()))
                .header(HttpHeaders.AUTHORIZATION, authorization())
                .retrieve()
                .body(CloudflareRecordListResponse.class);
        if (response == null || !response.success()) {
            throw new IllegalStateException("Cloudflare DNS 레코드 조회 실패: " + errorMessage(response));
        }
        return response.result() == null ? List.of() : response.result();
    }


    private String authorization() {
        return "Bearer " + properties.apiToken();
    }

    private void ensureConfigured() {
        if (!properties.configured()) {
            throw new IllegalStateException(
                    "Cloudflare 설정이 없습니다. cloudflare.api-token과 cloudflare.zone-id를 yml에 설정해 주세요.");
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
