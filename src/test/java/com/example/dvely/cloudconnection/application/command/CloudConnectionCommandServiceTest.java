package com.example.dvely.cloudconnection.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import com.example.dvely.common.exception.NotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    void 임시_자격은_등록_시점에_거부한다() {
        // #429 A안. 전에는 반대였다 — "임시 Access Key 는 sessionToken 이 필요하다" 로 명시적으로
        // 허용했고, 그 규칙에는 테스트가 아예 없었다. 세션 토큰은 만료되고 갱신되지 않으므로
        // 연결은 등록 직후 검증을 통과하고 며칠 뒤 조용히 죽는다(배포 전부 403, 화면은 "연결됨").
        when(userRepository.findById(1L)).thenReturn(Optional.of(mock(User.class)));

        assertThatThrownBy(() -> service.create(1L, awsAccessKeyCommand(
                "ASIA1234567890ABCDEF", "sessiontokenvalue1234567890")))
                .isInstanceOf(IllegalArgumentException.class)
                // 이유를 말해야 사용자가 조치할 수 있다 — 무엇을 쓰면 되는지까지 담는다.
                .hasMessageContaining("임시 AWS 자격")
                .hasMessageContaining("AKIA")
                .hasMessageContaining("역할 ARN");

        verify(cloudConnectionRepository, never()).save(any());
        verify(verificationJobRepository, never()).save(any());
    }

    @Test
    void 장기_키에_붙은_sessionToken_도_거부한다() {
        // 붙여넣기 사고다. 조용히 버리면 "넣었는데 왜 안 쓰이나" 를 알 길이 없다.
        when(userRepository.findById(1L)).thenReturn(Optional.of(mock(User.class)));

        assertThatThrownBy(() -> service.create(1L, awsAccessKeyCommand(
                "AKIA1234567890ABCDEF", "sessiontokenvalue1234567890")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionToken");

        verify(cloudConnectionRepository, never()).save(any());
    }

    @Test
    void 장기_키는_sessionToken_없이_저장되고_값은_null_이다() {
        // 거부가 과하게 번지지 않았음을 반대 방향에서 고정한다.
        //
        // 저장되는 sessionToken 이 null 인 것도 함께 보지만, 이것은 "계약" 테스트이지 특정 줄을
        // 지키는 테스트가 아니다. 되돌림 검증으로 확인했다 — 저장부를
        // trimToNull(command.sessionToken()) 로 되돌려도 이 테스트는 통과한다. 검증이 비어 있지
        // 않은 sessionToken 을 이미 거부하므로 두 구현을 구별하는 입력이 존재하지 않는다.
        // 즉 "새 sessionToken 이 저장되지 않는다" 를 실제로 지키는 것은 위 거부 테스트 둘이고,
        // 저장부의 null 은 그 사실을 코드에 적어 둔 것에 가깝다.
        when(userRepository.findById(1L)).thenReturn(Optional.of(mock(User.class)));
        when(cloudConnectionRepository.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0)));
        when(verificationJobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(1L, awsAccessKeyCommand("AKIA1234567890ABCDEF", null));

        ArgumentCaptor<CloudConnection> saved = ArgumentCaptor.forClass(CloudConnection.class);
        verify(cloudConnectionRepository).save(saved.capture());
        assertThat(saved.getValue().getSessionToken()).isNull();
    }

    private CreateCloudConnectionCommand awsAccessKeyCommand(String accessKeyId, String sessionToken) {
        return new CreateCloudConnectionCommand(
                "AWS",
                "production",
                "123456789012",
                "ap-northeast-2",
                null,
                "ACCESS_KEY",
                accessKeyId,
                "abcdefghijklmnopqrstuvwxyz1234567890ABCD",
                sessionToken,
                null,
                null,
                null,
                null
        );
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

    @Test
    void foreignOwnerCannotDeleteCloudConnection() {
        when(cloudConnectionRepository.findByIdAndOwnerUserId(10L, 2L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(2L, 10L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("cloudConnectionId=10");

        verify(cloudConnectionRepository, never()).deleteById(any());
        verify(verificationJobRepository, never()).save(any());
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
