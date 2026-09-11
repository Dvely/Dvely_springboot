package com.example.dvely.change.infrastructure.persistence.repository;

import com.example.dvely.change.infrastructure.persistence.entity.ChangeEntity;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataChangeRepository extends JpaRepository<ChangeEntity, Long> {

    Optional<ChangeEntity> findByTaskId(String taskId);

    Optional<ChangeEntity> findByIdAndOwnerUserId(Long changeId, Long ownerUserId);

    // U6 6-1: 목록은 프로젝션으로만 읽는다. 엔티티로 읽으면 diff_text(MEDIUMTEXT, 행당 최대 1MB)까지
    // 함께 실려 오는데 ChangeResult 에는 diff 가 없다 — 프로젝트 개요를 한 번 열면 그 프로젝트의
    // 모든 diff 를 DB 에서 끌어오고 그대로 버리고 있었다. 아래 뷰의 컬럼 = ChangeResult 의 필드다.
    interface ChangeSummaryView {
        Long getId();
        Long getProjectId();
        Long getConversationId();
        String getTaskId();
        String getPreviewSessionId();
        String getStatus();
        String getSummary();
        Long getApprovalId();
        Integer getPrNumber();
        String getMergeCommitSha();
        LocalDateTime getMergedAt();
        LocalDateTime getCreatedAt();
        LocalDateTime getUpdatedAt();
    }

    // createdAt 뒤에 id 를 덧붙여 정렬한다 — created_at 이 같은 초인 행들의 순서가 비결정적이면
    // 커서(:after) 페이지네이션이 같은 행을 건너뛰거나 두 번 준다.
    @Query("""
            select c.id as id,
                   c.projectId as projectId,
                   c.conversationId as conversationId,
                   c.taskId as taskId,
                   c.previewSessionId as previewSessionId,
                   c.status as status,
                   c.summary as summary,
                   c.approvalId as approvalId,
                   c.prNumber as prNumber,
                   c.mergeCommitSha as mergeCommitSha,
                   c.mergedAt as mergedAt,
                   c.createdAt as createdAt,
                   c.updatedAt as updatedAt
            from ChangeEntity c
            where c.projectId = :projectId
              and c.ownerUserId = :ownerUserId
              and (:after is null or c.id < :after)
            order by c.createdAt desc, c.id desc
            """)
    List<ChangeSummaryView> findProjectChangeSummaries(
            @Param("projectId") Long projectId,
            @Param("ownerUserId") Long ownerUserId,
            @Param("after") Long after,
            Pageable pageable
    );

    // Track Z (#56) review follow-up (BLOCKING-1): backs ResultApprovalService#hasResultGateHistory
    // — a project-scoped existence check (any status, not just the current task's own Change row)
    // used to tell whether this project has already had at least one RESULT-gate decision.
    boolean existsByProjectIdAndStatusIn(Long projectId, Collection<String> statuses);
}
