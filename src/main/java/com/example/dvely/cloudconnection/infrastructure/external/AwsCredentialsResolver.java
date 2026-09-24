package com.example.dvely.cloudconnection.infrastructure.external;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.value.AwsCredentialType;
import java.time.Duration;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

/**
 * BYOC 클라우드 연결(CloudConnection)을 실제 AWS 호출에 쓸 자격으로 바꾼다. 지금까지 이 로직은
 * CloudProviderVerificationClient 안에 private 로만 있어 재사용할 수 없었다 — RDS 프로비저닝이 같은
 * 자격을 필요로 하므로 공용 컴포넌트로 뽑는다.
 *
 * <ul>
 *   <li>ACCESS_KEY: 저장된 액세스 키(+세션 토큰)로 정적 자격.
 *   <li>ROLE_ARN: 서버 자체 자격으로 STS assume-role 해 임시 세션 자격을 얻는다.
 * </ul>
 *
 * <p><b>assume-role 자격은 연결별로 캐시한다(#344 9-1).</b> 전에는 "세션이 15분이라 캐시하지 말고
 * 작업마다 resolve 하라" 고 적혀 있었는데, 그러면 상태 폴러 4종(서버 20초·RDS 30초·CDN 30초·EIP 10분)이
 * <b>폴링마다 STS 왕복</b>을 하고 15분짜리 세션을 계속 새로 발급했다. 작업 하나에 최대 10초짜리
 * 네트워크 호출이 덧붙는다.</p>
 *
 * <p>그 주석의 우려(폴링 중 만료)는 {@link StsAssumeRoleCredentialsProvider} 가 해소한다 — 남은 수명이
 * {@code staleTime} 아래로 떨어지면 <b>만료 전에</b> 다시 발급한다. 즉 "캐시하면 만료된 자격을 쓸 수
 * 있다" 는 전제 자체가 직접 캐시할 때만 참이었다.</p>
 */
@Slf4j
@Component
public class AwsCredentialsResolver {

    private static final Duration ASSUME_ROLE_DURATION = Duration.ofMinutes(15);
    private static final Duration STS_TIMEOUT = Duration.ofSeconds(10);
    // 남은 수명이 이 아래면 만료 전에 다시 발급한다. 15분 세션에 2분이면 폴링 한 번이 자격 만료로
    // 실패할 여지가 없다 — 가장 촘촘한 폴러(서버 20초)의 여섯 배 여유다.
    private static final Duration STALE_TIME = Duration.ofMinutes(2);

    private final Map<String, CachedAssumeRole> cache = new ConcurrentHashMap<>();

    /** 연결 자격 + 리전. RDS 클라이언트를 만들 때 둘 다 필요하다. */
    public record AwsAccess(AwsCredentialsProvider credentialsProvider, Region region) {}

    public AwsAccess resolve(CloudConnection connection) {
        return new AwsAccess(credentialsProvider(connection), Region.of(connection.getRegion()));
    }

    /**
     * 같은 연결 자격을 쓰되 <b>대상 클라이언트 리전만</b> 지정한 값으로 바꿔 준다. 자격(assume-role 세션·
     * 정적 키)은 리전 무관이라 그대로 재사용한다. CloudFront 용 <b>ACM 인증서는 반드시 us-east-1</b> 이고
     * CloudFront 자체는 글로벌({@code AWS_GLOBAL})이라, 연결 리전(예 ap-northeast-2)과 다른 리전에 클라이언트를
     * 만들어야 하는 이 두 경우에 쓴다.
     */
    public AwsAccess resolveInRegion(CloudConnection connection, Region region) {
        return new AwsAccess(credentialsProvider(connection), region);
    }

    private AwsCredentialsProvider credentialsProvider(CloudConnection connection) {
        AwsCredentialType type = AwsCredentialType.from(connection.getAwsCredentialType());
        if (type == AwsCredentialType.ACCESS_KEY) {
            return StaticCredentialsProvider.create(staticCredentials(connection));
        }
        // assume-role 의 STS 호출은 연결 리전에서 하되(리전 엔드포인트일 뿐), 그렇게 얻은 세션 자격은
        // 어느 리전 클라이언트에도 쓸 수 있다.
        return assumeRoleProvider(connection).provider();
    }

    /**
     * 연결별 assume-role provider. 캐시 키에 <b>roleArn 과 리전까지</b> 넣는다 — 같은 연결이 역할을
     * 바꿔 다시 연결될 수 있고(그때 옛 역할로 계속 발급하면 조용히 권한이 어긋난다), STS 엔드포인트가
     * 리전마다 다르다.
     */
    private CachedAssumeRole assumeRoleProvider(CloudConnection connection) {
        String key = connection.getId() + "|" + connection.getRoleArn() + "|" + connection.getRegion();
        return cache.computeIfAbsent(key, ignored -> buildAssumeRole(connection));
    }

    private CachedAssumeRole buildAssumeRole(CloudConnection connection) {
        StsClient sts = StsClient.builder()
                .region(Region.of(connection.getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(c -> c.apiCallTimeout(STS_TIMEOUT))
                .build();
        // 비동기 갱신은 켜지 않는다 — provider 당 백그라운드 스레드가 하나씩 생긴다. staleTime 만으로도
        // 만료 전에 갱신되고, 그 갱신 비용은 약 13분에 한 번 어느 호출자 하나가 무는 것으로 끝난다
        // (전에는 모든 호출자가 매번 물었다).
        StsAssumeRoleCredentialsProvider provider = StsAssumeRoleCredentialsProvider.builder()
                .stsClient(sts)
                .staleTime(STALE_TIME)
                .refreshRequest(AssumeRoleRequest.builder()
                        .roleArn(connection.getRoleArn())
                        .roleSessionName("qeploy-" + connection.getId())
                        .durationSeconds((int) ASSUME_ROLE_DURATION.toSeconds())
                        .build())
                .build();
        return new CachedAssumeRole(provider, sts);
    }

    /** provider 와 그것이 쓰는 StsClient. 캐시하므로 둘의 수명은 애플리케이션과 같고, 종료 시 함께 닫는다. */
    private record CachedAssumeRole(StsAssumeRoleCredentialsProvider provider, StsClient sts) {
    }

    /**
     * 캐시한 provider·StsClient 를 닫는다. 이 자원들은 호출마다 만들고 닫던 것이 아니라 이제
     * 애플리케이션 수명을 사므로, 종료 때 명시적으로 닫아야 커넥션·스레드가 남지 않는다.
     */
    @PreDestroy
    void closeCachedProviders() {
        cache.values().forEach(cached -> {
            try {
                cached.provider().close();
                cached.sts().close();
            } catch (RuntimeException e) {
                log.warn("STS provider 정리 실패(무해): 원인={}", e.toString());
            }
        });
        cache.clear();
    }

    private software.amazon.awssdk.auth.credentials.AwsCredentials staticCredentials(CloudConnection connection) {
        if (connection.getSessionToken() != null) {
            return AwsSessionCredentials.create(
                    connection.getAccessKeyId(), connection.getSecretAccessKey(), connection.getSessionToken());
        }
        return AwsBasicCredentials.create(connection.getAccessKeyId(), connection.getSecretAccessKey());
    }
}
