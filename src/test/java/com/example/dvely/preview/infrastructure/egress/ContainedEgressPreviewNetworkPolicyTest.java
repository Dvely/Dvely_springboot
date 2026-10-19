package com.example.dvely.preview.infrastructure.egress;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 프록시 설정과 허용목록은 틀려도 <b>조용히</b> 틀린다 — 너무 열리거나, tinyproxy 가 아예 뜨지
 * 않아 프리뷰가 "설치가 안 된다" 로만 보인다. 그래서 형태를 계약으로 고정한다.
 *
 * <p>#466 이후 실제로 뜨는 tinyproxy 설정은 세션마다 하나씩이라({@code sessionTinyproxyConf}),
 * 여기서 고정하는 계약도 세션별 {@code Listen}·{@code MaxClients}·{@code PidFile} 로 옮겨졌다.
 * 매칭 규칙(필터 순서·{@code ConnectPort}·허용목록 형식)은 예전 전역 설정과 그대로다.</p>
 */
class ContainedEgressPreviewNetworkPolicyTest {

    private static final int PER_SESSION_MAX_CLIENTS = 16;

    private ContainedEgressPreviewNetworkPolicy policy(List<String> hosts) {
        return new ContainedEgressPreviewNetworkPolicy(new PreviewEgressProperties(
                true, "alpine:3.20", "qeploy-preview-egress", "qeploy-egress", 8888,
                "qeploy-preview-", hosts, PER_SESSION_MAX_CLIENTS, 256, 256));
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
    @DisplayName("세션 설정: Listen·MaxClients·PidFile 이 세션마다 받은 값으로 들어간다")
    void sessionConfCarriesPerSessionValues() {
        String conf = policy(List.of("github.com"))
                .sessionTinyproxyConf("172.20.0.5", "/etc/tinyproxy/s-abc123.pid");

        assertThat(conf).contains("Listen 172.20.0.5");
        // MaxClients 는 더 이상 고정값이 아니라 세션 상한(perSessionMaxClients) 이어야 한다 —
        // 이게 전역이면 #466 이 고치려는 문제(한 세션이 다른 세션의 설치를 막는다)가 그대로 돌아온다.
        assertThat(conf).contains("MaxClients " + PER_SESSION_MAX_CLIENTS);
        assertThat(conf).contains("PidFile \"/etc/tinyproxy/s-abc123.pid\"");
    }

    @Test
    @DisplayName("Filter 가 Filter* 옵션들보다 앞에 온다 — 순서가 어긋나면 tinyproxy 가 종료한다")
    void filterDirectiveComesFirst() {
        String conf = policy(List.of("github.com"))
                .sessionTinyproxyConf("172.20.0.5", "/etc/tinyproxy/s-abc123.pid");

        int filter = conf.indexOf("Filter \"");
        assertThat(filter).isGreaterThan(-1);
        assertThat(filter).isLessThan(conf.indexOf("FilterExtended"));
        assertThat(filter).isLessThan(conf.indexOf("FilterDefaultDeny"));
        // 기본 거부여야 한다. Yes 가 아니면 허용목록이 "차단목록" 으로 뒤집힌다.
        assertThat(conf).contains("FilterDefaultDeny Yes");
        // CONNECT 는 443 만 — 다른 포트로 임의 TCP 터널을 뚫지 못하게.
        assertThat(conf).contains("ConnectPort 443");
        // 유휴 연결이 세션 상한을 영구히 쥐고 있지 못하게 하는 바깥 방어선 — 상한 자체는
        // 세션별 MaxClients 로 바뀌었지만 Timeout 은 그대로다.
        assertThat(conf).contains("Timeout 600");
    }

    @Test
    @DisplayName("컨테이너 모양 라벨은 세션별 모양과 pids·메모리 상한을 담는다 — 다르면 컨테이너를 다시 만든다")
    void proxyShapeCarriesContainerLevelLimits() {
        String shape = policy(List.of("github.com")).proxyShape();

        // #466 이전 컨테이너(전역 리스너)에는 이 라벨이 없다 — 그걸 재사용하면 세션 bind 가 실패한다.
        assertThat(shape).startsWith("per-session;");
        assertThat(shape).contains("pids=256", "memory-mb=256");
        // 세션 상한은 세션 설정 파일에서 바로 반영되므로 컨테이너를 다시 만들 이유가 아니다.
        assertThat(shape).doesNotContain("max-clients");
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
                true, "alpine:3.20", "p", "qeploy-egress", 8888, "qeploy-preview-", List.of(),
                PER_SESSION_MAX_CLIENTS, 256, 256);

        assertThat(props.networkName("session-a")).isEqualTo("qeploy-preview-session-a");
        assertThat(props.networkName("session-b")).isNotEqualTo(props.networkName("session-a"));
        assertThat(props.proxyUrl()).isEqualTo("http://qeploy-egress:8888");
    }

    @Test
    @DisplayName("세션 ID 를 그대로 파일 이름에 쓰지 않는다 — 안전한 문자만 남긴다")
    void sanitizeSessionIdKeepsOnlySafeCharacters() {
        // 지금 실제 세션 ID 는 UUID 뿐이라 이 입력들은 호출부가 보내지 않지만, 파일 이름을 만드는
        // 코드는 입력의 출처를 믿지 않는다 — 경로 조작(.. , /)·공백이 섞여도 안전해야 한다.
        assertThat(ContainedEgressPreviewNetworkPolicy.sanitizeSessionId("../../etc/passwd"))
                .doesNotContain("/", "..")
                .matches("[a-zA-Z0-9_-]+");
        assertThat(ContainedEgressPreviewNetworkPolicy.sanitizeSessionId("a b;rm -rf"))
                .matches("[a-zA-Z0-9_-]+");
    }

    @Test
    @DisplayName("일반 UUID 세션 ID 는 사실상 그대로 보존된다 — 하이픈은 안전한 문자다")
    void sanitizeSessionIdPreservesUuidShape() {
        String uuid = "4b6a2f2e-9f3c-4b0a-9d3f-123456789abc";

        assertThat(ContainedEgressPreviewNetworkPolicy.sanitizeSessionId(uuid)).isEqualTo(uuid);
    }

    @Test
    @DisplayName("서로 다른 세션 ID 는 서로 다른 sanitize 결과로 남는다 — 충돌하면 pid/conf 파일을 덮어쓴다")
    void sanitizeSessionIdStaysDistinctAcrossSessions() {
        String a = ContainedEgressPreviewNetworkPolicy.sanitizeSessionId("session-a");
        String b = ContainedEgressPreviewNetworkPolicy.sanitizeSessionId("session-b");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("빈 문자열·null 도 비어있지 않은 안전한 이름으로 떨어진다")
    void sanitizeSessionIdNeverProducesEmptyName() {
        assertThat(ContainedEgressPreviewNetworkPolicy.sanitizeSessionId("")).isEqualTo("unknown");
        assertThat(ContainedEgressPreviewNetworkPolicy.sanitizeSessionId(null)).isEqualTo("unknown");
        // 구분자만으로 이뤄진 입력(".." 처럼 전부 치환 대상)도 빈 문자열로 떨어지지 않는다.
        assertThat(ContainedEgressPreviewNetworkPolicy.sanitizeSessionId("//")).isNotEmpty();
    }
}
