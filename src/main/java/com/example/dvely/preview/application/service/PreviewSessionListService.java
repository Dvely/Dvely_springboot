package com.example.dvely.preview.application.service;

import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.common.limit.UserLimitProperties;
import com.example.dvely.preview.application.result.PreviewSessionListResult;
import com.example.dvely.preview.domain.value.PreviewSessionStatus;
import com.example.dvely.preview.infrastructure.persistence.entity.PreviewSessionEntity;
import com.example.dvely.preview.infrastructure.persistence.repository.SpringDataPreviewSessionRepository;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 살아 있는 프리뷰(ACTIVE·PROVISIONING) 목록(#651).
 *
 * <p>상한(#509)에 걸려도 자동 회수가 닫지 않고 남는 것은 사용자가 띄운 프로젝트 프리뷰와, 진행 중이거나 결정을 기다리는
 * 작업의 프리뷰다. 사용자에게 보여 줘야 할 것이 바로 그 둘이다. 항목마다 지금 닫을 수 있는지와 이유를 닫기 경로(#650)와
 * <b>같은 규칙</b>({@link PreviewClosePolicy})으로 계산한다 — FE 가 상태 목록을 따로 들고 있지 않게.</p>
 *
 * <p><b>Docker 를 부르지 않는다.</b> 항목마다 inspect 하면 목록 하나에 수십~수백 ms 가 붙는다. 그 대가로 컨테이너가 사라진
 * ACTIVE 행이 잠깐 보일 수 있지만, 닫아도 해가 없다(컨테이너 제거는 멱등이다). 읽기 전용 트랜잭션 하나에서 세 번 읽는다
 * — 세션(사용자·상태 인덱스), 작업 상태(PK IN), 프로젝트 이름(PK IN).</p>
 */
@Service
@RequiredArgsConstructor
public class PreviewSessionListService {

    private static final List<String> LIVE_STATUSES = List.of(
            PreviewSessionStatus.ACTIVE.name(),
            PreviewSessionStatus.PROVISIONING.name()
    );

    private final SpringDataPreviewSessionRepository repository;
    private final TaskStore taskStore;
    private final ProjectRepository projectRepository;
    private final UserLimitProperties limits;

    @Transactional(readOnly = true)
    public PreviewSessionListResult list(Long ownerUserId) {
        List<PreviewSessionEntity> live =
                repository.findByOwnerUserIdAndStatusInOrderByLastAccessedAtAsc(ownerUserId, LIVE_STATUSES);

        Map<String, TaskStatus> taskStatuses = taskStore.statusesOf(live.stream()
                .map(PreviewSessionEntity::getTaskId)
                .filter(Objects::nonNull)
                .distinct()
                .toList());
        List<Long> projectIds = live.stream()
                .map(PreviewSessionEntity::getProjectId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        // 지운 프로젝트도 이름을 보여 준다(휴지통). 행이 아예 없으면 이름 없이 항목만 남는다.
        Map<Long, String> projectNames = projectIds.isEmpty()
                ? Map.of()
                : projectRepository.findAllByIdInAndOwnerUserId(projectIds, ownerUserId).stream()
                        .filter(project -> project.getName() != null)
                        .collect(Collectors.toMap(Project::getId, Project::getName, (a, b) -> a));

        List<PreviewSessionListResult.Item> items = live.stream()
                .map(session -> item(session, taskStatuses::get, projectNames))
                .toList();
        return new PreviewSessionListResult(limits.maxLivePreviews(), items);
    }

    private static PreviewSessionListResult.Item item(PreviewSessionEntity session,
                                                      Function<String, TaskStatus> taskStatuses,
                                                      Map<Long, String> projectNames) {
        TaskStatus taskStatus = session.getTaskId() == null ? null : taskStatuses.apply(session.getTaskId());
        Optional<String> blocked = PreviewClosePolicy.closeBlockedReason(session.getStatus(), taskStatus);
        return new PreviewSessionListResult.Item(
                session.getId(),
                session.getStatus(),
                session.getProjectId(),
                session.getProjectId() == null ? null : projectNames.get(session.getProjectId()),
                session.getTaskId(),
                taskStatus,
                blocked.isEmpty(),
                blocked.orElse(null),
                session.getLastAccessedAt(),
                session.getExpiresAt(),
                session.getCreatedAt()
        );
    }
}
