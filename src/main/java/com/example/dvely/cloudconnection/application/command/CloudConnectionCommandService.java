package com.example.dvely.cloudconnection.application.command;

import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.cloudconnection.application.command.dto.CreateCloudConnectionCommand;
import com.example.dvely.cloudconnection.application.result.CloudConnectionVerificationJobResult;
import com.example.dvely.cloudconnection.application.result.CreateCloudConnectionResult;
import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.model.CloudConnectionVerificationJob;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionVerificationJobRepository;
import com.example.dvely.cloudconnection.domain.value.AwsCredentialType;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionVerificationJobStatus;
import com.example.dvely.cloudconnection.domain.value.CloudProvider;
import com.example.dvely.cloudconnection.domain.value.GcpCredentialType;
import com.example.dvely.common.exception.NotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CloudConnectionCommandService {

    private static final Pattern AWS_ACCOUNT_ID = Pattern.compile("^\\d{12}$");
    private static final Pattern AWS_ROLE_ARN = Pattern.compile("^arn:aws(?:-[a-z]+)*:iam::(\\d{12}):role/.+$");
    private static final Pattern AWS_REGION = Pattern.compile("^[a-z]{2}(?:-[a-z]+)+-\\d+$");
    private static final Pattern AWS_ACCESS_KEY_ID = Pattern.compile("^(AKIA|ASIA)[A-Z0-9]{16}$");
    private static final Pattern AWS_SECRET_ACCESS_KEY = Pattern.compile("^[A-Za-z0-9/+=]{20,}$");
    private static final Pattern GCP_PROJECT_ID = Pattern.compile("^[a-z][a-z0-9-]{4,28}[a-z0-9]$");
    private static final Pattern GCP_REGION = Pattern.compile("^[a-z]+-[a-z]+\\d+$");
    private static final Pattern GCP_SERVICE_ACCOUNT_EMAIL = Pattern.compile(
            "^[A-Za-z0-9._%+-]+@([a-z][a-z0-9-]{4,28}[a-z0-9])\\.iam\\.gserviceaccount\\.com$"
    );
    private static final String GCP_TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final UserRepository userRepository;
    private final CloudConnectionRepository cloudConnectionRepository;
    private final CloudConnectionVerificationJobRepository verificationJobRepository;

    @Transactional
    public CreateCloudConnectionResult create(Long ownerUserId, CreateCloudConnectionCommand command) {
        resolveUser(ownerUserId);
        CloudProvider provider = CloudProvider.from(command.provider());
        CloudConnection cloudConnection = switch (provider) {
            case AWS -> createAwsConnection(ownerUserId, command);
            case GCP -> createGcpConnection(ownerUserId, command);
        };
        CloudConnection saved = cloudConnectionRepository.save(cloudConnection);
        CloudConnectionVerificationJob job = verificationJobRepository.save(
                new CloudConnectionVerificationJob(saved.getId(), ownerUserId)
        );
        return new CreateCloudConnectionResult(
                saved.getId(),
                saved.getProvider(),
                saved.getStatus(),
                job.getId()
        );
    }

    @Transactional
    public CloudConnectionVerificationJobResult requestVerification(Long ownerUserId, Long cloudConnectionId) {
        CloudConnection cloudConnection = resolveCloudConnection(ownerUserId, cloudConnectionId);
        CloudConnectionVerificationJob activeJob = verificationJobRepository
                .findLatestByCloudConnectionId(cloudConnectionId)
                .filter(job -> job.getStatus() == CloudConnectionVerificationJobStatus.PENDING
                        || job.getStatus() == CloudConnectionVerificationJobStatus.RUNNING)
                .orElse(null);
        if (activeJob != null) {
            return toJobResult(activeJob);
        }
        cloudConnection.markValidated();
        cloudConnectionRepository.save(cloudConnection);
        CloudConnectionVerificationJob job = verificationJobRepository.save(
                new CloudConnectionVerificationJob(cloudConnectionId, ownerUserId)
        );
        return toJobResult(job);
    }

    @Transactional
    public void delete(Long ownerUserId, Long cloudConnectionId) {
        CloudConnection cloudConnection = resolveCloudConnection(ownerUserId, cloudConnectionId);
        cloudConnectionRepository.deleteById(cloudConnection.getId());
    }

    private CloudConnection createAwsConnection(Long ownerUserId, CreateCloudConnectionCommand command) {
        String displayName = requireText(command.displayName(), "displayName");
        String region = requireText(command.region(), "region").toLowerCase();
        if (!AWS_REGION.matcher(region).matches()) {
            throw new IllegalArgumentException("AWS region 형식이 올바르지 않습니다.");
        }
        AwsCredentialType credentialType = resolveAwsCredentialType(command);

        if (credentialType == AwsCredentialType.ACCESS_KEY) {
            String accessKeyId = requireText(command.accessKeyId(), "accessKeyId");
            String secretAccessKey = requireText(command.secretAccessKey(), "secretAccessKey");
            validateAwsAccessKey(accessKeyId, secretAccessKey, command.sessionToken());
            if (hasText(command.accountId()) && !AWS_ACCOUNT_ID.matcher(command.accountId().trim()).matches()) {
                throw new IllegalArgumentException("AWS accountId는 12자리 숫자여야 합니다.");
            }
            String sessionToken = accessKeyId.startsWith("ASIA")
                    ? trimToNull(command.sessionToken())
                    : null;
            return new CloudConnection(
                    ownerUserId,
                    CloudProvider.AWS,
                    displayName,
                    trimToNull(command.accountId()),
                    region,
                    null,
                    credentialType.name(),
                    accessKeyId,
                    secretAccessKey,
                    sessionToken,
                    null,
                    null,
                    null,
                    null
            );
        }

        String accountId = requireText(command.accountId(), "accountId");
        String roleArn = requireText(command.roleArn(), "roleArn");
        validateAwsRole(accountId, roleArn);
        return new CloudConnection(
                ownerUserId,
                CloudProvider.AWS,
                displayName,
                accountId,
                region,
                roleArn,
                credentialType.name(),
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private CloudConnection createGcpConnection(Long ownerUserId, CreateCloudConnectionCommand command) {
        String displayName = requireText(command.displayName(), "displayName");
        String region = requireText(command.region(), "region").toLowerCase();
        if (!GCP_REGION.matcher(region).matches()) {
            throw new IllegalArgumentException("GCP region 형식이 올바르지 않습니다.");
        }
        GcpCredentialType credentialType = resolveGcpCredentialType(command);

        if (credentialType == GcpCredentialType.SERVICE_ACCOUNT_KEY) {
            GcpServiceAccountKey key = parseGcpServiceAccountKey(command.serviceAccountKeyJson());
            if (hasText(command.gcpProjectId()) && !key.projectId().equals(command.gcpProjectId().trim())) {
                throw new IllegalArgumentException("GCP projectId와 serviceAccountKeyJson의 project_id가 일치하지 않습니다.");
            }
            if (hasText(command.serviceAccountEmail())
                    && !key.clientEmail().equals(command.serviceAccountEmail().trim())) {
                throw new IllegalArgumentException(
                        "GCP serviceAccountEmail과 serviceAccountKeyJson의 client_email이 일치하지 않습니다.");
            }
            return new CloudConnection(
                    ownerUserId,
                    CloudProvider.GCP,
                    displayName,
                    null,
                    region,
                    null,
                    null,
                    null,
                    null,
                    null,
                    credentialType.name(),
                    command.serviceAccountKeyJson().trim(),
                    key.projectId(),
                    key.clientEmail()
            );
        }

        String projectId = requireText(command.gcpProjectId(), "projectId");
        String serviceAccountEmail = requireText(command.serviceAccountEmail(), "serviceAccountEmail");
        if (!GCP_PROJECT_ID.matcher(projectId).matches()) {
            throw new IllegalArgumentException("GCP projectId 형식이 올바르지 않습니다.");
        }
        Matcher matcher = GCP_SERVICE_ACCOUNT_EMAIL.matcher(serviceAccountEmail);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("GCP serviceAccountEmail 형식이 올바르지 않습니다.");
        }
        if (!projectId.equals(matcher.group(1))) {
            throw new IllegalArgumentException("GCP projectId와 serviceAccountEmail의 프로젝트 ID가 일치하지 않습니다.");
        }

        return new CloudConnection(
                ownerUserId,
                CloudProvider.GCP,
                displayName,
                null,
                region,
                null,
                null,
                null,
                null,
                null,
                credentialType.name(),
                null,
                projectId,
                serviceAccountEmail
        );
    }

    private void resolveUser(Long ownerUserId) {
        userRepository.findById(ownerUserId)
                .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다. userId=" + ownerUserId));
    }

    private CloudConnection resolveCloudConnection(Long ownerUserId, Long cloudConnectionId) {
        return cloudConnectionRepository.findByIdAndOwnerUserId(cloudConnectionId, ownerUserId)
                .orElseThrow(() -> new NotFoundException(
                        "클라우드 연결을 찾을 수 없습니다. cloudConnectionId=" + cloudConnectionId));
    }

    private AwsCredentialType resolveAwsCredentialType(CreateCloudConnectionCommand command) {
        if (command.awsCredentialType() != null && !command.awsCredentialType().isBlank()) {
            return AwsCredentialType.from(command.awsCredentialType());
        }
        if (hasText(command.roleArn()) || hasText(command.accountId())) {
            return AwsCredentialType.ROLE_ARN;
        }
        return AwsCredentialType.ACCESS_KEY;
    }

    private GcpCredentialType resolveGcpCredentialType(CreateCloudConnectionCommand command) {
        if (command.gcpCredentialType() != null && !command.gcpCredentialType().isBlank()) {
            return GcpCredentialType.from(command.gcpCredentialType());
        }
        if (hasText(command.serviceAccountKeyJson())) {
            return GcpCredentialType.SERVICE_ACCOUNT_KEY;
        }
        return GcpCredentialType.SERVICE_ACCOUNT_EMAIL;
    }

    private void validateAwsRole(String accountId, String roleArn) {
        if (!AWS_ACCOUNT_ID.matcher(accountId).matches()) {
            throw new IllegalArgumentException("AWS accountId는 12자리 숫자여야 합니다.");
        }
        Matcher matcher = AWS_ROLE_ARN.matcher(roleArn);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("AWS roleArn 형식이 올바르지 않습니다.");
        }
        if (!accountId.equals(matcher.group(1))) {
            throw new IllegalArgumentException("AWS accountId와 roleArn의 계정 ID가 일치하지 않습니다.");
        }
    }

    private void validateAwsAccessKey(String accessKeyId, String secretAccessKey, String sessionToken) {
        if (!AWS_ACCESS_KEY_ID.matcher(accessKeyId).matches()) {
            throw new IllegalArgumentException("AWS accessKeyId 형식이 올바르지 않습니다.");
        }
        if (!AWS_SECRET_ACCESS_KEY.matcher(secretAccessKey).matches()) {
            throw new IllegalArgumentException("AWS secretAccessKey 형식이 올바르지 않습니다.");
        }
        if (accessKeyId.startsWith("ASIA") && !hasText(sessionToken)) {
            throw new IllegalArgumentException("임시 AWS Access Key는 sessionToken이 필요합니다.");
        }
    }

    private GcpServiceAccountKey parseGcpServiceAccountKey(String serviceAccountKeyJson) {
        String json = requireText(serviceAccountKeyJson, "serviceAccountKeyJson");
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            String type = textValue(root, "type");
            String projectId = textValue(root, "project_id");
            String clientEmail = textValue(root, "client_email");
            String privateKey = textValue(root, "private_key");
            String tokenUri = textValue(root, "token_uri");

            if (!"service_account".equals(type)) {
                throw new IllegalArgumentException("GCP service account key JSON의 type은 service_account여야 합니다.");
            }
            if (!GCP_PROJECT_ID.matcher(projectId).matches()) {
                throw new IllegalArgumentException("GCP service account key JSON의 project_id 형식이 올바르지 않습니다.");
            }
            Matcher matcher = GCP_SERVICE_ACCOUNT_EMAIL.matcher(clientEmail);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("GCP service account key JSON의 client_email 형식이 올바르지 않습니다.");
            }
            if (!projectId.equals(matcher.group(1))) {
                throw new IllegalArgumentException("GCP service account key JSON의 project_id와 client_email이 일치하지 않습니다.");
            }
            if (!privateKey.contains("-----BEGIN PRIVATE KEY-----")
                    || !privateKey.contains("-----END PRIVATE KEY-----")) {
                throw new IllegalArgumentException("GCP service account key JSON의 private_key 형식이 올바르지 않습니다.");
            }
            if (!GCP_TOKEN_URI.equals(tokenUri)) {
                throw new IllegalArgumentException("GCP service account key JSON의 token_uri가 올바르지 않습니다.");
            }
            return new GcpServiceAccountKey(projectId, clientEmail);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("GCP service account key JSON을 파싱할 수 없습니다.");
        }
    }

    private String textValue(JsonNode root, String fieldName) {
        JsonNode value = root.get(fieldName);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("GCP service account key JSON에 " + fieldName + " 값이 필요합니다.");
        }
        return value.asText().trim();
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private CloudConnectionVerificationJobResult toJobResult(CloudConnectionVerificationJob job) {
        return new CloudConnectionVerificationJobResult(
                job.getId(),
                job.getCloudConnectionId(),
                job.getStatus(),
                job.getConnectionStatus(),
                job.getMessage(),
                job.getAttempt(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getCompletedAt()
        );
    }

    private record GcpServiceAccountKey(String projectId, String clientEmail) {
    }
}
