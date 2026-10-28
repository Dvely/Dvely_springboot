package com.example.dvely.change.application.result;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 결과 승인 반영 범위 응답(#532). 최상위 모양은 상태와 무관하게 같다.
 *
 * @param scopeState     READY(계산함) | UNAVAILABLE(계산 실패, 승인·거절은 가능) | DECIDED(결정됨)
 * @param notice         서버가 만든 한 줄. 항상 있다
 * @param scope          READY 일 때만
 * @param decidedSnapshot DECIDED 일 때만
 * @param rejectOptions  PENDING 일 때만 채운다
 */
public record ResultScope(
        Long approvalId,
        String base,
        String head,
        String status,
        String scopeState,
        String notice,
        Scope scope,
        Decided decidedSnapshot,
        List<RejectOptionView> rejectOptions
) {

    public enum Origin {
        THIS_TASK,
        PREVIOUSLY_REJECTED,
        OTHER,
        UNKNOWN
    }

    public record Scope(
            boolean pendingPush,
            List<FileChange> files,
            Totals totals,
            List<CommitView> commits,
            boolean includesOtherWork,
            boolean includesRejected,
            boolean truncated
    ) {
    }

    /** @param origins 그 파일을 바꾼 커밋들의 origin. {@link Origin} 선언 순서로 정렬, 중복 없음 */
    public record FileChange(
            String path,
            String status,
            Integer additions,
            Integer deletions,
            String previousPath,
            List<Origin> origins
    ) {
    }

    public record Totals(int files, int additions, int deletions) {
    }

    public record CommitView(String sha, String taskId, Origin origin, Decision decision) {
    }

    public record Decision(Long approvalId, String status) {
    }

    /** @param option KEEP | REVERT. REJECTED 일 때만 있다 */
    public record Decided(String option, LocalDateTime decidedAt, String notice, Scope scope) {
    }

    public record RejectOptionView(
            String option,
            String label,
            String description,
            boolean available,
            @com.fasterxml.jackson.annotation.JsonProperty("default") boolean isDefault,
            String unavailableReason
    ) {
    }
}
