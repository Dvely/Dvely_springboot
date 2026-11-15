package com.example.dvely.preview.application.service;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.preview.application.result.InUsePreview;
import com.example.dvely.preview.domain.value.PreviewSessionStatus;
import com.example.dvely.preview.infrastructure.persistence.entity.PreviewSessionEntity;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 프리뷰를 닫아도 되는지 — 저절로 닫을 때(상한 회수, 게이트웨이의 무응답 회수 #678)와 사람이 닫을 때(#650)의 규칙을 한 곳에
 * 둔다.
 *
 * <p>작업 세션의 작업물은 그 컨테이너 안에만 있다. push 를 결과 승인 뒤로 미룬 작업(#523)은 컨테이너가 유일한 사본이고,
 * 진행 중인 작업은 다음 스텝이 그 작업물을 이어 쓴다. 닫으면 되살릴 수 없으므로, 작업이 끝났거나(DONE·CANCELLED) 실패한
 * 것(FAILED)만 닫는다.</p>
 *
 * <p>둘의 차이는 FAILED 하나다. 빌드 실패 복구의 재시도가 같은 컨테이너의 작업물을 이어 쓰므로 저절로는 닫지 않는다.
 * 사람은 닫을 수 있다 — 대신 재시도가 처음부터 시작된다(목록이 그것을 경고한다, #651).</p>
 *
 * <p>위 집합에 없는 상태는 모두 잠긴 것으로 본다. 상태가 새로 생겨도 그 프리뷰는 닫히지 않는 쪽으로 틀린다.</p>
 */
public final class PreviewClosePolicy {

    /** 저절로 닫아도 되는 작업 상태. {@link PreviewCapacity} 와 게이트웨이의 무응답 회수가 쓴다. */
    static final Set<TaskStatus> RECLAIMABLE = EnumSet.of(TaskStatus.DONE, TaskStatus.CANCELLED);

    /** 주인이 닫아도 되는 작업 상태. */
    static final Set<TaskStatus> CLOSABLE_BY_OWNER = EnumSet.of(TaskStatus.DONE, TaskStatus.CANCELLED, TaskStatus.FAILED);

    private PreviewClosePolicy() {
    }

    /** 상한 회수가 이 작업의 프리뷰를 닫아도 되는가. 런 행이 없으면 이어 쓸 작업도 없다. */
    static boolean reclaimable(AgentTask task) {
        return task == null || RECLAIMABLE.contains(task.status());
    }

    /**
     * 게이트웨이가 안쪽 앱 무응답으로 이 세션을 회수하려 할 때 막는 작업 상태(#678). 막지 않으면 비어 있다.
     *
     * <p>죽은 것은 앱 프로세스뿐이고 컨테이너 안의 작업물(git)은 멀쩡하다. 회수는 컨테이너를 지우므로 작업 세션에는 상한
     * 회수와 같은 규칙을 쓴다 — 잠긴 작업의 세션을 남겨 두어야 결과 승인·거절과 재시도가 그 작업물을 쓴다.</p>
     *
     * <p>프로젝트 프리뷰(작업 없음)는 막지 않는다. 브랜치를 새로 띄우면 같은 것이 돌아오고, 회수해야 FE 가 새 빌드 버튼으로
     * 돌아간다. 상한 회수가 프로젝트 프리뷰를 닫지 않는 것과 다르다 — 그쪽은 사용자가 보려고 띄운 살아 있는 프리뷰를 남의 자리
     * 때문에 닫지 않으려는 것이고, 여기는 이미 응답하지 않는 프리뷰다.</p>
     *
     * @param tasks 작업 id 로 런을 읽는다. 작업 세션일 때만 부른다
     */
    static Optional<TaskStatus> blockingUnreachableReclaim(PreviewSessionEntity session,
                                                           Function<String, AgentTask> tasks) {
        if (session.getTaskId() == null) {
            return Optional.empty();
        }
        AgentTask task = tasks.apply(session.getTaskId());
        return reclaimable(task) ? Optional.empty() : Optional.of(task.status());
    }

    /**
     * 주인이 이 세션을 닫으려 할 때 막는 작업. 막지 않으면 비어 있다.
     *
     * <p>프로젝트 프리뷰(작업 없음)와 런 행이 없는 세션은 막지 않는다.</p>
     *
     * @param tasks 작업 id 로 런을 읽는다. 작업 세션일 때만 부른다
     */
    static Optional<InUsePreview> blockingOwnerClose(PreviewSessionEntity session, Function<String, AgentTask> tasks) {
        if (session.getTaskId() == null) {
            return Optional.empty();
        }
        AgentTask task = tasks.apply(session.getTaskId());
        if (task == null || !blocksOwnerClose(task.status())) {
            return Optional.empty();
        }
        return Optional.of(new InUsePreview(session.getId(), session.getProjectId(), session.getTaskId(), task.status()));
    }

    /**
     * 내 프리뷰 목록(#651)이 보여 줄, 주인이 지금 닫을 수 없는 이유. 닫을 수 있으면 비어 있다.
     *
     * <p>{@code DELETE /preview-sessions/{id}} 와 같은 판정이다. 작업이 막으면 그 작업 상태({@code WAITING_RESULT_APPROVAL}
     * 등, 409)가 이유다. 작업이 막지 않아도 준비 중({@code PROVISIONING})인 세션은 그 경로가 닫지 않으므로(404)
     * {@code PROVISIONING} 이 이유다.</p>
     *
     * @param taskStatus 작업 세션의 작업 상태. 프로젝트 프리뷰이거나 런 행이 없으면 null
     */
    static Optional<String> closeBlockedReason(String sessionStatus, TaskStatus taskStatus) {
        if (blocksOwnerClose(taskStatus)) {
            return Optional.of(taskStatus.name());
        }
        if (PreviewSessionStatus.PROVISIONING.name().equals(sessionStatus)) {
            return Optional.of(PreviewSessionStatus.PROVISIONING.name());
        }
        return Optional.empty();
    }

    /** 이 작업 상태가 주인의 닫기를 막는가. null(작업 없음·런 행 없음)은 막지 않는다. */
    private static boolean blocksOwnerClose(TaskStatus taskStatus) {
        return taskStatus != null && !CLOSABLE_BY_OWNER.contains(taskStatus);
    }
}
