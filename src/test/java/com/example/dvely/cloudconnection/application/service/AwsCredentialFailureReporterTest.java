package com.example.dvely.cloudconnection.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.cloudconnection.domain.value.CloudProvider;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;

/**
 * {@code #429} — 실제 AWS 작업이 "자격이 잘못됐다" 고 답했을 때만 연결 상태를 돌리는지 고정한다.
 *
 * <h2>실측과 단위 테스트의 역할이 다르다</h2>
 * "실제 예외가 분류되는가" 는 로컬 DB 의 상한 자격으로 이미 실측했다 — 실제 AWS 응답의 errorCode 가
 * {@code InvalidClientTokenId} 였고, 첫 주기에 상태가 바뀌고 다음 주기부터는 {@code debug} 로만
 * 남았다(WARN 0건, 재기록 0건). 그건 목으로 증명할 수 없는 부분이다.
 *
 * <p>여기서 고정하는 것은 반대쪽이다 — <b>무엇을 건드리지 않아야 하는지</b>. 일시적 실패나 권한
 * 부족으로 상태를 바꿔 버리면 되돌릴 사람이 없다. 그 경계는 실 AWS 로 재현하기 어렵다.
 */
class AwsCredentialFailureReporterTest {

    private final CloudConnectionRepository repository = mock(CloudConnectionRepository.class);
    private final AwsCredentialFailureReporter reporter = new AwsCredentialFailureReporter(repository);

    @Test
    void 만료된_토큰이면_상태를_INVALID_CREDENTIAL_로_바꾼다() {
        CloudConnection connection = awsConnection(CloudConnectionStatus.VALIDATED);
        when(repository.findById(10L)).thenReturn(Optional.of(connection));

        boolean reported = reporter.reportIfCredentialFailure(10L, awsFailure("ExpiredToken", 403,
                "The security token included in the request is expired"));

        assertThat(reported).isTrue();
        assertThat(connection.getStatus()).isEqualTo(CloudConnectionStatus.INVALID_CREDENTIAL);
        verify(repository).save(connection);
    }

    @Test
    void errorCode_가_없어도_메시지로_판정한다() {
        // dev 에서 관측된 실패는 CloudFrontException 이고, SDK v2 의 예외 메시지에는 errorCode 가
        // 들어가지 않아 로그만으로는 코드를 알 수 없었다. 코드만 보면 정작 그 사건을 놓칠 수 있어
        // 메시지 경로를 함께 둔다 — 이 테스트가 그 경로를 지킨다.
        CloudConnection connection = awsConnection(CloudConnectionStatus.CONNECTED);
        when(repository.findById(10L)).thenReturn(Optional.of(connection));

        boolean reported = reporter.reportIfCredentialFailure(10L, awsFailure(null, 403,
                "The security token included in the request is invalid."));

        assertThat(reported).isTrue();
        assertThat(connection.getStatus()).isEqualTo(CloudConnectionStatus.INVALID_CREDENTIAL);
        verify(repository).save(connection);
    }

    @Test
    void 원인_체인_안에_있어도_찾는다() {
        // 스윕·워커는 SDK 예외를 자기 예외로 감싸 던지는 경우가 있다.
        CloudConnection connection = awsConnection(CloudConnectionStatus.VALIDATED);
        when(repository.findById(10L)).thenReturn(Optional.of(connection));

        RuntimeException wrapped = new IllegalStateException("배포 준비 실패",
                awsFailure("InvalidClientTokenId", 403, "bad key"));

        assertThat(reporter.reportIfCredentialFailure(10L, wrapped)).isTrue();
        assertThat(connection.getStatus()).isEqualTo(CloudConnectionStatus.INVALID_CREDENTIAL);
    }

