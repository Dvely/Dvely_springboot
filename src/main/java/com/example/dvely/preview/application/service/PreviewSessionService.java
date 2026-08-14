package com.example.dvely.preview.application.service;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.preview.application.result.PreviewAccessGrant;
import com.example.dvely.preview.application.result.PreviewSessionInfo;
import com.example.dvely.preview.domain.value.PreviewSessionStatus;
import com.example.dvely.preview.infrastructure.config.PreviewGatewayUrlResolver;
import com.example.dvely.preview.infrastructure.config.PreviewProperties;
import com.example.dvely.preview.infrastructure.persistence.entity.PreviewSessionEntity;
import com.example.dvely.preview.infrastructure.persistence.repository.SpringDataPreviewSessionRepository;
import com.example.dvely.preview.infrastructure.security.PreviewAccessCookies;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PreviewSessionService {

    private final SpringDataPreviewSessionRepository repository;
    private final DockerContainerService dockerService;
    private final TaskStore taskStore;
    private final PreviewProperties properties;
    private final PreviewGatewayUrlResolver gatewayUrlResolver;
    private final PreviewAccessCookies accessCookies;

    public PreviewSessionInfo acquire(String taskId) {
        AgentTask task = taskStore.get(taskId);
        if (task == null) {
            throw new IllegalStateException("Preview task를 찾을 수 없습니다. taskId=" + taskId);
        }

        Optional<PreviewSessionEntity> existing = repository.findByTaskIdAndStatus(
                taskId,
                PreviewSessionStatus.ACTIVE.name()
        );
        if (existing.isPresent() && dockerService.isContainerRunning(existing.get().getContainerId())) {
            return touch(existing.get()).toInfo();
        }
        existing.ifPresent(session -> expire(session, PreviewSessionStatus.EXPIRED));

        String sessionId = UUID.randomUUID().toString();
        String accessToken = UUID.randomUUID().toString().replace("-", "");
        String containerId = dockerService.createAndStartContainer(
                task.ownerUserId(),
                sessionId,
                task.projectId(),
                task.conversationId(),
                task.taskId()
        );
        int hostPort = dockerService.getMappedPort(containerId);
        String publicUrl = gatewayUrlResolver.publicUrl(sessionId, accessToken);
        PreviewSessionEntity created = new PreviewSessionEntity(
                sessionId,
                accessToken,
                task.ownerUserId(),
                task.projectId(),
                task.conversationId(),
                task.taskId(),
                containerId,
                hostPort,
                publicUrl,
                nextExpiry()
        );
        repository.save(created);
        log.info("[PreviewSession] 생성: sessionId={} taskId={} projectId={} conversationId={}",
                sessionId, taskId, task.projectId(), task.conversationId());
        return created.toInfo();
    }

    @Transactional(readOnly = true)
    public Optional<PreviewSessionInfo> findByTaskId(String taskId) {
        return repository.findByTaskIdAndStatus(taskId, PreviewSessionStatus.ACTIVE.name())
                .map(PreviewSessionEntity::toInfo);
    }

    /**
     * Resolves the "current preview server" for a project — used by the Cloud Ops Agent
     * (STATUS_CHECK/RESTART, EPIC 15 design D8) where the caller has a projectId from chat context
     * but no taskId (the operational request is not itself a CODE/DEPLOY task). Only the most
     * recently touched ACTIVE session is returned: a project can only meaningfully have one "the
     * server" at a time from the user's point of view, and an expired/closed session is correctly
     * "no server running", not a stale ID to act on.
     */
    @Transactional(readOnly = true)
    public Optional<PreviewSessionInfo> findActiveByProject(Long projectId, Long ownerUserId) {
        return repository.findFirstByProjectIdAndOwnerUserIdAndStatusOrderByLastAccessedAtDesc(
                        projectId, ownerUserId, PreviewSessionStatus.ACTIVE.name())
                .map(PreviewSessionEntity::toInfo);
    }

    /**
     * Persists the new mapped host port Docker assigned when a container was restarted (Cloud
     * Ops Agent RESTART, issue #71 — see {@link com.example.dvely.preview.infrastructure.persistence.entity.PreviewSessionEntity#rebindPort}
     * for why this is needed). Looked up by id rather than the taskId/status-scoped finders used
     * elsewhere in this class: the caller (InfraOpsAgentService) already resolved this exact
     * session moments earlier via {@link #findActiveByProject}, so a second ownership/status
     * check here would be redundant — a missing row at this point means the session was closed
     * out-of-band in that narrow window, which is a genuine failure (propagated, not degraded)
     * exactly like {@code DockerContainerService#restartContainer}'s own NotFound handling.
     */
    @Transactional
    public PreviewSessionInfo updateHostPort(String sessionId, int newHostPort) {
        PreviewSessionEntity session = repository.findById(sessionId)
                .orElseThrow(() -> new IllegalStateException("Preview 세션을 찾을 수 없습니다. sessionId=" + sessionId));
        session.rebindPort(newHostPort);
        return repository.save(session).toInfo();
    }

    /**
     * 소유자에게 이 세션의 열람 권한을 발급한다 (Issue #77).
     *
     * <p>두 가지를 한 번에 한다. 하나는 게이트웨이가 요구할 소유권 쿠키를 만들어 주는 것(G2),
     * 다른 하나는 accessToken을 회전시켜 예전 주소를 죽이는 것(G4)이다. 회전이 발급과 같은 호출에
     * 묶인 이유는 호출자가 곧바로 새 주소를 화면에 걸기 때문이다 — 유출된 주소의 수명이 "소유자가
     * 다음에 프리뷰를 여는 시점"으로 제한된다.</p>
     *
     * <p>남의 세션은 존재 여부를 알려주지 않도록 404로 통일한다(기존 close/status API와 같은 계약).
     * 이미 종료·만료된 세션은 열어줄 것이 없으므로 상태 충돌(409)이다.</p>
     */
    @Transactional
    public PreviewAccessGrant grantAccess(String sessionId, Long ownerUserId, Duration cookieValidity) {
        PreviewSessionEntity session = repository.findByIdAndOwnerUserId(sessionId, ownerUserId)
                .orElseThrow(() -> new NotFoundException("PreviewSession을 찾을 수 없습니다. sessionId=" + sessionId));
        if (!PreviewSessionStatus.ACTIVE.name().equals(session.getStatus())) {
            throw new IllegalStateException(
                    "종료되었거나 아직 준비 중인 프리뷰입니다. status=" + session.getStatus());
        }

        String rotatedToken = UUID.randomUUID().toString().replace("-", "");
        session.rotateAccess(rotatedToken, gatewayUrlResolver.publicUrl(sessionId, rotatedToken));
        PreviewSessionEntity saved = repository.save(session);
        log.info("[PreviewSession] 접근 발급 및 토큰 회전: sessionId={} ownerUserId={}", sessionId, ownerUserId);

        // 쿠키가 세션보다 오래 살아남을 이유가 없다 — 세션이 끝나면 어차피 게이트웨이가 열리지 않는다.
        Duration untilExpiry = Duration.between(LocalDateTime.now(), saved.getExpiresAt());
        Duration maxAge = untilExpiry.compareTo(cookieValidity) < 0 ? untilExpiry : cookieValidity;
        return new PreviewAccessGrant(
                sessionId,
                saved.getPublicUrl(),
                saved.getExpiresAt(),
                accessCookies.issue(sessionId, ownerUserId, maxAge),
                accessCookies.cookiePath(sessionId),
                maxAge
        );
    }

    @Transactional
    public Optional<PreviewSessionInfo> resolveGateway(String sessionId, String accessToken) {
        return repository.findByIdAndAccessTokenAndStatus(
                        sessionId,
                        accessToken,
                        PreviewSessionStatus.ACTIVE.name()
                )
                .filter(session -> session.getExpiresAt().isAfter(LocalDateTime.now()))
                .map(this::touch)
                .map(PreviewSessionEntity::toInfo);
    }

    @Transactional
    public boolean closeOwned(String sessionId, Long ownerUserId) {
        PreviewSessionEntity session = repository.findByIdAndOwnerUserId(sessionId, ownerUserId)
                .orElse(null);
        if (session == null || !PreviewSessionStatus.ACTIVE.name().equals(session.getStatus())) {
            return false;
        }
        expire(session, PreviewSessionStatus.CLOSED);
        return true;
    }

    @Transactional
    public int closeAllOwned(Long ownerUserId) {
        List<PreviewSessionEntity> sessions = repository.findByOwnerUserIdAndStatus(
                ownerUserId,
                PreviewSessionStatus.ACTIVE.name()
        );
        sessions.forEach(session -> expire(session, PreviewSessionStatus.CLOSED));
        return sessions.size();
    }

    /**
     * TTL 을 넘긴 세션의 컨테이너를 회수한다.
     *
     * <p>PROVISIONING 도 함께 본다: 프로젝트 단위 프리뷰는 clone/install/build 를 비동기로 돌기
     * 때문에, 그 도중 앱이 재시작되면 ACTIVE 로도 FAILED 로도 가지 못한 행이 컨테이너를 붙든 채
     * 남는다. 만료 시각이 지나도록 준비가 끝나지 않았다는 것은 그 준비가 더 이상 진행되고 있지
     * 않다는 뜻이므로(TTL 30분 &gt; 정상 프로비저닝 시간), EXPIRED 가 아니라 FAILED 로 닫아
     * "왜 안 떴는지"를 FE 가 그대로 보여줄 수 있게 한다.</p>
     */
    @Scheduled(fixedDelayString = "${qeploy.preview.cleanup-interval-ms:60000}")
    @Transactional
    public void cleanupExpired() {
        repository.findByStatusInAndExpiresAtBefore(
                        List.of(PreviewSessionStatus.ACTIVE.name(), PreviewSessionStatus.PROVISIONING.name()),
                        LocalDateTime.now()
                )
                .forEach(session -> {
                    if (PreviewSessionStatus.PROVISIONING.name().equals(session.getStatus())) {
                        session.markFailed("프리뷰 준비가 제한 시간 안에 끝나지 않았습니다. 다시 시도해주세요.");
                        repository.save(session);
                        dockerService.removeContainer(session.getContainerId());
                        log.warn("[PreviewSession] 준비 미완료로 정리: sessionId={}", session.getId());
                        return;
                    }
                    expire(session, PreviewSessionStatus.EXPIRED);
                });
    }

    private PreviewSessionEntity touch(PreviewSessionEntity session) {
        session.touch(nextExpiry());
        return repository.save(session);
    }

    private void expire(PreviewSessionEntity session, PreviewSessionStatus status) {
        session.close(status);
        repository.save(session);
        dockerService.removeContainer(session.getContainerId());
        log.info("[PreviewSession] 종료: sessionId={} status={}", session.getId(), status);
    }

    private LocalDateTime nextExpiry() {
        return LocalDateTime.now().plus(properties.getTtl());
    }

}
