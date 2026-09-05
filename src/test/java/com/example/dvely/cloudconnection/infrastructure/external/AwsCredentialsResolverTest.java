package com.example.dvely.cloudconnection.infrastructure.external;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.value.CloudProvider;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;

class AwsCredentialsResolverTest {

    private final AwsCredentialsResolver resolver = new AwsCredentialsResolver();

    private CloudConnection accessKeyConnection() {
        // ACCESS_KEY 연결 — resolve/resolveInRegion 이 정적 자격을 만들어 네트워크 없이 검증 가능하다.
        return new CloudConnection(
                1L, CloudProvider.AWS, "test-conn", "123456789012", "ap-northeast-2", null,
                "ACCESS_KEY", "AKIAEXAMPLEKEY", "secretExampleKey", null,
                null, null, null, null);
    }

    @Test
    void resolve_usesConnectionRegion() {
        var access = resolver.resolve(accessKeyConnection());
        assertThat(access.region()).isEqualTo(Region.of("ap-northeast-2"));
        assertThat(access.credentialsProvider().resolveCredentials().accessKeyId())
                .isEqualTo("AKIAEXAMPLEKEY");
    }

    @Test
    void resolveInRegion_overridesRegion_butReusesSameCredentials() {
        // CloudFront 용 ACM 은 us-east-1 이어야 하므로, 같은 연결 자격을 쓰되 리전만 바꾼다.
        CloudConnection conn = accessKeyConnection();
        var usEast = resolver.resolveInRegion(conn, Region.US_EAST_1);
        var global = resolver.resolveInRegion(conn, Region.AWS_GLOBAL);

        assertThat(usEast.region()).isEqualTo(Region.US_EAST_1);          // ACM
        assertThat(global.region()).isEqualTo(Region.AWS_GLOBAL);          // CloudFront(글로벌)
        // 리전만 다르고 자격(정적 키)은 연결 그대로여야 한다.
        assertThat(usEast.credentialsProvider().resolveCredentials().accessKeyId())
                .isEqualTo("AKIAEXAMPLEKEY");
        assertThat(global.credentialsProvider().resolveCredentials().accessKeyId())
                .isEqualTo("AKIAEXAMPLEKEY");
    }
}