    @Test
    void 이미_INVALID_CREDENTIAL_이면_다시_쓰지_않는다() {
        // 같은 실패가 주기마다 반복된다. 매번 저장하면 UPDATE 와 로그가 쌓이고 lastCheckedAt 만
        // 계속 움직여 "언제부터 상했나" 를 잃는다. 반환값은 true 여야 한다 — 호출부는 그것으로
        // WARN 을 억제하므로, false 로 바꾸면 소음이 되돌아온다.
        CloudConnection connection = awsConnection(CloudConnectionStatus.INVALID_CREDENTIAL);
        when(repository.findById(10L)).thenReturn(Optional.of(connection));

        boolean reported = reporter.reportIfCredentialFailure(10L,
                awsFailure("ExpiredToken", 403, "expired"));

        assertThat(reported).isTrue();
        verify(repository, never()).save(any());
    }

    @Test
    void 권한_부족은_자격_오류가_아니다() {
        // AccessDenied 는 PERMISSION_MISSING 이고 자격이 상한 것과 다른 문제다. 여기서 뭉개면
        // 사용자는 "자격을 다시 넣으라" 는 잘못된 안내를 받는다.
        CloudConnection connection = awsConnection(CloudConnectionStatus.VALIDATED);
        when(repository.findById(10L)).thenReturn(Optional.of(connection));

        boolean reported = reporter.reportIfCredentialFailure(10L,
                awsFailure("AccessDenied", 403, "not authorized to perform cloudfront:ListDistributions"));

        assertThat(reported).isFalse();
        assertThat(connection.getStatus()).isEqualTo(CloudConnectionStatus.VALIDATED);
        verify(repository, never()).save(any());
    }

    @Test
    void 일시적_실패로는_상태를_바꾸지_않는다() {
        // 스로틀링·5xx 는 다음 주기에 풀린다. 상태를 바꿔 버리면 되돌릴 사람이 없다.
        CloudConnection connection = awsConnection(CloudConnectionStatus.VALIDATED);
        when(repository.findById(10L)).thenReturn(Optional.of(connection));

        assertThat(reporter.reportIfCredentialFailure(10L,
                awsFailure("Throttling", 400, "Rate exceeded"))).isFalse();
        assertThat(reporter.reportIfCredentialFailure(10L,
                awsFailure("InternalError", 500, "We encountered an internal error"))).isFalse();

        assertThat(connection.getStatus()).isEqualTo(CloudConnectionStatus.VALIDATED);
        verify(repository, never()).save(any());
    }

    @Test
    void AWS_예외가_아니면_아무것도_하지_않는다() {
        assertThat(reporter.reportIfCredentialFailure(10L, new IllegalStateException("DB 연결 실패")))
                .isFalse();
        verify(repository, never()).findById(any());
    }

    @Test
    void 저장이_실패해도_호출부로_던지지_않는다() {
        // 상태 반영은 보조 장치다. 여기서 던지면 best-effort 였던 스윕이 깨지고, 고아 자원 청소가
        // 멈춘다 — 보조 장치가 본 기능을 죽이는 모양이 된다.
        CloudConnection connection = awsConnection(CloudConnectionStatus.VALIDATED);
        when(repository.findById(10L)).thenReturn(Optional.of(connection));
        when(repository.save(any())).thenThrow(new RuntimeException("DB 쓰기 실패"));

        assertThat(reporter.reportIfCredentialFailure(10L,
                awsFailure("ExpiredToken", 403, "expired"))).isTrue();
    }

    @Test
    void 연결이_없어도_조용히_끝난다() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThat(reporter.reportIfCredentialFailure(99L,
                awsFailure("ExpiredToken", 403, "expired"))).isTrue();
        verify(repository, never()).save(any());
    }

    private AwsServiceException awsFailure(String errorCode, int statusCode, String message) {
        AwsServiceException.Builder builder = AwsServiceException.builder()
                .message(message)
                .statusCode(statusCode);
        if (errorCode != null) {
            builder.awsErrorDetails(AwsErrorDetails.builder()
                    .errorCode(errorCode)
                    .errorMessage(message)
                    .build());
        }
        return builder.build();
    }

    private CloudConnection awsConnection(CloudConnectionStatus status) {
        return new CloudConnection(
                10L,
                1L,
                CloudProvider.AWS,
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
                null,
                status,
                null,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }
}
