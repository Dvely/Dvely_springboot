package com.example.dvely.cloudconnection.application.service;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.awscore.exception.AwsServiceException;

/**
 * 실제 AWS 작업이 <b>자격 자체가 잘못됐다</b>고 답했을 때 연결 상태를 {@code INVALID_CREDENTIAL}
 * 로 돌린다.
 *
 * <h2>왜 필요한가 (#429)</h2>
 * {@code ACCESS_KEY} + {@code sessionToken} 으로 저장된 임시 자격은 만료되고, {@code
 * StaticCredentialsProvider} 는 갱신하지 않는다. 주기적 재검증도 없다(검증 워커는 잡 구동이다).
 * 그래서 연결은 영원히 "연결됨" 으로 남고, 배포는 전부 403 으로 죽는다. 서버는 매시간 그 사실을
 * 알고 있는데 아무에게도 말하지 않았다.
 *
 * <p>장치는 이미 양쪽에 있었다 — 분류({@code ExpiredToken → INVALID_CREDENTIAL})는
 * {@code CloudProviderVerificationClient} 에 있고, 표시 자리는 FE 응답 DTO 에 있다. <b>그 사이가
 * 끊겨 있었다.</b> 분류기 시그니처가 {@code StsException} 전용이라 실제 작업이 던지는
 * {@code CloudFrontException}·{@code Ec2Exception} 이 닿지 못했다.
 *
 * <h2>왜 코드와 메시지를 둘 다 보는가</h2>
 * dev 에서 관측된 실패는 {@code CloudFrontException: The security token included in the request is
 * expired (Service: CloudFront, Status Code: 403)} 이다. SDK v2 의 예외 메시지에는 errorCode 가
 * 들어가지 않으므로 <b>그 로그만 보고는 코드가 무엇인지 알 수 없다</b> — 서비스마다 다를 수도 있다.
 * 코드만 보면 정작 이 사건을 놓칠 수 있어서 메시지도 함께 본다.
 *
 * <p>분류하지 못한 AWS 실패는 {@code debug} 로 남긴다. 조용히 버리면 "분류기가 이 모양을 못 받는다"
 * 는 사실이 다시 숨는다 — 이 이슈가 생긴 원인이 정확히 그것이다.
 *
 * <h2>무엇을 하지 않는가</h2>
 * 일시적 실패(스로틀링·5xx·네트워크)로는 절대 상태를 바꾸지 않는다. 바꿨다가 되돌릴 사람이 없으므로,
 * <b>재시도해도 풀리지 않는 것이 확실한 경우</b>만 반영한다. 권한 부족({@code AccessDenied})도
 * 제외한다 — 그건 {@code PERMISSION_MISSING} 이고 자격이 상한 것과 다른 문제다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwsCredentialFailureReporter {

    /** 자격 자체가 잘못됐다는 뜻이고 재시도로 풀리지 않는 코드. */
    private static final Set<String> DEFINITIVE_CODES = Set.of(
            "ExpiredToken",
            "ExpiredTokenException",
            "InvalidClientTokenId",
            "InvalidAccessKeyId",
            "SignatureDoesNotMatch",
            "UnrecognizedClientException",
            "AuthFailure",
            "InvalidSecurity"
    );

    /**
     * errorCode 가 비어 오는 경로를 위한 보조 판정. 자격이 아닌 실패를 잡지 않도록 좁게 쓴다 —
     * "token" 이나 "invalid" 단독으로는 매치하지 않는다.
     */
    private static final Pattern DEFINITIVE_MESSAGE = Pattern.compile(
            "security token included in the request is (expired|invalid)"
                    + "|request signature we calculated does not match"
                    + "|the security token included in the request is no longer valid",
            Pattern.CASE_INSENSITIVE);

    private final CloudConnectionRepository cloudConnectionRepository;

    /**
     * 실패가 자격 오류로 확실하면 연결 상태를 바꾸고 {@code true} 를 돌려준다. 그 외에는 아무것도
     * 하지 않고 {@code false} 다.
     *
     * <p><b>반환값은 호출부의 로그 등급을 위한 것이다.</b> 같은 자격 실패가 주기마다 반복되므로,
     * 연결 상태로 기록된 뒤에는 호출부가 매 주기 WARN 을 쌓지 않도록 할 수 있다. 동작(다음 주기
     * 재시도)은 바꾸지 않는다 — 스윕을 아예 건너뛰게 만들면, 사용자가 자격을 고친 뒤 재검증을
     * 누르지 않는 동안 그 연결의 고아 자원이 영구히 방치된다.
     *
     * <p>호출부의 흐름을 바꾸지 않는다 — 여기서 예외를 던지면 best-effort 경로였던 스윕·워커가
     * 깨진다. 저장 실패까지 삼키고 로그만 남긴다.
     */
    public boolean reportIfCredentialFailure(Long connectionId, Throwable failure) {
        if (connectionId == null || failure == null) {
            return false;
        }
        Optional<String> reason = definitiveCredentialFailure(failure);
        if (reason.isEmpty()) {
            return false;
        }
        try {
            markInvalid(connectionId, reason.get());
        } catch (RuntimeException e) {
            // 상태 반영은 보조 장치다. 실패해도 호출부의 본래 처리를 막지 않는다.
            log.warn("연결 자격 상태 반영 실패(무해): connectionId={} 원인={}", connectionId, e.toString());
        }
        // 저장이 실패했어도 "자격 실패로 분류됐다" 는 참이다 — 호출부의 로그 판단은 그것에 달렸다.
        return true;
    }

    private void markInvalid(Long connectionId, String reason) {
        Optional<CloudConnection> found = cloudConnectionRepository.findById(connectionId);
        if (found.isEmpty()) {
            return;
        }
        CloudConnection connection = found.get();
        if (connection.getStatus() == CloudConnectionStatus.INVALID_CREDENTIAL) {
            // 같은 실패가 주기마다 반복된다. 이미 반영됐으면 쓰지 않는다 — 안 그러면 매 주기 UPDATE 와
            // 로그가 쌓이고, lastCheckedAt 만 계속 움직여 "언제부터 상했나" 를 잃는다.
            return;
        }
        connection.markHealth(CloudConnectionStatus.INVALID_CREDENTIAL, LocalDateTime.now());
        cloudConnectionRepository.save(connection);
        log.warn("AWS 자격이 더는 유효하지 않아 연결 상태를 INVALID_CREDENTIAL 로 바꿥니다: "
                + "connectionId={} 판정근거={}", connectionId, reason);
    }

    /** 자격 오류로 확실하면 그 근거(코드 또는 "message")를, 아니면 빈 값을 돌려준다. */
    private Optional<String> definitiveCredentialFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (!(current instanceof AwsServiceException aws)) {
                continue;
            }
            String code = aws.awsErrorDetails() == null ? "" : aws.awsErrorDetails().errorCode();
            if (code != null && DEFINITIVE_CODES.contains(code)) {
                return Optional.of(code);
            }
            String message = aws.getMessage() == null ? "" : aws.getMessage();
            if (DEFINITIVE_MESSAGE.matcher(message).find()) {
                return Optional.of("message");
            }
            // 분류하지 못한 AWS 실패를 남긴다. 새 모양이 나타나면 여기서 보인다 — 조용히 버리는 것이
            // 이 이슈를 만든 원인이므로, 같은 구조를 반복하지 않는다.
            log.debug("자격 오류로 분류하지 않은 AWS 실패: code={} status={}",
                    code, aws.statusCode());
        }
        return Optional.empty();
    }
}
