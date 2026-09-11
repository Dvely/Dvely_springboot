package com.example.dvely.preview.application.service;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.preview.application.port.out.DeadPreviewSessionReclaimer;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PreviewSessionService implements DeadPreviewSessionReclaimer {

    /**
     * 게이트웨이 접근의 만료 연장을 이 간격으로 묶는다 (Issue #342, 7-1). 프리뷰 페이지 한 번의
     * 로드가 자산 수만큼 같은 행을 UPDATE 하던 것을 이 간격당 한 번으로 줄인다.
     */
    private static final Duration TOUCH_THROTTLE = Duration.ofSeconds(60);

    private final SpringDataPreviewSessionRepository repository;
    private final DockerContainerService dockerService;
    private final TaskStore taskStore;
    private final PreviewProperties properties;
    private final PreviewGatewayUrlResolver gatewayUrlResolver;
    private final PreviewAccessCookies accessCookies;
    private final PreviewRuntimeConfigService runtimeConfigService;

    // 필드 주입(null-safe). 단위 테스트는 Spring 없이 생성자로 만드므로 null 이면 도달 게이트를 건너뛴다.
    @Autowired(required = false)
    private PreviewReadinessProbe readinessProbe;

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
        long memoryBytes = runtimeConfigService.previewContainerMemoryBytes(task.projectId());
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
    public void markServing(String taskId) {
        PreviewSessionEntity session = repository
                .findByTaskIdAndStatus(taskId, PreviewSessionStatus.PROVISIONING.name())
                .orElse(null);
        if (session == null) {
            return;
        }
        // 런타임 준비(서버형의 DB 자동 프로비저닝→세션 네트워크 연결)가 컨테이너의 :0 랜덤 발행
        // 포트를 재할당해, 생성 시점에 저장한 host_port 가 어긋날 수 있다. 게이트웨이가 이 포트로
        // 프록시하므로, ACTIVE 로 올리기 직전 지금의 실제 포트로 다시 맞춘다(어긋나면 502).
        //
        // Docker 조회와 도달 확인을 저장보다 먼저, 트랜잭션 밖에서 끝낸다 — 도달 확인은 앱이 뜰
        // 때까지 기다리는 호출이라 트랜잭션 안에 두면 그 시간만큼 커넥션이 묶였다(#337). 둘 중
        // 하나라도 던지면 아래 저장에 도달하지 않아 세션은 PROVISIONING 으로 남는다(예전 롤백과 같은 결과).
        int hostPort = dockerService.getMappedPort(session.getContainerId());
        // ACTIVE 직전 게이트웨이 경유 도달 확인 — 첫 iframe 로드의 503(깨진 이미지) 레이스를 닫는다.
        if (readinessProbe != null) {
            readinessProbe.awaitReachable(hostPort);
        }
        session.rebindPort(hostPort);
        session.activate(nextExpiry());
        repository.save(session);
        log.info("[PreviewSession] 서빙 시작: sessionId={} taskId={} hostPort={}",
                session.getId(), taskId, session.getHostPort());
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

    /**
     * 게이트웨이가 요청마다 부르는 세션 조회.
     *
     * <p>여기서 엔티티를 <b>고치지 않는다</b>는 것이 7-1 의 핵심이다(Issue #342). 예전에는 조회한
     * 엔티티를 {@code touch} 로 고쳐 {@code save} 했고, 그 결과 프리뷰 페이지가 끌어오는 자산
     * 하나하나(JS/CSS/이미지)마다 이 행에 더티 체크 UPDATE 와 쓰기 락이 걸렸다 — 자산이 N 개인
     * 페이지 한 번에 UPDATE N 번이다. 이제 갱신은 {@link #touchThrottled} 가 스로틀을 통과할 때만
     * 단일 UPDATE 로 나간다.</p>
     *
     * <p>세션 조회 자체는 <b>캐시하지 않는다.</b> accessToken 은 소유자가 프리뷰를 다시 열 때마다
     * 회전하고(이전 주소는 그 순간 404) 그 판정이 이 조회다. 캐시를 두면 회전이 다음 만료까지 미뤄져
     * 유출된 주소의 수명을 늘리게 된다 — 줄일 수 있는 것은 쓰기뿐이다.</p>
     */
    @Transactional
    public Optional<PreviewSessionInfo> resolveGateway(String sessionId, String accessToken) {
        return repository.findByIdAndAccessTokenAndStatus(
                        sessionId,
                        accessToken,
                        PreviewSessionStatus.ACTIVE.name()
                )
                .filter(session -> session.getExpiresAt().isAfter(LocalDateTime.now()))
                .map(session -> {
                    touchThrottled(session);
                    // 갱신을 벌크 UPDATE 로 보냈으므로 이 엔티티의 expiresAt 은 갱신 전 값이다.
                    // 게이트웨이는 sessionId·ownerUserId·hostPort 만 쓰므로 문제가 없고, 만료를
                    // 응답에 싣는 경로(findCurrent·grantAccess)는 각자 따로 읽는다.
                    return session.toInfo();
                });
    }

    /**
     * 게이트웨이가 컨테이너 안쪽 앱에 도달하지 못했을 때({@link DeadPreviewSessionReclaimer}) 그 ACTIVE
     * 세션을 회수한다 — EXPIRED 로 닫고 컨테이너를 제거한다. 컨테이너는 살아있어도(그래서 attach·findCurrent
     * 는 못 걸러냄) 안쪽 서버가 죽어 502 만 나오는 세션을, "컨테이너가 사라진" 상태로 바꿔 {@code findCurrent}
     * 의 self-heal 에 태운다 — FE 는 다음 폴링에서 "없음"을 보고 새 빌드 CTA 로 복귀한다.
     *
     * <p>ACTIVE 가 아니면(다른 요청이 방금 회수했거나 만료) no-op — 동시 실패 요청이 여럿 들어와도 회수는
     * 사실상 1회다({@code expire} 가 상태를 바꾸고 {@code removeContainer} 는 멱등). 게이트웨이만 호출하므로
     * (host-affine) 이 판정은 항상 로컬 컨테이너에 대한 것이다.</p>
     */
    @Override
    public boolean reclaimUnreachable(String sessionId) {
        PreviewSessionEntity session = repository.findById(sessionId).orElse(null);
        if (session == null || !PreviewSessionStatus.ACTIVE.name().equals(session.getStatus())) {
            return false;
        }
        log.warn("[PreviewSession] 안쪽 앱 무응답으로 회수(컨테이너는 살아있으나 서버 프로세스가 죽음): sessionId={} projectId={}",
                session.getId(), session.getProjectId());
        expire(session, PreviewSessionStatus.EXPIRED);
        return true;
    }

    public boolean closeOwned(String sessionId, Long ownerUserId) {
        PreviewSessionEntity session = repository.findByIdAndOwnerUserId(sessionId, ownerUserId)
                .orElse(null);
        if (session == null || !PreviewSessionStatus.ACTIVE.name().equals(session.getStatus())) {
            return false;
        }
        expire(session, PreviewSessionStatus.CLOSED);
        return true;
    }

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
    public void cleanupExpired() {
        for (PreviewSessionEntity session : repository.findByStatusInAndExpiresAtBefore(
                List.of(PreviewSessionStatus.ACTIVE.name(), PreviewSessionStatus.PROVISIONING.name()),
                LocalDateTime.now())) {
            // 건별로 막는다. 예전에는 배치 전체가 트랜잭션 하나라 한 건의 Docker 실패가 나머지
            // 세션의 정리까지 통째로 롤백시켰다(#337) — 컨테이너 하나가 고장나면 만료 정리가
            // 영영 진행되지 않는 구조였다.
            try {
                if (PreviewSessionStatus.PROVISIONING.name().equals(session.getStatus())) {
                    // 제거를 먼저 — expire() 와 같은 이유로, 실패하면 PROVISIONING 으로 남아 다음 주기에 다시 본다.
                    dockerService.removeContainer(session.getContainerId());
                    session.markFailed("프리뷰 준비가 제한 시간 안에 끝나지 않았습니다. 다시 시도해주세요.");
                    repository.save(session);
                    log.warn("[PreviewSession] 준비 미완료로 정리: sessionId={}", session.getId());
                    continue;
                }
                expire(session, PreviewSessionStatus.EXPIRED);
            } catch (RuntimeException exception) {
                log.warn("[PreviewSession] 만료 정리 실패(다음 주기 재시도): sessionId={} 원인={}",
                        session.getId(), exception.toString());
            }
        }
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
        session.touch(keepFurther(nextExpiry(), session));
        return repository.save(session);
    }

    /**
     * 게이트웨이 접근의 만료 연장 — {@link #TOUCH_THROTTLE} 안에 이미 갱신됐으면 건너뛴다 (7-1).
     *
     * <p>"문서 탐색({@code Sec-Fetch-Dest})일 때만 갱신" 대신 시간 스로틀을 고른 이유는 두 가지다.
     * 하나는 <b>동작 보존</b>이다 — 문서 탐색만 갱신하면, 열어둔 프리뷰가 XHR/SSE 로만 계속 쓰이는
     * 동안에는 연장이 끊겨 사용 중인 세션이 만료된다. 다른 하나는 <b>보안 경계</b>다: {@code
     * Sec-Fetch-Dest} 는 게이트웨이의 인가 판정(문서 탐색에만 소유권 쿠키 요구)이 쓰는 신호이므로,
     * 세션 계층이 같은 헤더를 갱신 정책에 쓰기 시작하면 두 판정이 한 입력에 얽힌다.</p>
     *
     * <p>TTL 은 30 분이므로 60 초 스로틀이 실제로 깎는 연장은 최대 60 초다. 그 대가로 자산 N 개의
     * UPDATE N 번이 60 초당 1 번이 된다.</p>
     */
    private void touchThrottled(PreviewSessionEntity session) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleBefore = now.minus(TOUCH_THROTTLE);
        if (!session.getLastAccessedAt().isBefore(staleBefore)) {
            return;
        }
        repository.touchAccess(session.getId(), now, keepFurther(nextExpiry(), session), staleBefore);
    }

    /** 이미 걸려 있는 만료가 더 멀면 그것을 유지한다(유예 보존). */
    private static LocalDateTime keepFurther(LocalDateTime next, PreviewSessionEntity session) {
        return next.isAfter(session.getExpiresAt()) ? next : session.getExpiresAt();
    }

    /**
     * 컨테이너 제거를 저장보다 <b>먼저</b> 한다. 예전에는 저장이 먼저였고, 제거가 실패하면
     * 트랜잭션 롤백이 상태 변경까지 되돌려 세션이 ACTIVE 로 남아 다음 기회에 다시 회수됐다.
     * 호출부에서 트랜잭션을 걷어낸 지금(#337) 그 성질은 롤백이 아니라 순서로만 지킬 수 있다 —
     * 제거가 던지면 저장에 도달하지 않으므로 결과가 이전과 같다.
     *
     * <p>제거와 저장 사이에는 "컨테이너는 없는데 행은 아직 ACTIVE" 인 짧은 창이 생기지만,
     * 그 상태의 세션은 게이트웨이가 도달 실패로 보고 {@link #reclaimUnreachable} 이 다시
     * 회수한다({@code removeContainer} 는 멱등).</p>
     */
    private void expire(PreviewSessionEntity session, PreviewSessionStatus status) {
        dockerService.removeContainer(session.getContainerId());
        session.close(status);
        repository.save(session);
        log.info("[PreviewSession] 종료: sessionId={} status={}", session.getId(), status);
    }

    private LocalDateTime nextExpiry() {
        return LocalDateTime.now().plus(properties.getTtl());
    }

}
