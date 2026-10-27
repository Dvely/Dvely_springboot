package com.example.dvely.preview.infrastructure.egress;

import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 끝난 프리뷰 세션이 남긴 egress 네트워크·프록시 프로세스를 주기적으로 거둔다(#529).
 *
 * <p>세션 회수 경로가 실패하거나(예: 컨테이너가 밖에서 사라져 세션 라벨을 못 읽음) 예전 코드로 남은
 * 것을 가져간다. 판정과 정리는 {@link ContainedEgressPreviewNetworkPolicy#sweepOrphans} 가 하고 멱등이라
 * 인스턴스가 여럿이어도 겹쳐 돌아 문제가 없다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "qeploy.preview.egress", name = "enabled", havingValue = "true")
public class PreviewEgressOrphanSweeper {

    /** 막 만든 세션은 네트워크가 컨테이너보다 먼저 생긴다. 이보다 젊은 네트워크는 건드리지 않는다. */
    static final Duration GRACE = Duration.ofMinutes(10);

    private final ContainedEgressPreviewNetworkPolicy policy;

    @Scheduled(initialDelayString = "${qeploy.preview.egress.orphan-sweep-initial-delay-ms:60000}",
            fixedDelayString = "${qeploy.preview.egress.orphan-sweep-interval-ms:600000}")
    public void sweep() {
        try {
            policy.sweepOrphans(GRACE, Instant.now());
        } catch (RuntimeException e) {
            log.warn("[PreviewEgress] egress 고아 정리 실패(다음 주기에 다시 시도): {}", e.toString());
        }
    }
}
