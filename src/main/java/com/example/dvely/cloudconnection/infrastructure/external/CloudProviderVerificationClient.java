package com.example.dvely.cloudconnection.infrastructure.external;

import com.example.dvely.cloudconnection.application.port.out.CloudConnectionVerificationPort;
import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.value.AwsCredentialType;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.cloudconnection.domain.value.GcpCredentialType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;
import software.amazon.awssdk.services.sts.model.StsException;

@Component
public class CloudProviderVerificationClient implements CloudConnectionVerificationPort {

    private static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient cloudVerificationHttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public VerificationResult verify(CloudConnection cloudConnection) {
        return switch (cloudConnection.getProvider()) {
            case AWS -> verifyAws(cloudConnection);
            case GCP -> verifyGcp(cloudConnection);
        };
    }

    private VerificationResult verifyAws(CloudConnection cloudConnection) {
        try {
            Region region = Region.of(cloudConnection.getRegion());
            AwsCredentialType credentialType = AwsCredentialType.from(cloudConnection.getAwsCredentialType());
            if (credentialType == AwsCredentialType.ACCESS_KEY) {
                return verifyAwsIdentity(
                        region,
                        StaticCredentialsProvider.create(toAwsCredentials(cloudConnection)),
                        cloudConnection.getAccountId()
                );
            }

            try (DefaultCredentialsProvider sourceCredentials = DefaultCredentialsProvider.builder().build();
                 StsClient sourceClient = stsClient(region, sourceCredentials)) {
                AssumeRoleResponse assumed = sourceClient.assumeRole(AssumeRoleRequest.builder()
                        .roleArn(cloudConnection.getRoleArn())
                        .roleSessionName("qeploy-verification-" + cloudConnection.getId())
                        .durationSeconds(900)
                        .build());
                Credentials credentials = assumed.credentials();
                AwsSessionCredentials assumedCredentials = AwsSessionCredentials.create(
                        credentials.accessKeyId(),
                        credentials.secretAccessKey(),
                        credentials.sessionToken()
                );
                return verifyAwsIdentity(
                        region,
                        StaticCredentialsProvider.create(assumedCredentials),
                        cloudConnection.getAccountId()
                );
            }
        } catch (StsException exception) {
            return classifyAwsServiceFailure(exception);
        } catch (SdkClientException exception) {
            String message = exception.getMessage() == null ? "" : exception.getMessage();
            if (message.contains("Unable to load credentials")) {
                return new VerificationResult(
                        CloudConnectionStatus.INVALID_CREDENTIAL,
                        "AWS Role 검증에 사용할 Qeploy 실행 환경의 source credential을 찾을 수 없습니다."
                );
            }
            return new VerificationResult(
                    CloudConnectionStatus.UNKNOWN_ERROR,
                    "AWS STS에 연결하지 못했습니다. 잠시 후 다시 확인해 주세요."
            );
        } catch (RuntimeException exception) {
            return new VerificationResult(
                    CloudConnectionStatus.UNKNOWN_ERROR,
                    "AWS 권한 확인 중 예상하지 못한 오류가 발생했습니다."
            );
        }
    }

    private VerificationResult verifyAwsIdentity(Region region,
                                                 AwsCredentialsProvider credentialsProvider,
                                                 String expectedAccountId) {
        try (StsClient client = stsClient(region, credentialsProvider)) {
            GetCallerIdentityResponse identity = client.getCallerIdentity();
            if (expectedAccountId != null && !expectedAccountId.equals(identity.account())) {
                return new VerificationResult(
                        CloudConnectionStatus.INVALID_CREDENTIAL,
                        "AWS credential의 계정과 등록한 accountId가 일치하지 않습니다."
                );
            }
            return new VerificationResult(
                    CloudConnectionStatus.CONNECTED,
                    "AWS STS GetCallerIdentity 호출로 실제 계정 접근 권한을 확인했습니다."
            );
        }
    }

    private StsClient stsClient(Region region, AwsCredentialsProvider credentialsProvider) {
        return StsClient.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(configuration -> configuration.apiCallTimeout(REQUEST_TIMEOUT))
                .build();
    }

    private AwsCredentials toAwsCredentials(CloudConnection cloudConnection) {
        if (cloudConnection.getSessionToken() != null) {
            return AwsSessionCredentials.create(
                    cloudConnection.getAccessKeyId(),
                    cloudConnection.getSecretAccessKey(),
                    cloudConnection.getSessionToken()
            );
        }
        return AwsBasicCredentials.create(
                cloudConnection.getAccessKeyId(),
                cloudConnection.getSecretAccessKey()
        );
    }

    private VerificationResult classifyAwsServiceFailure(StsException exception) {
        String errorCode = exception.awsErrorDetails() == null
                ? ""
                : exception.awsErrorDetails().errorCode();
        if (List.of(
                "InvalidClientTokenId",
                "SignatureDoesNotMatch",
                "ExpiredToken",
                "InvalidAccessKeyId"
        ).contains(errorCode)) {
            return new VerificationResult(
                    CloudConnectionStatus.INVALID_CREDENTIAL,
                    "AWS credential이 올바르지 않거나 만료되었습니다."
            );
        }
        if ("AccessDenied".equals(errorCode) || exception.statusCode() == 403) {
            return new VerificationResult(
                    CloudConnectionStatus.PERMISSION_MISSING,
                    "AWS STS 호출 또는 AssumeRole 권한이 없습니다."
            );
        }
        return new VerificationResult(
                CloudConnectionStatus.UNKNOWN_ERROR,
                "AWS STS가 권한 확인 요청을 처리하지 못했습니다."
        );
    }

