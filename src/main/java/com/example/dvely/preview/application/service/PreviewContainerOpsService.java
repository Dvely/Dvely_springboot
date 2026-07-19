package com.example.dvely.preview.application.service;

import com.example.dvely.agent.infrastructure.docker.ContainerResourceUsage;
import com.example.dvely.agent.infrastructure.docker.ContainerRuntimeStatus;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.preview.application.result.PreviewContainerLogsResult;
import com.example.dvely.preview.application.result.PreviewContainerStatusResult;
import com.example.dvely.preview.infrastructure.persistence.entity.PreviewSessionEntity;
import com.example.dvely.preview.infrastructure.persistence.repository.SpringDataPreviewSessionRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Read-only observability for a preview session's Docker container: current state (§ status)
 * and stdout/stderr (§ logs). Split out from {@link PreviewSessionService} because it's purely
 * observational — unlike {@code acquire}/{@code close}, neither method here mutates the session
 * row or its TTL (see {@link #getLogs}: an operator watching logs must not accidentally keep an
 * otherwise-idle session alive).
 *
 * Both methods share the same ownership contract as the existing close API: a session owned by
 * another user (or that doesn't exist) is a 404, regardless of its lifecycle status. Sessions
 * that are already CLOSED/EXPIRED are still readable — {@code containerRunning=false} is itself
 * useful information for a FE polling this endpoint.
 *
 * Deliberately NOT {@code @Transactional} at the method level: {@link #getStatus} and
 * {@link #getLogs} both make Docker I/O calls (stats ~1-3s, logs up to 10s) after the ownership
 * lookup, and wrapping the whole method in a transaction would hold a pooled DB connection idle
 * for that entire span — with a small Hikari pool (default 10), a handful of concurrent slow
 * Docker calls could exhaust the pool and start failing unrelated requests (review F2). Spring
 * Data JPA repository methods already run in their own short-lived transaction by default
 * (see {@code SimpleJpaRepository}), so calling {@code repository.findByIdAndOwnerUserId(...)}
 * directly already scopes the connection to just that one query.
 */
@Service
@RequiredArgsConstructor
public class PreviewContainerOpsService {

    // Deployment logs precedent doesn't apply here (no U3 "history limit" merged yet at design
    // time) — chosen directly per design doc §1.2: default 200 lines, clamp to [1, 2000].
    private static final int DEFAULT_TAIL = 200;
    private static final int MIN_TAIL = 1;
    private static final int MAX_TAIL = 2000;

    private final SpringDataPreviewSessionRepository repository;
    private final DockerContainerService dockerService;

    public PreviewContainerStatusResult getStatus(Long ownerUserId, String sessionId) {
        PreviewSessionEntity session = findOwned(sessionId, ownerUserId);
        ContainerRuntimeStatus runtimeStatus = dockerService.getContainerStatus(session.getContainerId());
        // Stats is a ~1s call (design doc §1.1) — only pay for it when the container is
        // actually running; a stopped/missing container has no meaningful resource usage.
        ContainerResourceUsage usage = runtimeStatus.running()
                ? dockerService.getContainerStats(session.getContainerId()).orElse(null)
                : null;
        return PreviewContainerStatusResult.of(session, runtimeStatus, usage);
    }

    public PreviewContainerLogsResult getLogs(Long ownerUserId, String sessionId, Integer tail, Integer sinceSeconds) {
        PreviewSessionEntity session = findOwned(sessionId, ownerUserId);
        boolean running = dockerService.isContainerRunning(session.getContainerId());
        Integer sinceEpochSeconds = toEpochSeconds(sinceSeconds);
        String logText = dockerService.getContainerLogs(session.getContainerId(), clampTail(tail), sinceEpochSeconds);
        return new PreviewContainerLogsResult(session.getId(), running, logText);
    }

    private PreviewSessionEntity findOwned(String sessionId, Long ownerUserId) {
        return repository.findByIdAndOwnerUserId(sessionId, ownerUserId)
                .orElseThrow(() -> new NotFoundException("PreviewSession을 찾을 수 없습니다. sessionId=" + sessionId));
    }

    private int clampTail(Integer tail) {
        int value = tail != null ? tail : DEFAULT_TAIL;
        return Math.max(MIN_TAIL, Math.min(value, MAX_TAIL));
    }

    /**
     * "최근 N초" is translated to an absolute epoch-seconds cutoff here, at request time — an
     * absolute client-supplied timestamp was rejected at design time in favor of a relative
     * value specifically to avoid client/server timezone mismatches (design doc §1.2).
     */
    private Integer toEpochSeconds(Integer sinceSeconds) {
        if (sinceSeconds == null) {
            return null;
        }
        // "recent N seconds" can't be negative — clamp instead of erroring, mirroring the tail
        // clamp policy (design doc §1.2's "클램프, 에러 아님" applies uniformly to query params;
        // review F8).
        int flooredSinceSeconds = Math.max(0, sinceSeconds);
        return clampToEpochSeconds(Instant.now().getEpochSecond(), flooredSinceSeconds);
    }

    /**
     * Package-private (not private) so it's directly unit-testable against a fabricated "now"
     * without mocking the system clock — the int-range clamp below is otherwise unreachable in
     * a test, since the real current epoch is nowhere near the int boundary until ~2038. Docker's
     * `since` filter is a 32-bit unix timestamp; clamping into int range replaces an unchecked
     * narrowing `(int)` cast that would otherwise silently wrap around near that boundary
     * (review F8).
     */
    static int clampToEpochSeconds(long nowEpochSecond, int flooredSinceSeconds) {
        long epochCutoff = nowEpochSecond - flooredSinceSeconds;
        long clamped = Math.max(Integer.MIN_VALUE, Math.min(epochCutoff, Integer.MAX_VALUE));
        return (int) clamped;
    }
}
