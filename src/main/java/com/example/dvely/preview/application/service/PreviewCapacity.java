package com.example.dvely.preview.application.service;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.common.limit.GlobalLimitProperties;
import com.example.dvely.common.limit.UserLimitExceededException;
import com.example.dvely.common.limit.UserLimitProperties;
import com.example.dvely.common.response.ErrorCode;
import com.example.dvely.preview.domain.value.PreviewSessionStatus;
import com.example.dvely.preview.infrastructure.persistence.entity.PreviewSessionEntity;
import com.example.dvely.preview.infrastructure.persistence.repository.SpringDataPreviewSessionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 살아 있는 프리뷰 상한 — 사용자별(#509)과 서버 전체(#624). 세션마다 컨테이너가 하나(메모리 상한 1 GiB, JAVA_FULLSTACK
 * 2 GiB)라 한 사용자가 많이 띄우거나 사용자가 많아지면 서버 메모리가 먼저 닿는다.
 *
 * <p>사용자 상한을 먼저 본다. 자기 끝난 작업의 프리뷰로 자리가 나면 남의 것은 건드리지 않는다. 서버 전체가 찼으면 끝난
 * 작업의 프리뷰를 <b>사용자와 상관없이</b> 오래 안 본 순서로 닫는다 — 끝난 작업의 프리뷰는 원래 자리가 필요하면 닫히는
 * 것이고, 그것이 남의 것이라고 서버 자리를 붙들게 두면 다들 TTL 이 끝날 때까지 막힌다.</p>
 *
 * <p>새 컨테이너를 띄우는 두 곳 — 작업 CODE 스텝({@link PreviewSessionService#acquire})과 프로젝트 프리뷰
 * ({@link ProjectPreviewService#provision}) — 이 띄우기 전에 {@link #makeRoom}, 행을 저장한 뒤 {@link #confirm} 을
 * 부른다. 이미 떠 있는 세션에 붙는 경우는 컨테이너가 늘지 않으므로 부르지 않는다.</p>
 *
 * <p>트랜잭션을 걸지 않는다. 저장소 호출마다 새 트랜잭션이라 다른 요청이 커밋한 행을 그때그때 본다 —
 * {@link #confirm} 이 그 성질에 기댄다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PreviewCapacity {

    private static final List<String> LIVE_STATUSES = List.of(
            PreviewSessionStatus.ACTIVE.name(),
            PreviewSessionStatus.PROVISIONING.name()
    );

    private final SpringDataPreviewSessionRepository repository;
    private final DockerContainerService dockerService;
    private final TaskStore taskStore;
    private final UserLimitProperties limits;
    private final GlobalLimitProperties globalLimits;

    /**
     * 새 프리뷰 하나가 들어갈 자리를 만든다. 자리가 없으면 끝난 작업의 프리뷰를 오래 안 본 순서로 닫는다. 그래도 없으면
     * 던진다 — 남은 것은 사용자가 직접 연 프로젝트 프리뷰, 진행 중이거나 결정을 기다리는 작업의 프리뷰다.
     *
     * <p>사용자 상한에 걸리면 {@code PREVIEW_LIMIT_EXCEEDED}, 서버 전체에 걸리면 {@code PREVIEW_CAPACITY_EXCEEDED} 다.</p>
     */
    public void makeRoom(Long ownerUserId) {
        int mine = reclaimUntilBelow(
                repository.findByOwnerUserIdAndStatusInOrderByLastAccessedAtAsc(ownerUserId, LIVE_STATUSES),
                limits.maxLivePreviews(), "사용자");
        if (mine >= limits.maxLivePreviews()) {
            throw userLimitExceeded(mine);
        }
        int all = reclaimUntilBelow(
                repository.findByStatusInOrderByLastAccessedAtAsc(LIVE_STATUSES),
                globalLimits.maxLivePreviews(), "서버");
        if (all >= globalLimits.maxLivePreviews()) {
            log.warn("[PreviewCapacity] 서버 전체 프리뷰 상한: live={} max={} userId={}",
                    all, globalLimits.maxLivePreviews(), ownerUserId);
            throw serverCapacityExceeded();
        }
    }

    /** 오래 안 본 순서로 읽은 {@code live} 에서 끝난 작업의 프리뷰를 닫아 {@code max} 아래로 내린다. 남은 수를 돌려준다. */
    private int reclaimUntilBelow(List<PreviewSessionEntity> live, int max, String scope) {
        int remaining = live.size();
        for (PreviewSessionEntity session : live) {
            if (remaining < max) {
                break;
            }
            if (isReclaimable(session)) {
                close(session, PreviewSessionStatus.EXPIRED);
                remaining--;
                log.info("[PreviewCapacity] {} 상한으로 회수: sessionId={} taskId={} ownerUserId={}",
                        scope, session.getId(), session.getTaskId(), session.getOwnerUserId());
            }
        }
        return remaining;
    }

    /**
     * 행을 저장한 뒤 다시 센다. 같은 사용자의 요청이(서버 전체로는 여러 사용자의 요청이) 동시에 들어오면 둘 다
     * {@link #makeRoom} 에서 빈자리를 보고 띄울 수 있다. 넘쳤으면 방금 만든 세션을 닫고 던진다.
     *
     * <p>각 요청은 자기 행을 저장한 <em>뒤에</em> 센다. 그래서 마지막으로 센 요청은 앞선 요청들의 행을 모두 보고, 넘친
     * 채로 남는 일은 없다. 정말 동시에 몰리면 둘 다 물러날 수는 있다 — 다시 시도하면 된다.</p>
     */
    public void confirm(Long ownerUserId, PreviewSessionEntity created) {
        long mine = repository.countByOwnerUserIdAndStatusIn(ownerUserId, LIVE_STATUSES);
        if (mine > limits.maxLivePreviews()) {
            backOut(created, ownerUserId, "사용자");
            throw userLimitExceeded(mine - 1);
        }
        long all = repository.countByStatusIn(LIVE_STATUSES);
        if (all > globalLimits.maxLivePreviews()) {
            backOut(created, ownerUserId, "서버");
            throw serverCapacityExceeded();
        }
    }

    private void backOut(PreviewSessionEntity created, Long ownerUserId, String scope) {
        close(created, PreviewSessionStatus.CLOSED);
        log.info("[PreviewCapacity] 동시 요청으로 {} 상한 초과 — 방금 만든 세션을 닫음: sessionId={} userId={}",
                scope, created.getId(), ownerUserId);
    }

    private boolean isReclaimable(PreviewSessionEntity session) {
        if (!PreviewSessionStatus.ACTIVE.name().equals(session.getStatus()) || session.getTaskId() == null) {
            return false;
        }
        // 저절로 닫는 것은 끝난 작업의 프리뷰뿐이다. 규칙은 사람이 닫을 때와 함께 PreviewClosePolicy 에 있다(#650).
        return PreviewClosePolicy.reclaimable(taskStore.get(session.getTaskId()));
    }

    private void close(PreviewSessionEntity session, PreviewSessionStatus status) {
        // 제거를 먼저 — 실패하면 행이 살아 있는 채로 남아 TTL 정리가 다시 본다(PreviewSessionService#expire 와 같은 순서).
        dockerService.removeContainer(session.getContainerId());
        session.close(status);
        repository.save(session);
    }

    private UserLimitExceededException userLimitExceeded(long live) {
        return new UserLimitExceededException(
                ErrorCode.PREVIEW_LIMIT_EXCEEDED,
                "열린 프리뷰가 " + live + "개라 새 프리뷰를 띄울 수 없습니다(사용자당 " + limits.maxLivePreviews()
                        + "개). 쓰지 않는 프리뷰를 닫거나, 결과 승인을 기다리는 작업을 결정한 뒤 다시 시도해주세요.");
    }

    // 서버 전체 수는 다른 사용자의 활동이라 알려 주지 않는다. 이 사용자가 할 수 있는 일만 적는다.
    private UserLimitExceededException serverCapacityExceeded() {
        return new UserLimitExceededException(
                ErrorCode.PREVIEW_CAPACITY_EXCEEDED,
                "지금은 서버의 프리뷰 자리가 모두 차 있어 새 프리뷰를 띄울 수 없습니다. 잠시 뒤 다시 시도하거나, "
                        + "열어 둔 프리뷰가 있으면 닫은 뒤 다시 시도해주세요.");
    }
}
