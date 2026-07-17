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
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional(readOnly = true)
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

    @Transactional(readOnly = true)
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
        return (int) (Instant.now().getEpochSecond() - sinceSeconds);
    }
}
