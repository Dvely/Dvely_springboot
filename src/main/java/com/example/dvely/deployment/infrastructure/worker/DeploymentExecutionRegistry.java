package com.example.dvely.deployment.infrastructure.worker;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 이 인스턴스가 실제로 실행하기로 한 배포 이력 ID 의 JVM 로컬 레지스트리(#340 5-8).
 *
 * <p>{@code DeploymentRunWorker#renewLeases} 의 하트비트가 이 집합으로 범위를 좁힌다. 예전에는
 * "이 workerId 가 소유한 IN_PROGRESS 행 전부"를 조건 없이 갱신했고, 그래서 두 가지가 함께
 * 나빴다 — (1) 실행 중인 배포가 하나도 없어도 30초마다 0행짜리 UPDATE 가 나갔고, (2) 실행기에
 * 넘기지 못해 실행 주체가 없는 행의 리스까지 계속 살려두면 {@code recoverExpiredLeases} 가
 * 그것을 영영 회수하지 못한다. {@code AgentRunWorker} 가 #55(ADR-Y4)에서 고친 것과 같은 형태다.</p>
 *
 * <p>등록은 executor 제출 <b>직전</b>에 해야 한다. 제출은 됐지만 아직 executor 큐에서 대기 중인
 * 배포도 하트비트 보호를 받아야 하기 때문이다 — 그 창을 비워두면 리스가 만료돼 회수가 같은
 * 일을 다시 집는데, 원래 제출분은 나중에 그대로 실행된다(이중 실행).</p>
 */
@Component
public class DeploymentExecutionRegistry {

    private final Set<Long> registeredHistoryIds = ConcurrentHashMap.newKeySet();

    public void register(Long historyId) {
        registeredHistoryIds.add(historyId);
    }

    public void unregister(Long historyId) {
        registeredHistoryIds.remove(historyId);
    }

    /** 하트비트의 {@code history_id IN (...)} 필터에 쓸 시점 스냅샷. */
    public Set<Long> snapshot() {
        return Set.copyOf(registeredHistoryIds);
    }
}
