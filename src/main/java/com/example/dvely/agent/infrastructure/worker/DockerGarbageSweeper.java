package com.example.dvely.agent.infrastructure.worker;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 컨테이너가 남기고 간 도커 찌꺼기(고아 볼륨 · dangling 이미지 · 오래된 빌드 캐시)를 주기적으로
 * 회수한다.
 *
 * <p><b>컨테이너는 지워도 그것들은 남는다.</b> 2026-09-08 dev 에서 컨테이너가 0 개인데 고아 볼륨
 * 10 개(2.2GB)와 빌드 캐시 6.5GB 가 쌓여 디스크의 절반 이상을 찌꺼기가 차지했고, 손으로 지워
 * 67% → 38% 로 내려갔다. 정리 주체가 코드에 아예 없었다 — 컨테이너 제거만 있고 그 뒤가 없었다.</p>
 *
 * <p>디스크가 차면 무엇이 죽는지가 이 워커의 존재 이유다. 앱·MySQL·프리뷰 컨테이너가 한꺼번에
 * 멈추고, 그때는 원인이 "디스크" 라는 것조차 로그를 남길 자리가 없다. 같은 서버에서 pm2 로그가
 * 6.6GB 까지 자라 디스크 89% 에 닿은 적도 있다(deploy/README §5).</p>
 *
 * <p>안전 근거: 도커의 prune 은 <b>실행 중이거나 정지 상태로 남아 있는 컨테이너가 참조하는 것을
 * 건드리지 않는다.</b> 살아 있는 프리뷰 세션의 컨테이너·볼륨은 대상이 아니다. 빌드 캐시도
 * {@code until} 이전 것만 지우므로 방금 만든 캐시는 남는다 — 다음 빌드를 통째로 느리게 만들지
 * 않기 위함이다.</p>
 *
 * <p>볼륨 누수 자체는 {@code removeContainer} 의 {@code withRemoveVolumes(true)} 로 원천에서
 * 막았다. 이 워커는 그 이전에 쌓인 것과, 비정상 종료로 새는 것을 받아내는 안전망이다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DockerGarbageSweeper {

    private final DockerContainerService dockerService;

    /** 이 시간 안에 쓰인 빌드 캐시는 남긴다. */
    @Value("${qeploy.docker.sweep.build-cache-keep-hours:48}")
    private long buildCacheKeepHours;

    @Value("${qeploy.docker.sweep.enabled:true}")
    private boolean enabled;

    /**
     * 기본 6시간마다. 잦을 이유가 없다 — 찌꺼기는 며칠에 걸쳐 쌓이고, prune 자체가 도커 데몬을
     * 잠시 붙잡으므로 빌드가 도는 중에 자주 끼어들면 손해다.
     */
    @Scheduled(
            initialDelayString = "${qeploy.docker.sweep.initial-delay-ms:600000}",
            fixedDelayString = "${qeploy.docker.sweep.interval-ms:21600000}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        long freed = dockerService.pruneGarbage(Duration.ofHours(buildCacheKeepHours));
        if (freed < 0) {
            return;   // 도커에 못 닿음 — pruneGarbage 가 이미 경고를 남겼다
        }
        // 0 바이트일 때는 남기지 않는다. 정상 상태가 대부분이라 그때마다 찍으면 로그만 늘고,
        // 정작 "얼마나 쌓였다가 지워졌나" 를 되짚을 때 묻힌다.
        if (freed > 0) {
            log.info("[DockerSweeper] 도커 찌꺼기 회수: {} MB (빌드 캐시는 {}시간 이내 것 유지)",
                    freed / (1024 * 1024), buildCacheKeepHours);
        }
    }
}
