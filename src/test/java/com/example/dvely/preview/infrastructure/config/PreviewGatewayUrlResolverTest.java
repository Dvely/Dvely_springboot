package com.example.dvely.preview.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.config.CorsProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 배포 환경에서 `previewUrl`이 `http://localhost:8080/...`으로 나가 브라우저가 연결을 거부하던
 * 문제를 고정한다. 이 값은 틀려도 API가 200을 주기 때문에, 잘못된 주소가 조용히 나가는 것을
 * 막는 것이 이 클래스의 존재 이유다.
 */
class PreviewGatewayUrlResolverTest {

    @Test
    void usesTheConfiguredOriginWhenItIsSet() {
        PreviewGatewayUrlResolver resolver = resolver("https://qeploy.com", List.of("https://app.example.com"));

        assertThat(resolver.publicUrl("session-1", "token-1"))
                .isEqualTo("https://qeploy.com/api/v1/previews/session-1/token-1/");
    }

    @Test
    void trimsATrailingSlashSoTheUrlIsNotDoubled() {
        PreviewGatewayUrlResolver resolver = resolver("https://qeploy.com/", List.of());

        assertThat(resolver.publicUrl("s", "t")).isEqualTo("https://qeploy.com/api/v1/previews/s/t/");
    }

    /**
     * 서버에 환경변수를 추가하지 않아도 배포 환경에서 열리는 주소가 나오도록 하는 경로다 —
     * FE가 API를 호출하고 있다는 것 자체가 CORS 오리진이 맞게 설정돼 있다는 증거다.
     */
    @Test
    void fallsBackToTheCorsOriginWhenTheGatewayOriginWasNeverConfigured() {
        PreviewGatewayUrlResolver resolver = resolver(null, List.of("https://qeploy.com"));

        assertThat(resolver.baseUrl()).isEqualTo("https://qeploy.com");
    }

    @Test
    void treatsTheLocalDefaultAsUnconfiguredSoADeployedServerStillResolves() {
        PreviewGatewayUrlResolver resolver = resolver("http://localhost:8080", List.of("https://qeploy.com"));

        assertThat(resolver.baseUrl()).isEqualTo("https://qeploy.com");
    }

    /** 로컬 개발에서는 FE(5173)와 API(8080) 오리진이 다르므로 CORS 값을 가져다 쓰면 안 된다. */
    @Test
    void keepsTheLocalDefaultWhenEveryCorsOriginIsLoopback() {
        PreviewGatewayUrlResolver resolver = resolver(null, List.of("http://localhost:5173", "http://127.0.0.1:3000"));

        assertThat(resolver.baseUrl()).isEqualTo("http://localhost:8080");
    }

    /** CORS 설정에는 오리진이 아닌 패턴이 섞일 수 있는데, 그런 값으로는 주소를 만들 수 없다. */
    @Test
    void ignoresCorsEntriesThatAreNotAbsoluteOrigins() {
        PreviewGatewayUrlResolver resolver = resolver(null, List.of("*.qeploy.com", "https://qeploy.com"));

        assertThat(resolver.baseUrl()).isEqualTo("https://qeploy.com");
    }

    private PreviewGatewayUrlResolver resolver(String configured, List<String> allowedOrigins) {
        PreviewProperties previewProperties = new PreviewProperties();
        previewProperties.setGatewayBaseUrl(configured);
        return new PreviewGatewayUrlResolver(
                previewProperties,
                new CorsProperties(allowedOrigins, List.of())
        );
    }
}
