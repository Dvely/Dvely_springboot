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
    /**
     * ROLE_ARN 연결. provider·StsClient 의 <b>생성</b>은 지연 초기화라 네트워크를 타지 않는다 —
     * 실제 STS 호출은 자격을 요청할 때 일어난다. 그래서 캐시 동일성만 네트워크 없이 검증할 수 있다.
     */
    private CloudConnection roleConnection(Long id, String roleArn, String region) {
        return new CloudConnection(
                id, CloudProvider.AWS, "role-conn", "123456789012", region, roleArn,
                "ROLE_ARN", null, null, null,
                null, null, null, null);
    }

    @Test
    void 같은_연결은_assume_role_provider_를_재사용한다() {
        // #344 9-1. 전에는 resolve 마다 StsClient 를 새로 만들고 assumeRole 을 네트워크로 호출했다.
        // 상태 폴러 4종(서버 20초·RDS 30초·CDN 30초·EIP 10분)이 각자 그것을 반복했다.
        CloudConnection conn = roleConnection(1L, "arn:aws:iam::123456789012:role/qeploy", "ap-northeast-2");

        var first = resolver.resolve(conn);
        var second = resolver.resolve(conn);

        assertThat(second.credentialsProvider())
                .as("같은 연결이면 provider 인스턴스가 같아야 한다 — 다르면 STS 왕복이 다시 매번 생긴다")
                .isSameAs(first.credentialsProvider());
    }

    @Test
    void 리전만_바꿔_써도_같은_자격_provider_를_쓴다() {
        // resolveInRegion 은 ACM(us-east-1)·CloudFront(글로벌)용이다. 자격은 리전 무관이므로
        // 리전이 다르다고 STS 세션을 새로 발급할 이유가 없다.
        CloudConnection conn = roleConnection(1L, "arn:aws:iam::123456789012:role/qeploy", "ap-northeast-2");

        var home = resolver.resolve(conn);
        var usEast = resolver.resolveInRegion(conn, Region.US_EAST_1);

        assertThat(usEast.region()).isEqualTo(Region.US_EAST_1);
        assertThat(usEast.credentialsProvider()).isSameAs(home.credentialsProvider());
    }

    @Test
    void 역할이_바뀌면_새_provider_를_만든다() {
        // 같은 연결 ID 로 역할만 바꿔 다시 연결할 수 있다. 연결 ID 만 키로 쓰면 옛 역할로 계속
        // 발급해 조용히 권한이 어긋난다 — 실패가 "권한 부족" 으로 나타나 원인을 찾기 어렵다.
        CloudConnection before = roleConnection(1L, "arn:aws:iam::123456789012:role/old", "ap-northeast-2");
        CloudConnection after = roleConnection(1L, "arn:aws:iam::123456789012:role/new", "ap-northeast-2");

        var withOld = resolver.resolve(before);
        var withNew = resolver.resolve(after);

        assertThat(withNew.credentialsProvider()).isNotSameAs(withOld.credentialsProvider());
    }
}
