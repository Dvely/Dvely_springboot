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
        givenNetworks(network("qeploy-preview-old", NOW.minus(Duration.ofDays(30))));
        givenLiveSessions();
        givenProcesses();

        assertThat(policy.sweepOrphans(GRACE, NOW)).isEqualTo(1);
        verify(policy).release("old");
    }

    @Test
    void aSessionWithAContainerIsKeptEvenIfOld() {
        givenNetworks(network("qeploy-preview-live", NOW.minus(Duration.ofDays(1))));
        givenLiveSessions("live");
        givenProcesses("live");

        assertThat(policy.sweepOrphans(GRACE, NOW)).isZero();
        verify(policy, never()).release(anyString());
    }

    @Test
    void aJustCreatedNetworkIsLeftAloneWhileItsContainerIsStillComing() {
        givenNetworks(network("qeploy-preview-new", NOW.minus(Duration.ofMinutes(2))));
        givenLiveSessions();
        givenProcesses("new");

        assertThat(policy.sweepOrphans(GRACE, NOW)).isZero();
    }

    @Test
    void aProxyProcessWhoseNetworkIsGoneIsReleased() {
        givenNetworks(network("bridge", NOW.minus(Duration.ofDays(90))));   // 다른 네트워크는 무시한다
        givenLiveSessions("live");
        givenProcesses("live", "ghost");

        assertThat(policy.sweepOrphans(GRACE, NOW)).isEqualTo(1);
        verify(policy).release("ghost");
        verify(policy, never()).release("live");
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

    private static Network network(String name, Instant created) {
        Network network = mock(Network.class);
        when(network.getName()).thenReturn(name);
        when(network.getCreated()).thenReturn(Date.from(created));
        return network;
    }
}
