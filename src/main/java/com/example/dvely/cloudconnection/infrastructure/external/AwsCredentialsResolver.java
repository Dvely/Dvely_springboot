package com.example.dvely.cloudconnection.infrastructure.external;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.value.AwsCredentialType;
import java.time.Duration;
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
import software.amazon.awssdk.services.sts.model.Credentials;

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
 * <p>assume-role 세션은 유효기간이 짧으므로(15분) 캐시하지 말고 <b>작업마다</b> resolve 를 호출한다.
 * RDS 상태 폴링처럼 오래 걸리는 흐름은 매 호출에서 새로 받아야 만료된 자격으로 실패하지 않는다.</p>
 */
@Component
public class AwsCredentialsResolver {

    private static final Duration ASSUME_ROLE_DURATION = Duration.ofMinutes(15);
    private static final Duration STS_TIMEOUT = Duration.ofSeconds(10);

    /** 연결 자격 + 리전. RDS 클라이언트를 만들 때 둘 다 필요하다. */
    public record AwsAccess(AwsCredentialsProvider credentialsProvider, Region region) {}

    public AwsAccess resolve(CloudConnection connection) {
        Region region = Region.of(connection.getRegion());
        AwsCredentialType type = AwsCredentialType.from(connection.getAwsCredentialType());
        if (type == AwsCredentialType.ACCESS_KEY) {
            return new AwsAccess(StaticCredentialsProvider.create(staticCredentials(connection)), region);
        }
        return new AwsAccess(StaticCredentialsProvider.create(assumeRole(connection, region)), region);
    }

    private AwsSessionCredentials assumeRole(CloudConnection connection, Region region) {
        try (DefaultCredentialsProvider source = DefaultCredentialsProvider.builder().build();
             StsClient sts = StsClient.builder()
                     .region(region)
                     .credentialsProvider(source)
                     .httpClientBuilder(UrlConnectionHttpClient.builder())
                     .overrideConfiguration(c -> c.apiCallTimeout(STS_TIMEOUT))
                     .build()) {
            Credentials credentials = sts.assumeRole(AssumeRoleRequest.builder()
                    .roleArn(connection.getRoleArn())
                    .roleSessionName("qeploy-rds-" + connection.getId())
                    .durationSeconds((int) ASSUME_ROLE_DURATION.toSeconds())
                    .build()).credentials();
            return AwsSessionCredentials.create(
                    credentials.accessKeyId(), credentials.secretAccessKey(), credentials.sessionToken());
        }
    }

    private software.amazon.awssdk.auth.credentials.AwsCredentials staticCredentials(CloudConnection connection) {
        if (connection.getSessionToken() != null) {
            return AwsSessionCredentials.create(
                    connection.getAccessKeyId(), connection.getSecretAccessKey(), connection.getSessionToken());
        }
        return AwsBasicCredentials.create(connection.getAccessKeyId(), connection.getSecretAccessKey());
    }
}
