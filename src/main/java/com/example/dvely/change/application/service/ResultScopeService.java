package com.example.dvely.change.application.service;

import com.example.dvely.agent.application.service.ServerPreviewBranch;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.change.application.port.out.GithubComparePort;
import com.example.dvely.change.application.port.out.GithubComparePort.Comparison;
import com.example.dvely.change.application.result.ResultScope;
import com.example.dvely.change.application.result.ResultScope.CommitView;
import com.example.dvely.change.application.result.ResultScope.Decided;
import com.example.dvely.change.application.result.ResultScope.Decision;
import com.example.dvely.change.application.result.ResultScope.FileChange;
import com.example.dvely.change.application.result.ResultScope.Origin;
import com.example.dvely.change.application.result.ResultScope.RejectOptionView;
import com.example.dvely.change.application.result.ResultScope.Scope;
import com.example.dvely.change.application.result.ResultScope.Totals;
import com.example.dvely.change.domain.value.ChangeStatus;
import com.example.dvely.change.infrastructure.persistence.entity.ChangeEntity;
import com.example.dvely.change.infrastructure.persistence.entity.ServerPushedCommitEntity;
import com.example.dvely.change.infrastructure.persistence.repository.SpringDataChangeRepository;
import com.example.dvely.change.infrastructure.persistence.repository.SpringDataChangeRepository.TaskDecisionView;
import com.example.dvely.common.exception.GithubAppReauthorizationRequiredException;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 결과 승인의 실제 반영 범위(#532). 승인은 preview 전체를 main 에 merge 하므로, 이번 작업 외의 변경(다른 작업,
 * 이전에 거절한 작업, 사람이 직접 올린 커밋)도 함께 반영된다. 그 범위를 GitHub 의 {@code main...preview} 비교로
 * 계산해 보여 준다. 에이전트가 쓴 설명은 근거로 쓰지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResultScopeService {

    public static final String BASE = "main";
    public static final String HEAD = "preview";

    /** compare API 의 파일 목록 상한. 이만큼 왔으면 잘렸을 수 있다. */
    static final int GITHUB_FILE_LIMIT = 300;
    /** 커밋별 파일을 따로 읽는 상한. 넘으면 파일별 origin 을 좁히지 않고 잘린 것으로 표시한다. */
    static final int COMMIT_LOOKUP_LIMIT = 30;

    private static final Pattern TASK_IN_MESSAGE = Pattern.compile("Qeploy Agent task (\\S+)");

    static final String UNAVAILABLE_NOTICE = "반영 범위를 불러오지 못했습니다. 지금 승인하면 이번 작업 외의 변경이 함께 "
            + "반영되는지 확인하지 못한 채 반영됩니다. 다시 불러오기를 권합니다.";
    static final String REVERT_UNAVAILABLE_REASON = "이 환경에서는 preview 되돌리기를 쓸 수 없습니다.";

    private final ApprovalRepository approvalRepository;
    private final SpringDataChangeRepository changeRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final AuthCommandService authCommandService;
    private final GithubComparePort githubComparePort;
    private final ServerPreviewBranch serverPreviewBranch;
    private final ServerPushLedger pushLedger;
    private final ObjectMapper objectMapper;

    /** GET /approvals/{id}/result-scope */
    public ResultScope view(Long ownerUserId, Long approvalId) {
        Approval approval = approvalRepository.findByIdAndOwnerUserId(approvalId, ownerUserId)
                .orElseThrow(() -> new NotFoundException("승인을 찾을 수 없습니다. approvalId=" + approvalId));
        if (approval.getType() != ApprovalType.RESULT) {
            throw new IllegalArgumentException("반영 범위는 결과 승인에만 있습니다. approvalId=" + approvalId);
        }
        String status = approval.getStatus().name();
        if (approval.getStatus() != ApprovalStatus.PENDING) {
            Decided decided = decided(approval);
            return new ResultScope(approval.getId(), BASE, HEAD, status, "DECIDED", decided.notice(), null, decided,
                    List.of());
        }
        Scope scope;
        try {
            scope = compute(approval);
        } catch (GithubAppReauthorizationRequiredException e) {
            throw e;   // 403 재인증 — 경고 카드가 아니라 재인증으로 보내야 한다
        } catch (RuntimeException e) {
            log.warn("[ResultScope] 반영 범위 계산 실패 | approvalId={} cause={}", approvalId, e.getClass().getSimpleName());
            return new ResultScope(approval.getId(), BASE, HEAD, status, "UNAVAILABLE", UNAVAILABLE_NOTICE, null, null,
                    rejectOptions(null));
        }
        return new ResultScope(approval.getId(), BASE, HEAD, status, "READY", notice(scope), scope, null,
                rejectOptions(scope));
    }

    /**
     * 결정 직전에 남길 범위(JSON). 실패해도 결정은 막지 않는다 — 비어 있으면 결정 뒤 화면이 "기록 없음"으로 보인다.
     */
    public String snapshotJson(Approval approval) {
        try {
            return objectMapper.writeValueAsString(compute(approval));
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("[ResultScope] 결정 시점 범위 기록 실패 | approvalId={} cause={}", approval.getId(),
                    e.getClass().getSimpleName());
            return null;
        }
    }

    Scope compute(Approval approval) {
        Optional<ChangeEntity> change = changeRepository.findByTaskId(approval.getTaskId());
        String repo = projectRepository
                .findByIdAndOwnerUserIdAndDeletedFalse(approval.getProjectId(), approval.getOwnerUserId())
                .map(project -> project.getSourceRepository())
                .orElse(null);
        String token = repo == null ? null : resolveUserToken(approval.getOwnerUserId());
        Comparison comparison = repo == null ? Comparison.empty()
                : githubComparePort.compare(token, repo, BASE, HEAD);

        Map<String, ServerPushedCommitEntity> pushed = new HashMap<>();
        if (repo != null) {
            pushLedger.find(repo, comparison.commits().stream().map(GithubComparePort.Commit::sha).toList())
                    .forEach(entry -> pushed.put(entry.getCommitSha(), entry));
        }
        Set<String> reverts = new HashSet<>();
        pushed.values().stream().filter(ServerPushedCommitEntity::isRevert)
                .forEach(entry -> reverts.add(entry.getCommitSha()));
        List<GithubComparePort.Commit> effective = effectiveCommits(comparison.commits(), reverts);
        Map<String, Attribution> taskBySha = new LinkedHashMap<>();
        for (GithubComparePort.Commit commit : effective) {
            taskBySha.put(commit.sha(), attribute(commit, pushed.get(commit.sha()), approval.getTaskId()));
        }
        Set<String> otherTasks = new HashSet<>();
        taskBySha.values().forEach(a -> otherTasks.add(a.taskId()));
        otherTasks.remove(null);
        otherTasks.remove(approval.getTaskId());
        Map<String, TaskDecisionView> decisions = new HashMap<>();
        if (!otherTasks.isEmpty()) {
            for (TaskDecisionView view : changeRepository.findTaskDecisions(approval.getProjectId(), otherTasks)) {
                decisions.put(view.getTaskId(), view);
            }
        }
        List<CommitView> commits = new ArrayList<>();
        for (GithubComparePort.Commit commit : effective) {
            commits.add(commitView(commit.sha(), taskBySha.get(commit.sha()), approval.getTaskId(), decisions));
        }

        boolean commitsTruncated = comparison.totalCommits() > comparison.commits().size();
        FileOrigins fileOrigins = fileOrigins(commits, token, repo);
        boolean pendingPush = change.map(ChangeEntity::isPushDeferred).orElse(false);
        List<FileChange> files = mergeFiles(comparison.files(), fileOrigins.byPath(), fileOrigins.fallback(),
                pendingPush ? UnifiedDiffFiles.parse(change.get().getDiffText()) : List.of());

        boolean truncated = commitsTruncated || fileOrigins.truncated()
                || comparison.files().size() >= GITHUB_FILE_LIMIT;
        return new Scope(
                pendingPush,
                files,
                truncated ? null : totals(files),
                commits,
                commits.stream().anyMatch(c -> c.origin() != Origin.THIS_TASK),
                commits.stream().anyMatch(c -> c.origin() == Origin.PREVIOUSLY_REJECTED),
                truncated);
    }

    /**
     * 서버가 올린 거절 REVERT 커밋과 그 조상은 뺀다. 되돌린 뒤 preview 는 main 과 같은 내용이라, 그 앞의 커밋들은
     * 비교 목록에는 남아도 이번 승인으로 반영되는 내용이 아니다. REVERT 인지는 서버가 남긴 push 기록으로만 정한다 —
     * 메시지만 흉내 낸 커밋으로 앞의 변경을 목록에서 숨길 수 없게 한다.
     */
    static List<GithubComparePort.Commit> effectiveCommits(List<GithubComparePort.Commit> commits, Set<String> reverts) {
        Map<String, GithubComparePort.Commit> bySha = new HashMap<>();
        commits.forEach(c -> bySha.put(c.sha(), c));
        Set<String> neutralized = new HashSet<>();
        for (GithubComparePort.Commit commit : commits) {
            if (reverts.contains(commit.sha())) {
                markAncestors(commit.sha(), bySha, neutralized);
            }
        }
        return commits.stream().filter(c -> !neutralized.contains(c.sha())).toList();
    }

    /**
     * 커밋이 어느 작업의 것인지. 서버 push 기록이 있으면 그것을 믿는다. 없으면 메시지의 taskId 를 쓰되,
     * 메시지가 이번 작업이라고 주장하는 것은 믿지 않는다 — 누구나 그렇게 써서 다른 변경을 "이번 작업"으로
     * 보이게 할 수 있다. 메시지로는 경고가 커지는 쪽(다른 작업·거절된 작업)으로만 분류된다.
     */
    static Attribution attribute(GithubComparePort.Commit commit, ServerPushedCommitEntity pushed, String thisTask) {
        if (pushed != null && !pushed.isRevert() && pushed.getTaskId() != null) {
            return new Attribution(pushed.getTaskId(), true);
        }
        String claimed = taskIdOf(commit.message());
        if (claimed == null || claimed.equals(thisTask)) {
            return new Attribution(null, false);
        }
        return new Attribution(claimed, false);
    }

    record Attribution(String taskId, boolean recorded) {
    }

    private static void markAncestors(String sha, Map<String, GithubComparePort.Commit> bySha, Set<String> marked) {
        List<String> stack = new ArrayList<>(List.of(sha));
        while (!stack.isEmpty()) {
            String current = stack.removeLast();
            GithubComparePort.Commit commit = bySha.get(current);
            if (commit == null || !marked.add(current)) {
                continue;   // 비교 구간 밖(main 에 이미 있음)이거나 이미 봤다
            }
            stack.addAll(commit.parents());
        }
    }

    static String taskIdOf(String message) {
        Matcher matcher = TASK_IN_MESSAGE.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    static CommitView commitView(String sha, Attribution attribution, String thisTask,
                                 Map<String, TaskDecisionView> decisions) {
        String taskId = attribution.taskId();
        if (taskId == null) {
            return new CommitView(sha, null, Origin.UNKNOWN, null);
        }
        if (taskId.equals(thisTask) && attribution.recorded()) {
            return new CommitView(sha, taskId, Origin.THIS_TASK, null);
        }
        TaskDecisionView decision = decisions.get(taskId);
        if (decision == null || decision.getApprovalId() == null) {
            return new CommitView(sha, taskId, Origin.OTHER, null);
        }
        if (ChangeStatus.REJECTED.name().equals(decision.getStatus())) {
            return new CommitView(sha, taskId, Origin.PREVIOUSLY_REJECTED,
                    new Decision(decision.getApprovalId(), ApprovalStatus.REJECTED.name()));
        }
        return new CommitView(sha, taskId, Origin.OTHER,
                new Decision(decision.getApprovalId(), ApprovalStatus.APPROVED.name()));
    }

    /**
     * 파일마다 그것을 바꾼 커밋들의 origin. 커밋 origin 이 한 가지뿐이면 커밋을 따로 읽지 않는다(흔한 경우).
     * 읽을 커밋이 상한을 넘으면 모든 파일에 등장한 origin 전부를 주고 잘린 것으로 표시한다.
     */
    private FileOrigins fileOrigins(List<CommitView> commits, String token, String repo) {
        EnumSet<Origin> all = EnumSet.noneOf(Origin.class);
        commits.forEach(c -> all.add(c.origin()));
        if (all.size() <= 1) {
            return new FileOrigins(Map.of(), all.isEmpty() ? EnumSet.of(Origin.UNKNOWN) : all, false);
        }
        if (commits.size() > COMMIT_LOOKUP_LIMIT) {
            return new FileOrigins(Map.of(), all, true);
        }
        Map<String, EnumSet<Origin>> byPath = new HashMap<>();
        for (CommitView commit : commits) {
            for (String path : githubComparePort.commitFiles(token, repo, commit.sha())) {
                byPath.computeIfAbsent(path, p -> EnumSet.noneOf(Origin.class)).add(commit.origin());
            }
        }
        return new FileOrigins(byPath, EnumSet.of(Origin.UNKNOWN), false);
    }

    private record FileOrigins(Map<String, EnumSet<Origin>> byPath, EnumSet<Origin> fallback, boolean truncated) {
    }

    /** 비교 결과에 미룬 push(#527)의 서버 diff 를 더한다. 같은 경로면 비교 쪽 줄 수를 두고 THIS_TASK 만 더한다. */
    static List<FileChange> mergeFiles(List<GithubComparePort.File> compared, Map<String, EnumSet<Origin>> byPath,
                                       EnumSet<Origin> fallback, List<FileChange> deferred) {
        Map<String, FileChange> merged = new LinkedHashMap<>();
        for (GithubComparePort.File file : compared) {
            EnumSet<Origin> origins = EnumSet.noneOf(Origin.class);
            origins.addAll(byPath.getOrDefault(file.path(), EnumSet.noneOf(Origin.class)));
            if (file.previousPath() != null) {
                origins.addAll(byPath.getOrDefault(file.previousPath(), EnumSet.noneOf(Origin.class)));
            }
            if (origins.isEmpty()) {
                origins.addAll(fallback);
            }
            merged.put(file.path(), new FileChange(file.path(), file.status(), file.additions(), file.deletions(),
                    file.previousPath(), List.copyOf(origins)));
        }
        for (FileChange file : deferred) {
            FileChange existing = merged.get(file.path());
            if (existing == null) {
                merged.put(file.path(), file);
                continue;
            }
            EnumSet<Origin> origins = EnumSet.copyOf(existing.origins());
            origins.add(Origin.THIS_TASK);
            merged.put(file.path(), new FileChange(existing.path(), existing.status(), existing.additions(),
                    existing.deletions(), existing.previousPath(), List.copyOf(origins)));
        }
        return List.copyOf(merged.values());
    }

    static Totals totals(List<FileChange> files) {
        int additions = 0;
        int deletions = 0;
        for (FileChange file : files) {
            additions += file.additions() == null ? 0 : file.additions();
            deletions += file.deletions() == null ? 0 : file.deletions();
        }
        return new Totals(files.size(), additions, deletions);
    }

    static String notice(Scope scope) {
        if (scope.files().isEmpty() && scope.commits().isEmpty()) {
            return "main 에 반영할 변경이 없습니다.";
        }
        long rejectedTasks = scope.commits().stream()
                .filter(c -> c.origin() == Origin.PREVIOUSLY_REJECTED)
                .map(CommitView::taskId).distinct().count();
        String rejected = rejectedTasks > 0 ? " 그중 " + rejectedTasks + "건은 이전에 거절한 변경입니다." : "";
        if (scope.truncated()) {
            String others = scope.includesOtherWork()
                    ? " 승인하면 이번 작업 외의 변경도 함께 main 에 반영됩니다." + rejected
                    : " 이번 작업 외의 변경이 함께 반영될 수 있습니다.";
            return "변경 파일이 많아 일부만 표시합니다." + others;
        }
        Totals totals = scope.totals();
        String counts = "파일 " + totals.files() + "개(+" + totals.additions() + " −" + totals.deletions() + ")";
        if (scope.includesOtherWork()) {
            return "승인하면 이번 작업 외의 변경도 함께 main 에 반영됩니다: " + counts + "." + rejected;
        }
        return "승인하면 이번 작업의 변경이 main 에 반영됩니다: " + counts + ".";
    }

    List<RejectOptionView> rejectOptions(Scope scope) {
        boolean revertAvailable = serverPreviewBranch.enabled();
        return List.of(
                new RejectOptionView("KEEP", "preview 에 남기기",
                        "main 에는 반영하지 않고 preview 에 둡니다. 이어서 수정을 요청하면 지금 preview 위에서 작업하고, "
                                + "다음 결과 승인 때 함께 반영됩니다.",
                        true, true, null),
                new RejectOptionView("REVERT", "preview 되돌리기", revertDescription(scope),
                        revertAvailable, false, revertAvailable ? null : REVERT_UNAVAILABLE_REASON));
    }

    static String revertDescription(Scope scope) {
        String head = "preview 를 main 과 같은 상태로 되돌립니다. ";
        String tail = " 저장소 기록에는 되돌리는 커밋이 남습니다.";
        if (scope == null) {
            return head + "아직 main 에 반영되지 않은 preview 의 변경이 모두 빠집니다." + tail;
        }
        long others = scope.commits().stream().filter(c -> c.origin() != Origin.THIS_TASK).count();
        String thisTask = scope.pendingPush()
                ? "이번 작업은 아직 preview 에 올라가지 않았으므로, "
                : "이번 작업과 ";
        if (others == 0) {
            return head + (scope.pendingPush() ? "이번 작업은 아직 preview 에 올라가지 않아 바뀌는 것이 없습니다."
                    : "이번 작업이 preview 에서 빠집니다.") + tail;
        }
        return head + thisTask + "아직 main 에 반영되지 않은 다른 변경 " + others + "건이 모두 preview 에서 빠집니다." + tail;
    }

    private Decided decided(Approval approval) {
        String option = approval.getRejectOption() == null ? null : approval.getRejectOption().name();
        Scope scope = changeRepository.findByTaskId(approval.getTaskId())
                .filter(c -> approval.getId().equals(c.getApprovalId()))
                .map(ChangeEntity::getDecidedScope)
                .map(this::readScope)
                .orElse(null);
        return new Decided(option, approval.getDecidedAt(), decidedNotice(approval, scope), scope);
    }

    private Scope readScope(String json) {
        try {
            return objectMapper.readValue(json, Scope.class);
        } catch (JsonProcessingException e) {
            log.warn("[ResultScope] 결정 시점 범위를 읽지 못했습니다 | cause={}", e.getClass().getSimpleName());
            return null;
        }
    }

    static String decidedNotice(Approval approval, Scope scope) {
        String missing = scope == null ? " 결정 당시의 반영 범위는 기록되지 않았습니다." : "";
        return switch (approval.getStatus()) {
            case APPROVED -> "승인하여 preview 의 변경을 main 에 반영했습니다." + missing;
            case REJECTED -> approval.getRejectOption() == com.example.dvely.approval.domain.value.RejectOption.REVERT
                    ? "거절하고 preview 를 main 과 같은 상태로 되돌렸습니다." + missing
                    : "거절했습니다. main 에는 반영하지 않았고 preview 는 그대로 두었습니다." + missing;
            case CANCELLED -> "결정하기 전에 작업이 취소되었습니다.";
            case PENDING -> throw new IllegalStateException("결정되지 않은 승인입니다");
        };
    }

    /** 만료됐으면 갱신한다. 재인증이 필요하면 {@link GithubAppReauthorizationRequiredException} 이 그대로 올라간다. */
    private String resolveUserToken(Long ownerUserId) {
        User user = userRepository.findById(ownerUserId)
                .orElseThrow(() -> new IllegalStateException("유저를 찾을 수 없습니다. userId=" + ownerUserId));
        return user.isUserAccessTokenExpired()
                ? authCommandService.refreshGithubUserToken(ownerUserId)
                : user.getGithubUserAccessToken();
    }
}
