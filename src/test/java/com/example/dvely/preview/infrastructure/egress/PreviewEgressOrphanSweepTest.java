package com.example.dvely.preview.infrastructure.egress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Network;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 끝난 세션의 egress 자원 판정(#529). 살아 있거나 막 만들어진 세션은 건드리지 않는다. */
class PreviewEgressOrphanSweepTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");
    private static final Duration GRACE = Duration.ofMinutes(10);

    private final DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
    private final ContainedEgressPreviewNetworkPolicy policy = spy(new ContainedEgressPreviewNetworkPolicy(
            new PreviewEgressProperties(true, "alpine:3.20", "qeploy-preview-egress", "qeploy-egress", 8888,
                    "qeploy-preview-", List.of("github.com"), 16, 256, 256), docker));

    @Test
    void anOldNetworkWithoutAPreviewContainerIsReleased() {
        givenNetworks(legacy("qeploy-preview-11111111-1111-4111-8111-111111111111", NOW.minus(Duration.ofDays(30))));
        givenLiveSessions();
        givenProcesses();

        assertThat(policy.sweepOrphans(GRACE, NOW)).isEqualTo(1);
        verify(policy).release("11111111-1111-4111-8111-111111111111");
    }

    @Test
    void aSessionWithAContainerIsKeptEvenIfOld() {
        givenNetworks(labeled("qeploy-preview-22222222-2222-4222-8222-222222222222", "22222222-2222-4222-8222-222222222222", NOW.minus(Duration.ofDays(1))));
        givenLiveSessions("22222222-2222-4222-8222-222222222222");
        givenProcesses("22222222-2222-4222-8222-222222222222");

        assertThat(policy.sweepOrphans(GRACE, NOW)).isZero();
        verify(policy, never()).release(anyString());
    }

    @Test
    void aJustCreatedNetworkIsLeftAloneWhileItsContainerIsStillComing() {
        givenNetworks(labeled("qeploy-preview-33333333-3333-4333-8333-333333333333", "33333333-3333-4333-8333-333333333333", NOW.minus(Duration.ofMinutes(2))));
        givenLiveSessions();
        givenProcesses("33333333-3333-4333-8333-333333333333");

        assertThat(policy.sweepOrphans(GRACE, NOW)).isZero();
    }

    @Test
    void aProxyProcessWhoseNetworkIsGoneIsReleased() {
        givenNetworks(other("bridge", false));   // 다른 네트워크는 무시한다
        givenLiveSessions("live");
        givenProcesses("live", "ghost");

        assertThat(policy.sweepOrphans(GRACE, NOW)).isEqualTo(1);
        verify(policy).release("ghost");
        verify(policy, never()).release("live");
    }

    @Test
    void onlyNetworksQeployCreatedForASessionAreTouched() {
        // 같은 호스트의 다른 네트워크는 오래됐고 컨테이너가 없어도 건드리지 않는다.
        givenNetworks(
                other("bridge", false),
                other("host", false),
                other("qeploy-preview", false),                              // 공유 프리뷰 네트워크(접두 뒤 '-' 없음)
                other("qeploy-coding-agent", true),                          // coding-agent egress 네트워크
                other("qeploy-preview-egress-proxy", true),                  // 접두는 같지만 UUID 가 아님
                other("qeploy-preview-11111111-1111-4111-8111-111111111111", false),                      // UUID 지만 internal 이 아님(사용자 생성)
                other("my-app_default", true),
                labeled("qeploy-preview-33333333-3333-4333-8333-333333333333", "33333333-3333-4333-8333-333333333333", NOW.minus(Duration.ofDays(3))));
        givenLiveSessions();
        givenProcesses();

        assertThat(policy.sweepOrphans(GRACE, NOW)).isEqualTo(1);
        verify(policy).release("33333333-3333-4333-8333-333333333333");
        verify(policy).release(anyString());
    }

    @Test
    void theProcessPatternMatchesOnlyThatSessionsCommandLine() {
        String pattern = ContainedEgressPreviewNetworkPolicy.sessionProcessPattern("/etc/tinyproxy/s-11111111-1111-4111-8111-111111111111.conf");

        assertThat("tinyproxy -d -c /etc/tinyproxy/s-11111111-1111-4111-8111-111111111111.conf").matches(pattern);
        assertThat("tinyproxy -d -c /etc/tinyproxy/s-11111111-1111-4111-8111-111111111111Xconf").doesNotMatch(pattern);      // 점은 글자 그대로
        assertThat("tinyproxy -d -c /etc/tinyproxy/s-11111111-1111-4111-8111-111111111111.conf.bak").doesNotMatch(pattern);  // 끝 고정
        assertThat("sh -c tinyproxy -d -c /etc/tinyproxy/s-11111111-1111-4111-8111-111111111111.conf").doesNotMatch(pattern); // 앞 고정
    }

    @Test
    void sweepingTwiceIsHarmless() {
        givenNetworks();
        givenLiveSessions();
        givenProcesses();

        assertThat(policy.sweepOrphans(GRACE, NOW)).isZero();
        assertThat(policy.sweepOrphans(GRACE, NOW)).isZero();
    }

    private void givenNetworks(Network... networks) {
        when(docker.listNetworksCmd().exec()).thenReturn(List.of(networks));
        doNothing().when(policy).release(anyString());
    }

    private void givenLiveSessions(String... sessions) {
        doReturn(Set.of(sessions)).when(policy).liveSessionIds();
    }

    private void givenProcesses(String... sessions) {
        doReturn(Set.of(sessions)).when(policy).sessionsWithProxyProcess();
    }

    /** #529 이전에 만든, 라벨 없는 세션 네트워크(internal). */
    private static Network legacy(String name, Instant created) {
        Network network = mock(Network.class);
        when(network.getName()).thenReturn(name);
        when(network.getInternal()).thenReturn(true);
        when(network.getCreated()).thenReturn(Date.from(created));
        return network;
    }

    private static Network labeled(String name, String sessionId, Instant created) {
        Network network = mock(Network.class);
        when(network.getName()).thenReturn(name);
        when(network.getLabels()).thenReturn(java.util.Map.of(
                ContainedEgressPreviewNetworkPolicy.EGRESS_NETWORK_LABEL, "true",
                ContainedEgressPreviewNetworkPolicy.PREVIEW_SESSION_ID_LABEL, sessionId));
        when(network.getCreated()).thenReturn(Date.from(created));
        return network;
    }

    private static Network other(String name, boolean internal) {
        Network network = mock(Network.class);
        when(network.getName()).thenReturn(name);
        when(network.getInternal()).thenReturn(internal);
        when(network.getCreated()).thenReturn(Date.from(NOW.minus(Duration.ofDays(90))));
        return network;
    }

    @Test
    void sessionProcessesAreSignalledAsTheUserTinyproxyRunsAs() {
        // 프록시 컨테이너 root 에는 CAP_KILL 이 없다 — 다른 uid 에 보내는 신호는 막힌다. 같은 uid 로 보낸다.
        ContainedEgressPreviewNetworkPolicy real = new ContainedEgressPreviewNetworkPolicy(
                new PreviewEgressProperties(true, "alpine:3.20", "qeploy-preview-egress", "qeploy-egress", 8888,
                        "qeploy-preview-", List.of("github.com"), 16, 256, 256), docker);

        real.release("11111111-1111-4111-8111-111111111111");

        verify(docker.execCreateCmd("qeploy-preview-egress")).withUser(ContainedEgressPreviewNetworkPolicy.TINYPROXY_USER);
        assertThat(real.sessionTinyproxyConf("10.0.0.2", "/etc/tinyproxy/s-x.pid"))
                .contains("User " + ContainedEgressPreviewNetworkPolicy.TINYPROXY_USER);
    }
}
