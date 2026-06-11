package com.example.dvely.cloudconnection.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.cloudconnection.application.command.dto.CreateCloudConnectionCommand;
import com.example.dvely.cloudconnection.application.result.CreateCloudConnectionResult;
import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionVerificationJobRepository;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CloudConnectionCommandServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private CloudConnectionRepository cloudConnectionRepository;

    @Mock
    private CloudConnectionVerificationJobRepository verificationJobRepository;

    private CloudConnectionCommandService service;

    @BeforeEach
    void setUp() {
        service = new CloudConnectionCommandService(
                userRepository,
                cloudConnectionRepository,
                verificationJobRepository
        );
    }

    @Test
    void createKeepsFormatValidationSeparateAndReturnsPersistentJobId() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(mock(User.class)));
        when(cloudConnectionRepository.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0)));
        when(verificationJobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CreateCloudConnectionResult result = service.create(1L, new CreateCloudConnectionCommand(
                "AWS",
                "production",
                "123456789012",
                "ap-northeast-2",
                null,
                "ACCESS_KEY",
                "AKIA1234567890ABCDEF",
                "abcdefghijklmnopqrstuvwxyz1234567890ABCD",
                null,
                null,
                null,
                null,
                null
        ));

        assertThat(result.status()).isEqualTo(CloudConnectionStatus.VALIDATED);
        assertThatCodeIsUuid(result.jobId());
    }

    @Test
    void createRejectsUntrustedGcpTokenEndpoint() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(mock(User.class)));

        String serviceAccountKey = """
                {
                  "type": "service_account",
                  "project_id": "qeploy-project",
                  "private_key": "-----BEGIN PRIVATE KEY-----\\nvalue\\n-----END PRIVATE KEY-----\\n",
                  "client_email": "worker@qeploy-project.iam.gserviceaccount.com",
                  "token_uri": "https://example.com/token"
                }
                """;

        assertThatThrownBy(() -> service.create(1L, new CreateCloudConnectionCommand(
                "GCP",
                "production",
                null,
                "asia-northeast3",
                null,
                null,
                null,
                null,
                null,
                "SERVICE_ACCOUNT_KEY",
                serviceAccountKey,
                "qeploy-project",
                "worker@qeploy-project.iam.gserviceaccount.com"
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token_uri");
    }

    private CloudConnection withId(CloudConnection connection) {
        return new CloudConnection(
                10L,
                connection.getOwnerUserId(),
                connection.getProvider(),
                connection.getDisplayName(),
                connection.getAccountId(),
                connection.getRegion(),
                connection.getRoleArn(),
                connection.getAwsCredentialType(),
                connection.getAccessKeyId(),
                connection.getSecretAccessKey(),
                connection.getSessionToken(),
                connection.getGcpCredentialType(),
                connection.getServiceAccountKeyJson(),
                connection.getGcpProjectId(),
                connection.getServiceAccountEmail(),
                connection.getStatus(),
                connection.getLastCheckedAt(),
                connection.getCreatedAt(),
                connection.getUpdatedAt()
        );
    }

    private void assertThatCodeIsUuid(String value) {
        assertThat(UUID.fromString(value).toString()).isEqualTo(value);
    }
}
