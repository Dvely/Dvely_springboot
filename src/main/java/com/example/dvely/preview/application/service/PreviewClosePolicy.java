package com.example.dvely.preview.application.service;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.preview.application.result.InUsePreview;
import com.example.dvely.preview.infrastructure.persistence.entity.PreviewSessionEntity;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 프리뷰를 닫아도 되는지 — 저절로 닫을 때(상한 회수)와 사람이 닫을 때(#650)의 규칙을 한 곳에 둔다.
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

    /** 저절로 닫아도 되는 작업 상태. {@link PreviewCapacity} 가 쓴다. */
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
        if (task == null || CLOSABLE_BY_OWNER.contains(task.status())) {
            return Optional.empty();
        }
        return Optional.of(new InUsePreview(session.getId(), session.getProjectId(), session.getTaskId(), task.status()));
    }
}
