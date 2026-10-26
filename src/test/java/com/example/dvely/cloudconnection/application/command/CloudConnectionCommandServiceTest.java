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
import com.example.dvely.cloudconnection.application.command.dto.ReplaceCloudConnectionCredentialsCommand;
import com.example.dvely.cloudconnection.application.port.out.CloudConnectionResourceUsagePort;
import com.example.dvely.cloudconnection.application.port.out.CloudConnectionResourceUsagePort.ResourceUsage;
import com.example.dvely.cloudconnection.application.result.CloudConnectionVerificationJobResult;
import com.example.dvely.cloudconnection.application.result.CreateCloudConnectionResult;
import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionVerificationJobRepository;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.cloudconnection.domain.value.CloudProvider;
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

    @Mock
    private CloudConnectionResourceUsagePort resourceUsagePort;

    private CloudConnectionCommandService service;

    @BeforeEach
    void setUp() {
        service = new CloudConnectionCommandService(
                userRepository,
                cloudConnectionRepository,
                verificationJobRepository,
                resourceUsagePort
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

    @Test
    void 리소스가_남은_연결은_해제하지_않는다() {
        // #495. 자원 테이블은 연결을 외래키로 묶지 않아 DB 가 막아 주지 않는다. 지우면 그 DB·서버·CDN 을
        // 지울 자격이 사라진다(dev 연결 2 / DB 12 의 모양).
        CloudConnection connection = awsConnection("123456789012", "AKIA1234567890ABCDEF", null,
                CloudConnectionStatus.INVALID_CREDENTIAL);
        when(cloudConnectionRepository.findByIdAndOwnerUserId(10L, 1L)).thenReturn(Optional.of(connection));
        when(resourceUsagePort.usageOf(10L)).thenReturn(new ResourceUsage(1, 0, 2, 0));

        assertThatThrownBy(() -> service.delete(1L, 10L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB 1개")
                .hasMessageContaining("CloudFront 도메인 2개")
                .hasMessageContaining("리소스를 먼저 삭제")
                .hasMessageContaining("자격 교체");

        verify(cloudConnectionRepository, never()).deleteById(any());
    }

    @Test
    void 리소스가_없는_연결은_해제한다() {
        CloudConnection connection = awsConnection("123456789012", "AKIA1234567890ABCDEF", null,
                CloudConnectionStatus.CONNECTED);
        when(cloudConnectionRepository.findByIdAndOwnerUserId(10L, 1L)).thenReturn(Optional.of(connection));
        when(resourceUsagePort.usageOf(10L)).thenReturn(new ResourceUsage(0, 0, 0, 0));

        service.delete(1L, 10L);

        verify(cloudConnectionRepository).deleteById(10L);
    }

    // ── 자격 교체(#491) ─────────────────────────────────────────────────────

    @Test
    void 만료된_임시_자격_연결에_장기_키를_넣으면_같은_연결로_되살아난다() {
        // dev 연결 2 의 실제 모양. 자원은 연결 id 로 묶여 있어서 id 가 바뀌면 그 DB 를 지울 길이 없다.
        CloudConnection expired = awsConnection("123456789012", "ASIA1234567890ABCDEF", "old-session-token",
                CloudConnectionStatus.INVALID_CREDENTIAL);
        when(cloudConnectionRepository.findByIdAndOwnerUserId(10L, 1L)).thenReturn(Optional.of(expired));
        when(verificationJobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CloudConnectionVerificationJobResult job = service.replaceCredentials(1L, 10L,
                new ReplaceCloudConnectionCredentialsCommand(
                        "ACCESS_KEY", null, null, "AKIA1234567890ABCDEF", "abcdefghijklmnopqrstuvwxyz1234567890ABCD"));

        ArgumentCaptor<CloudConnection> saved = ArgumentCaptor.forClass(CloudConnection.class);
        verify(cloudConnectionRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(10L);
        assertThat(saved.getValue().getAccessKeyId()).isEqualTo("AKIA1234567890ABCDEF");
        // 남은 옛 세션 토큰이 장기 키에 붙으면 AwsSessionCredentials 로 서명돼 새 키도 실패한다.
        assertThat(saved.getValue().getSessionToken()).isNull();
        assertThat(saved.getValue().getAccountId()).isEqualTo("123456789012");
        assertThat(saved.getValue().getRegion()).isEqualTo("ap-northeast-2");
        assertThat(saved.getValue().getStatus()).isEqualTo(CloudConnectionStatus.VALIDATED);
        assertThat(job.cloudConnectionId()).isEqualTo(10L);
        assertThatCodeIsUuid(job.jobId());
    }

    @Test
    void 다른_계정으로는_자격을_바꿀_수_없다() {
        // 자원은 원래 계정에 있다. 다른 계정 자격으로 삭제를 부르면 엉뚱한 계정을 향한다.
        CloudConnection connection = awsConnection("123456789012", "AKIA1234567890ABCDEF", null,
                CloudConnectionStatus.INVALID_CREDENTIAL);
        when(cloudConnectionRepository.findByIdAndOwnerUserId(10L, 1L)).thenReturn(Optional.of(connection));

        assertThatThrownBy(() -> service.replaceCredentials(1L, 10L, new ReplaceCloudConnectionCredentialsCommand(
                "ACCESS_KEY", "999999999999", null, "AKIAZZZZ567890ABCDEF", "abcdefghijklmnopqrstuvwxyz1234567890ABCD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("123456789012");

        verify(cloudConnectionRepository, never()).save(any());
        verify(verificationJobRepository, never()).save(any());
    }

    @Test
    void 교체에도_임시_자격은_거부한다() {
        CloudConnection connection = awsConnection("123456789012", "ASIA1234567890ABCDEF", "old-session-token",
                CloudConnectionStatus.INVALID_CREDENTIAL);
        when(cloudConnectionRepository.findByIdAndOwnerUserId(10L, 1L)).thenReturn(Optional.of(connection));

        assertThatThrownBy(() -> service.replaceCredentials(1L, 10L, new ReplaceCloudConnectionCredentialsCommand(
                "ACCESS_KEY", null, null, "ASIA9999567890ABCDEF", "abcdefghijklmnopqrstuvwxyz1234567890ABCD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("임시 AWS 자격");

        verify(cloudConnectionRepository, never()).save(any());
    }

    @Test
    void 역할_위임으로_바꾸면_키를_지우고_계정과_ARN_을_대조한다() {
        CloudConnection connection = awsConnection("123456789012", "AKIA1234567890ABCDEF", null,
                CloudConnectionStatus.INVALID_CREDENTIAL);
        when(cloudConnectionRepository.findByIdAndOwnerUserId(10L, 1L)).thenReturn(Optional.of(connection));
        when(verificationJobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.replaceCredentials(1L, 10L, new ReplaceCloudConnectionCredentialsCommand(
                "ROLE_ARN", null, "arn:aws:iam::999999999999:role/QeployDeployRole", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("roleArn");

        service.replaceCredentials(1L, 10L, new ReplaceCloudConnectionCredentialsCommand(
                "ROLE_ARN", null, "arn:aws:iam::123456789012:role/QeployDeployRole", null, null));

        ArgumentCaptor<CloudConnection> saved = ArgumentCaptor.forClass(CloudConnection.class);
        verify(cloudConnectionRepository).save(saved.capture());
        assertThat(saved.getValue().getAwsCredentialType()).isEqualTo("ROLE_ARN");
        assertThat(saved.getValue().getAccessKeyId()).isNull();
        assertThat(saved.getValue().getSecretAccessKey()).isNull();
    }

    @Test
    void 남의_연결은_자격을_바꿀_수_없다() {
        when(cloudConnectionRepository.findByIdAndOwnerUserId(10L, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replaceCredentials(2L, 10L, new ReplaceCloudConnectionCredentialsCommand(
                "ACCESS_KEY", null, null, "AKIA1234567890ABCDEF", "abcdefghijklmnopqrstuvwxyz1234567890ABCD")))
                .isInstanceOf(NotFoundException.class);

        verify(cloudConnectionRepository, never()).save(any());
    }

    @Test
    void 교체_명령은_시크릿을_toString_에_찍지_않는다() {
        ReplaceCloudConnectionCredentialsCommand command = new ReplaceCloudConnectionCredentialsCommand(
                "ACCESS_KEY", null, null, "AKIA1234567890ABCDEF", "secret-value-should-not-appear");
        assertThat(command.toString()).doesNotContain("secret-value-should-not-appear");
    }

    private CloudConnection awsConnection(String accountId, String accessKeyId, String sessionToken,
                                          CloudConnectionStatus status) {
        return new CloudConnection(10L, 1L, CloudProvider.AWS, "production", accountId, "ap-northeast-2", null,
                "ACCESS_KEY", accessKeyId, "oldsecretoldsecretoldsecret", sessionToken,
                null, null, null, null, status, null, null, null);
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
