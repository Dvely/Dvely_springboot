package com.example.dvely.agent.infrastructure.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import java.time.Duration;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DockerGarbageSweeperTest {

    private final DockerContainerService dockerService = mock(DockerContainerService.class);

    private DockerGarbageSweeper sweeper(boolean enabled, long keepHours) {
        return sweeper(enabled, keepHours, Runnable::run);
    }

    private DockerGarbageSweeper sweeper(boolean enabled, long keepHours, java.util.concurrent.Executor executor) {
        DockerGarbageSweeper sweeper = new DockerGarbageSweeper(dockerService, executor);
        ReflectionTestUtils.setField(sweeper, "enabled", enabled);
        ReflectionTestUtils.setField(sweeper, "buildCacheKeepHours", keepHours);
        return sweeper;
    }

    /** 빌드 캐시는 설정한 보존 시간을 그대로 넘겨야 한다 — 방금 만든 캐시까지 날리면 다음 빌드가 통째로 느려진다. */
    @Test
    void sweepsWithTheConfiguredBuildCacheRetention() {
        when(dockerService.pruneGarbage(any())).thenReturn(1024L * 1024 * 500);

        sweeper(true, 48).sweep();

        verify(dockerService).pruneGarbage(Duration.ofHours(48));
    }

    /** 끌 수 있어야 한다 — 도커를 공유하는 환경에서는 이 앱이 남의 캐시를 지우면 안 된다. */
    @Test
    void doesNothingWhenDisabled() {
        sweeper(false, 48).sweep();

        verify(dockerService, never()).pruneGarbage(any());
    }

    /**
     * 도커에 못 닿아도(-1) 예외 없이 넘어가야 한다. 정리는 부가 기능이고, 이 앱은 도커 없이도
     * 기동하는 것을 전제로 한다.
     */
    @Test
    void survivesWhenDockerIsUnreachable() {
        when(dockerService.pruneGarbage(any())).thenReturn(-1L);

        Assertions.assertThatCode(() -> sweeper(true, 48).sweep()).doesNotThrowAnyException();
    }

    /**
     * #340 5-10 — prune 은 스케줄러 스레드에서 돌지 않는다. prune 3회가 도커 데몬을 잠시
     * 붙잡는 동안 스케줄러를 점유하면 같은 풀을 쓰는 다른 잡이 그만큼 밀린다.
     */
    @Test
    void pruningRunsOnTheMaintenanceExecutorNotTheSchedulerThread() {
        java.util.List<Runnable> submitted = new java.util.ArrayList<>();

        sweeper(true, 48, submitted::add).sweep();

        Assertions.assertThat(submitted).hasSize(1);
        verify(dockerService, never()).pruneGarbage(any());

        submitted.get(0).run();
        verify(dockerService).pruneGarbage(Duration.ofHours(48));
    }
}