    private VerificationResult verifyGcp(CloudConnection cloudConnection) {
        String accessToken;
        try {
            accessToken = resolveGcpAccessToken(cloudConnection);
        } catch (GcpVerificationException exception) {
            return new VerificationResult(exception.status(), exception.getMessage());
        } catch (IOException exception) {
            return new VerificationResult(
                    CloudConnectionStatus.INVALID_CREDENTIAL,
                    "GCP credential로 access token을 발급할 수 없습니다."
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new VerificationResult(
                    CloudConnectionStatus.UNKNOWN_ERROR,
                    "GCP access token 요청이 중단되었습니다."
            );
        } catch (RuntimeException exception) {
            return new VerificationResult(
                    CloudConnectionStatus.UNKNOWN_ERROR,
                    "GCP access token 확인 중 예상하지 못한 오류가 발생했습니다."
            );
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(
                            "https://cloudresourcemanager.googleapis.com/v1/projects/"
                                    + encodePathSegment(cloudConnection.getGcpProjectId())
                    ))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> response = cloudVerificationHttpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() == 200) {
                return new VerificationResult(
                        CloudConnectionStatus.CONNECTED,
                        "GCP Resource Manager projects.get 호출로 실제 프로젝트 조회 권한을 확인했습니다."
                );
            }
            if (response.statusCode() == 401) {
                return new VerificationResult(
                        CloudConnectionStatus.INVALID_CREDENTIAL,
                        "GCP credential이 올바르지 않거나 만료되었습니다."
                );
            }
            if (response.statusCode() == 403) {
                return new VerificationResult(
                        CloudConnectionStatus.PERMISSION_MISSING,
                        "GCP 프로젝트 조회 권한(resourcemanager.projects.get)이 없습니다."
                );
            }
            if (response.statusCode() == 404) {
                return new VerificationResult(
                        CloudConnectionStatus.INVALID_CREDENTIAL,
                        "등록한 GCP 프로젝트를 찾을 수 없거나 credential의 프로젝트와 일치하지 않습니다."
                );
            }
            return new VerificationResult(
                    CloudConnectionStatus.UNKNOWN_ERROR,
                    "GCP Resource Manager가 권한 확인 요청을 처리하지 못했습니다."
            );
        } catch (IOException exception) {
            return new VerificationResult(
                    CloudConnectionStatus.UNKNOWN_ERROR,
                    "GCP Resource Manager에 연결하지 못했습니다. 잠시 후 다시 확인해 주세요."
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new VerificationResult(
                    CloudConnectionStatus.UNKNOWN_ERROR,
                    "GCP 권한 확인 요청이 중단되었습니다."
            );
        } catch (RuntimeException exception) {
            return new VerificationResult(
                    CloudConnectionStatus.UNKNOWN_ERROR,
                    "GCP 권한 확인 중 예상하지 못한 오류가 발생했습니다."
            );
        }
    }

    private String resolveGcpAccessToken(CloudConnection cloudConnection)
            throws IOException, InterruptedException {
        GcpCredentialType credentialType = GcpCredentialType.from(cloudConnection.getGcpCredentialType());
        if (credentialType == GcpCredentialType.SERVICE_ACCOUNT_KEY) {
            GoogleCredentials credentials = ServiceAccountCredentials.fromStream(new ByteArrayInputStream(
                            cloudConnection.getServiceAccountKeyJson().getBytes(StandardCharsets.UTF_8)
                    ))
                    .createScoped(CLOUD_PLATFORM_SCOPE);
            return credentials.refreshAccessToken().getTokenValue();
        }

        GoogleCredentials sourceCredentials = GoogleCredentials.getApplicationDefault()
                .createScoped(CLOUD_PLATFORM_SCOPE);
        AccessToken sourceToken = sourceCredentials.refreshAccessToken();
        String serviceAccount = encodePathSegment(cloudConnection.getServiceAccountEmail());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(
                        "https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/"
                                + serviceAccount
                                + ":generateAccessToken"
                ))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + sourceToken.getTokenValue())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"scope\":[\"" + CLOUD_PLATFORM_SCOPE + "\"],\"lifetime\":\"900s\"}"
                ))
                .build();
        HttpResponse<String> response = cloudVerificationHttpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );
        if (response.statusCode() == 401) {
            throw new GcpVerificationException(
                    CloudConnectionStatus.INVALID_CREDENTIAL,
                    "Qeploy 실행 환경의 GCP source credential이 올바르지 않습니다."
            );
        }
        if (response.statusCode() == 403) {
            throw new GcpVerificationException(
                    CloudConnectionStatus.PERMISSION_MISSING,
                    "서비스 계정 impersonation 권한(iam.serviceAccounts.getAccessToken)이 없습니다."
            );
        }
        if (response.statusCode() != 200) {
            throw new GcpVerificationException(
                    CloudConnectionStatus.UNKNOWN_ERROR,
                    "GCP IAM Credentials가 access token 요청을 처리하지 못했습니다."
            );
        }
        JsonNode responseBody = objectMapper.readTree(response.body());
        JsonNode accessToken = responseBody.get("accessToken");
        if (accessToken == null || !accessToken.isTextual() || accessToken.asText().isBlank()) {
            throw new GcpVerificationException(
                    CloudConnectionStatus.UNKNOWN_ERROR,
                    "GCP IAM Credentials 응답에 accessToken이 없습니다."
            );
        }
        return accessToken.asText();
    }

    private String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static class GcpVerificationException extends RuntimeException {

        private final CloudConnectionStatus status;

        GcpVerificationException(CloudConnectionStatus status, String message) {
            super(message);
            this.status = status;
        }

        CloudConnectionStatus status() {
            return status;
        }
    }
}
