package com.example.dvely.preview.infrastructure.egress;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 프록시 설정과 허용목록은 틀려도 <b>조용히</b> 틀린다 — 너무 열리거나, tinyproxy 가 아예 뜨지
 * 않아 프리뷰가 "설치가 안 된다" 로만 보인다. 그래서 형태를 계약으로 고정한다.
 */
class ContainedEgressPreviewNetworkPolicyTest {

    private ContainedEgressPreviewNetworkPolicy policy(List<String> hosts) {
        return new ContainedEgressPreviewNetworkPolicy(new PreviewEgressProperties(
                true, "alpine:3.20", "qeploy-preview-egress", "qeploy-egress", 8888,
                "qeploy-preview-", hosts));
    }

    @Test
    @DisplayName("허용목록은 앵커를 붙이고 점을 이스케이프한다 — 하위 도메인이 함께 열리면 안 된다")
    void allowListIsAnchoredAndEscaped() {
        String list = policy(List.of("github.com", "registry.npmjs.org")).allowList();

        assertThat(list.lines()).containsExactly("^github\\.com$", "^registry\\.npmjs\\.org$");
        // 점을 이스케이프하지 않으면 정규식에서 임의의 한 글자가 되어 githubXcom 까지 열린다.
        assertThat(list).doesNotContain("^github.com$");
    }

    @Test
    @DisplayName("Filter 가 Filter* 옵션들보다 앞에 온다 — 순서가 어긋나면 tinyproxy 가 종료한다")
    void filterDirectiveComesFirst() {
        String conf = policy(List.of("github.com")).tinyproxyConf();

        int filter = conf.indexOf("Filter \"");
        assertThat(filter).isGreaterThan(-1);
        assertThat(filter).isLessThan(conf.indexOf("FilterExtended"));
        assertThat(filter).isLessThan(conf.indexOf("FilterDefaultDeny"));
        // 기본 거부여야 한다. Yes 가 아니면 허용목록이 "차단목록" 으로 뒤집힌다.
        assertThat(conf).contains("FilterDefaultDeny Yes");
        // CONNECT 는 443 만 — 다른 포트로 임의 TCP 터널을 뚫지 못하게.
        assertThat(conf).contains("ConnectPort 443");
    }

    @Test
    @DisplayName("환경변수에 JVM 프록시가 함께 간다 — JVM 은 환경변수 프록시를 스스로 읽지 않는다")
    void environmentCarriesJvmProxyToo() {
        List<String> env = policy(List.of("github.com")).environment();

        assertThat(env).contains(
                "HTTP_PROXY=http://qeploy-egress:8888",
                "HTTPS_PROXY=http://qeploy-egress:8888",
                "http_proxy=http://qeploy-egress:8888",
                "https_proxy=http://qeploy-egress:8888");
        // 이게 빠지면 gradle 이 배포본·의존성을 받지 못한다(JAVA_FULLSTACK 이 통째로 막힌다).
        assertThat(env).anySatisfy(entry -> assertThat(entry)
                .startsWith("JAVA_TOOL_OPTIONS=")
                .contains("-Dhttps.proxyHost=qeploy-egress")
                .contains("-Dhttps.proxyPort=8888"));
    }

    @Test
    @DisplayName("세션마다 네트워크 이름이 갈린다 — 같으면 프리뷰끼리 다시 만난다")
    void networkNameIsPerSession() {
        PreviewEgressProperties props = new PreviewEgressProperties(
                true, "alpine:3.20", "p", "qeploy-egress", 8888, "qeploy-preview-", List.of());

        assertThat(props.networkName("session-a")).isEqualTo("qeploy-preview-session-a");
        assertThat(props.networkName("session-b")).isNotEqualTo(props.networkName("session-a"));
        assertThat(props.proxyUrl()).isEqualTo("http://qeploy-egress:8888");
    }
}
