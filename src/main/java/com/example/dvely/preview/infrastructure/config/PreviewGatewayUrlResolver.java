package com.example.dvely.preview.infrastructure.config;

import com.example.dvely.config.CorsProperties;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 프리뷰 세션에 발급할 공개 주소의 기준 오리진을 정한다.
 *
 * <p>이 값이 틀리면 증상이 조용하다. 세션은 정상적으로 만들어지고 API도 200을 돌려주는데,
 * 그 안의 `previewUrl`만 열리지 않는 주소다 — 실제로 배포 환경에서
 * `QEPLOY_PREVIEW_GATEWAY_BASE_URL`이 설정된 적이 없어 `http://localhost:8080/...`이 그대로
 * 발급됐고, 브라우저는 연결 거부만 냈다. 그래서 여기서 세 단계로 결정하고, 무엇으로 결정했는지
 * 기동 로그에 남긴다.</p>
 *
 * <ol>
 *   <li>설정값(`qeploy.preview.gateway-base-url`)이 로컬 기본값이 아니면 그대로 쓴다.</li>
 *   <li>비어 있거나 로컬 기본값이면 CORS 허용 오리진 중 첫 번째 비-로컬 오리진을 쓴다. 이
 *       서비스는 FE와 API가 같은 오리진(`https://qeploy.com` + `/api/v1/**`)이라 CORS 오리진이
 *       곧 게이트웨이 오리진이고, CORS는 FE가 API를 호출하는 이상 반드시 맞게 설정돼 있다 —
 *       즉 서버에 새 환경변수를 넣지 않아도 배포 환경에서 올바른 주소가 나온다.</li>
 *   <li>둘 다 없으면 로컬 기본값. 이 경우는 외부에서 열 수 없는 주소이므로 경고를 남긴다.</li>
 * </ol>
 */
@Slf4j
@Component
public class PreviewGatewayUrlResolver {

    static final String LOCAL_DEFAULT = "http://localhost:8080";

    private static final String GATEWAY_PATH_PREFIX = "/api/v1/previews/";

    private final String baseUrl;

    public PreviewGatewayUrlResolver(PreviewProperties previewProperties, CorsProperties corsProperties) {
        this.baseUrl = resolve(previewProperties.getGatewayBaseUrl(), corsProperties.allowedOrigins());
    }

    /** 세션 하나의 공개 주소. 게이트웨이 프록시가 이 경로를 그대로 받는다. */
    public String publicUrl(String sessionId, String accessToken) {
        return baseUrl + GATEWAY_PATH_PREFIX + sessionId + "/" + accessToken + "/";
    }

    /** 해석된 기준 오리진(끝 슬래시 없음). */
    public String baseUrl() {
        return baseUrl;
    }

    private static String resolve(String configured, List<String> allowedOrigins) {
        String normalized = normalize(configured);
        if (normalized != null && !LOCAL_DEFAULT.equals(normalized)) {
            log.info("[PreviewGateway] 기준 오리진 = {} (설정값)", normalized);
            return normalized;
        }

        String fromCors = allowedOrigins.stream()
                .map(PreviewGatewayUrlResolver::normalize)
                .filter(origin -> origin != null && !isLoopback(origin))
                .findFirst()
                .orElse(null);
        if (fromCors != null) {
            log.warn("[PreviewGateway] QEPLOY_PREVIEW_GATEWAY_BASE_URL 미설정 — CORS 오리진에서 유추: {}."
                    + " 프리뷰 주소가 FE와 다른 호스트라면 환경변수를 명시적으로 지정해야 한다.", fromCors);
            return fromCors;
        }

        log.warn("[PreviewGateway] 기준 오리진이 {} 이다. 이 서버가 로컬이 아니라면 발급되는 프리뷰 주소를"
                + " 브라우저가 열 수 없다 — QEPLOY_PREVIEW_GATEWAY_BASE_URL 을 설정할 것.", LOCAL_DEFAULT);
        return LOCAL_DEFAULT;
    }

    /**
     * 끝 슬래시와 공백을 털어낸 오리진. `http(s)://`로 시작하지 않는 값(CORS 패턴이나 오타)은
     * 주소를 만들 수 없으므로 후보에서 제외한다.
     */
    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return null;
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean isLoopback(String origin) {
        String host = origin.substring(origin.indexOf("://") + 3).toLowerCase();
        return host.startsWith("localhost") || host.startsWith("127.0.0.1") || host.startsWith("[::1]");
    }
}
