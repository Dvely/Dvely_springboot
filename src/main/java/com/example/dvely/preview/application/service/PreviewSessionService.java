package com.example.dvely.preview.application.service;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.preview.application.result.PreviewAccessGrant;
import com.example.dvely.preview.application.result.PreviewSessionInfo;
import com.example.dvely.preview.domain.value.PreviewRuntimeType;
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
    private final PreviewRuntimeConfigService runtimeConfigService;

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
        // JAVA_FULLSTACK 은 JVM+gradle 이 무거워 큰 컨테이너가 필요하다. 저장된 런타임 타입이
        // JAVA 인 프로젝트만 큰 메모리로 만든다(자동 감지는 클론 후라 늦다 — 미리 설정해야 받는다).
        long memoryBytes = task.projectId() != null
                && runtimeConfigService.storedRuntimeType(task.projectId())
                        .filter(type -> type == PreviewRuntimeType.JAVA_FULLSTACK).isPresent()
                ? DockerContainerService.JAVA_MEMORY_LIMIT_BYTES
                : DockerContainerService.MEMORY_LIMIT_BYTES;
        String containerId = dockerService.createAndStartContainer(
                task.ownerUserId(),
                sessionId,
                task.projectId(),
                task.conversationId(),
                task.taskId(),
                memoryBytes
        );
        int hostPort = dockerService.getMappedPort(containerId);
        String publicUrl = gatewayUrlResolver.publicUrl(sessionId, accessToken);
        // PROVISIONING 으로 시작한다. 컨테이너는 떴지만 그 안의 서버는 아직 없다 — CodeAgentService
        // 가 LLM 작업과 빌드를 마친 뒤에야 startPreviewServer 를 부른다. ACTIVE 로 만들어두면
        // "열면 보인다"는 계약을 어기게 되고, 그 사이 iframe 을 붙인 FE 는 502 만 본다.
        // markServing 이 그 계약을 지키는 지점이다.
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
                nextExpiry(),
                PreviewSessionStatus.PROVISIONING
        );
        repository.save(created);
        log.info("[PreviewSession] 생성: sessionId={} taskId={} projectId={} conversationId={}",
                sessionId, taskId, task.projectId(), task.conversationId());
        return created.toInfo();
    }

    /**
     * 컨테이너 안의 서버가 실제로 뜬 뒤에 부른다. 이때부터 게이트웨이가 프록시를 열어주고,
     * FE 도 ACTIVE 를 보고 iframe 을 붙인다.
     *
     * acquire 가 PROVISIONING 으로 만들어두기 때문에 이 호출이 없으면 프리뷰는 영영 안 열린다.
     * 반대로 서버가 뜨기 전에 부르면 예전처럼 502 를 보게 된다.
     */
    @Transactional
    public void markServing(String taskId) {
        repository.findByTaskIdAndStatus(taskId, PreviewSessionStatus.PROVISIONING.name())
                .ifPresent(session -> {
                    session.activate(nextExpiry());
                    repository.save(session);
                    log.info("[PreviewSession] 서빙 시작: sessionId={} taskId={}", session.getId(), taskId);
                });
    }

    /**
     * 서버를 띄우지 못했을 때. PROVISIONING 인 채로 두면 FE 가 준비 중 화면을 무한히 돌리므로
     * 실패를 명시하고 사유를 남긴다 — FE 는 그 문자열을 그대로 사용자에게 보여준다.
     *
     * 컨테이너는 남겨둔다. 태스크 실패 후 로그를 확인할 수 있어야 하고, TTL 이 지나면
     * cleanupExpired 가 정리한다.
     */
    @Transactional
    public void markServeFailed(String taskId, String reason) {
        repository.findByTaskIdAndStatus(taskId, PreviewSessionStatus.PROVISIONING.name())
                .ifPresent(session -> {
                    session.markFailed(reason);
                    repository.save(session);
                    log.warn("[PreviewSession] 서빙 실패: sessionId={} taskId={} reason={}",
                            session.getId(), taskId, reason);
                });
    }

    @Transactional(readOnly = true)
    public Optional<PreviewSessionInfo> findByTaskId(String taskId) {
        return repository.findByTaskIdAndStatus(taskId, PreviewSessionStatus.ACTIVE.name())
                .map(PreviewSessionEntity::toInfo);
    }

    /**
     * 저장소 연결 승인이 열린 태스크의 세션 만료를 뒤로 미룬다.
     *
     * <p>TTL 은 게이트웨이 접근마다 {@code touch} 로 갱신되는데, 승인 카드만 보고 결정하는
     * 사용자는 프리뷰를 한 번도 열지 않는다. 그러면 사람이 답하기를 기다리는 동안 아무도 세션을
     * 건드리지 않아 30분에 회수되고, 아직 GitHub 에 올라가지 않은 작업물이 컨테이너와 함께
     * 사라진다(2026-08-18 운영에서 실제로 발생 — 승인이 09:04 에 열리고 세션은 09:34 에 만료).</p>
     *
     * <p>현재 만료가 이미 더 뒤라면 손대지 않는다 — 사용자가 프리뷰를 계속 보고 있어 TTL 이
     * 갱신되고 있는 경우 그 갱신을 이 호출이 되돌리면 안 된다.</p>
     */
    @Transactional
    public void holdForBindingApproval(String taskId) {
        repository.findByTaskIdAndStatus(taskId, PreviewSessionStatus.ACTIVE.name())
                .ifPresent(session -> {
                    LocalDateTime until = LocalDateTime.now().plus(properties.getBindingApprovalHold());
                    if (!until.isAfter(session.getExpiresAt())) {
                        return;
                    }
                    session.touch(until);
                    repository.save(session);
                    log.info("[PreviewSession] 저장소 연결 승인 대기로 만료 연장: taskId={} until={}", taskId, until);
                });
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

    /**
     * 접근이 있었으니 만료를 미룬다 — 단, <em>앞당기지는 않는다.</em>
     *
     * <p>예전에는 {@code nextExpiry()} 를 그대로 넣었다. 그러면 이미 걸려 있는 더 먼 만료가
     * {@code now + ttl} 로 되돌아간다. {@link #holdForBindingApproval} 이 준 유예가 정확히 그
     * 피해자였다 — 게이트가 승인을 열며 유예를 걸어도, 바로 뒤에 FE 가 프리뷰를 자동으로 띄우면서
     * 게이트웨이 접근이 한 번 일어나 유예가 통째로 지워졌다. 유예는 프리뷰를 한 번도 열지 않았을
     * 때만 살아남는 셈이었다(2026-08-18 운영 실측: 유예 로그는 12:13:34 에 찍혔는데 만료는
     * 12:43 — 30분 뒤였다).</p>
     */
    private PreviewSessionEntity touch(PreviewSessionEntity session) {
        LocalDateTime next = nextExpiry();
        session.touch(next.isAfter(session.getExpiresAt()) ? next : session.getExpiresAt());
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
