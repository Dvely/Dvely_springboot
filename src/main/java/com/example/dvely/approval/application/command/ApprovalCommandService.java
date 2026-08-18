package com.example.dvely.approval.application.command;

import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.application.service.AgentMessageService;
import com.example.dvely.agent.application.service.RepositoryBindingService;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.approval.application.port.out.StandaloneApprovalHandler;
import com.example.dvely.approval.application.query.ApprovalQueryService;
import com.example.dvely.approval.application.result.ApprovalResult;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.repository.ApprovalRouting;
import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.change.application.service.ResultApprovalService;
import com.example.dvely.common.exception.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ApprovalCommandService {

    private final ApprovalRepository approvalRepository;
    private final ApprovalQueryService queryService;
    private final AgentOrchestrator agentOrchestrator;
    private final AgentMessageService agentMessageService;
    // Spring injects every StandaloneApprovalHandler bean here (an empty list is fine — it just
    // means no standalone ApprovalType is wired yet). Kept as a List rather than a Map so new
    // handlers register themselves purely by implementing the interface (design D6/§4.1) with
    // no change needed in this service.
    private final List<StandaloneApprovalHandler> standaloneHandlers;
    // Track Z (#56): RESULT is never standalone (always task-bound, created only by
    // ResultApprovalGate) and never joins the allApproved plan-approval vote below — it gets its
    // own branch between the two, matching design D2/§4.2.
    private final ResultApprovalService resultApprovalService;
    // RESULT 와 같은 성질의 게이트 승인(태스크 바인딩, allApproved 투표에 참여하지 않음)이라 바로
    // 옆에 자기 분기를 갖는다 — 다만 NOT_BOUND 프로젝트를 대상으로 한다.
    private final RepositoryBindingService repositoryBindingService;
    // ADR-Y1 (#55): the task-row mutex (agent_runs, the aggregate root every task-bound decision
    // must lock FIRST — see TaskStore#lockTask) lives in the agent module's store, not here. This
    // service already reaches into the agent module for AgentOrchestrator/AgentMessageService, so
    // this is not a new layering precedent — approval and agent are tightly coupled by design
    // (approval gates agent task execution).
    private final TaskStore taskStore;

    /** 값이 필요 없는 승인용. REPOSITORY_BINDING 이라면 게이트가 제안한 후보 이름으로 폴백한다. */
    @Transactional
    public ApprovalResult approve(Long ownerUserId, Long approvalId) {
        return approve(ownerUserId, approvalId, null);
    }

    /**
     * @param repositoryName REPOSITORY_BINDING 승인에서만 의미가 있다. 다른 타입에서는 무시되므로
     *                       기존 다섯 타입의 동작은 이 파라미터가 생기기 전과 완전히 동일하다.
     *                       null/공백이면 게이트가 승인 요약에 적어 사용자에게 이미 보여준 후보
     *                       이름이 쓰인다.
     */
    @Transactional
    public ApprovalResult approve(Long ownerUserId, Long approvalId, String repositoryName) {
        ApprovalRouting routing = routingFor(ownerUserId, approvalId);
        if (routing.isStandalone()) {
            // Standalone: single row, no task to lock, order irrelevant (design §1 step②).
            Approval approval = findOwnedForDecide(ownerUserId, approvalId);
            approval.approve();
            Approval saved = approvalRepository.save(approval);
            resolveStandaloneHandler(saved.getType()).onApproved(saved);
            return queryService.toResult(saved);
        }

        // ADR-Y1 §1 step③: task row lock FIRST — the lock-hierarchy root every task-bound decision
        // path (approve/reject/cancel/RESULT/sweep) funnels through, structurally closing the D1
        // write-skew race (two approvals on the same task decided concurrently, each reading the
        // other's not-yet-committed PENDING row and both skipping executeApproved). This also
        // supersedes #56's RESULT-branch ordering (approval row -> task row, #62 B3's flagged
        // inversion) — every branch below, RESULT included, now locks task-then-approval.
        taskStore.lockTask(routing.taskId());
        // Fresh fetch under lock: step① deliberately used a scalar projection instead of loading
        // the Approval entity, so there is no stale L1-cached instance for this FOR UPDATE fetch
        // to collide with (see ApprovalRouting's javadoc).
        Approval approval = findOwnedForDecide(ownerUserId, approvalId);
        // REPOSITORY_BINDING 은 승인 대상 작업물이 아직 GitHub 어디에도 없고 프리뷰 컨테이너 안에만
        // 있는 유일한 승인이다(RESULT 는 게이트가 이미 preview 브랜치에 올려둔 뒤라 컨테이너가
        // 사라져도 reflect 가 GitHub 에서 머지한다). 컨테이너는 유예가 끝나면 회수되므로 그 뒤의
        // 승인은 아무것도 할 수 없다.
        //
        // 예전에는 그 상태에서 bind 가 예외를 던졌고, 그러면 위 approve() 까지 함께 롤백돼 승인이
        // PENDING 으로 남았다. 사용자에게는 눌러도 같은 409 만 반복되고 빠져나갈 길이 없는 카드가
        // 영원히 남는다(2026-08-18 운영에서 승인 15번이 그렇게 갇혔다). 되살릴 수 없다는 사실을
        // 말하고 닫는 편이 낫다.
        if (approval.getType() == ApprovalType.REPOSITORY_BINDING
                && !repositoryBindingService.isPreviewAvailable(approval)) {
            return closeExpiredRepositoryBinding(approval);
        }
        approval.approve();
        Approval saved = approvalRepository.save(approval);

        if (saved.getType() == ApprovalType.RESULT) {
            // Review follow-up (BLOCKING-3, carried forward): verify + lock the rollback-able DB
            // precondition (task must still be WAITING_RESULT_APPROVAL) BEFORE reflect()'s
            // irreversible external GitHub merge below. The task row is already locked (step③
            // above), so this call only re-affirms a lock this transaction already holds — no new
            // contention, no possibility of the ordering inversion #62 B3 flagged.
            agentOrchestrator.verifyResumableAfterResult(saved.getTaskId());
            ResultApprovalService.ReflectResult reflectResult = resultApprovalService.reflect(saved);
            agentOrchestrator.resumeAfterResult(saved.getTaskId());
            agentMessageService.appendAssistant(
                    saved.getConversationId(),
                    buildResultApprovedMessage(reflectResult)
            );
            return queryService.toResult(saved);
        }

        if (saved.getType() == ApprovalType.REPOSITORY_BINDING) {
            // RESULT 분기와 같은 순서: 되돌릴 수 있는 DB 전제조건(태스크가 아직
            // WAITING_RESULT_APPROVAL 인가)을 되돌릴 수 없는 GitHub 저장소 생성보다 먼저 확인한다.
            // 태스크 행은 위 step③ 에서 이미 잠갔으므로 이 호출은 이 트랜잭션이 이미 쥔 락을 다시
            // 확인할 뿐이다.
            agentOrchestrator.verifyResumableAfterResult(saved.getTaskId());
            RepositoryBindingService.BindResult bindResult =
                    repositoryBindingService.bind(saved, repositoryName);
            agentOrchestrator.resumeAfterResult(saved.getTaskId());
            agentMessageService.appendAssistant(
                    saved.getConversationId(),
                    buildRepositoryBoundMessage(bindResult)
            );
            return queryService.toResult(saved);
        }

        // All-approved check MUST be a locking read (design §1 "왜 all-approved 검사가 locking
        // read여야 하는가"): under REPEATABLE READ, a plain SELECT can still return a pre-lock
        // snapshot even after this transaction waited for and acquired the task lock above,
        // because that snapshot was fixed at this transaction's first non-locking read, not at
        // lock-acquisition time. A locking read always observes the latest committed version. This
        // never actually contends in practice — the task lock already serialized every other
        // decision-maker for this taskId out.
        List<Approval> taskApprovals = approvalRepository.findByTaskIdOrderByIdAscForUpdate(saved.getTaskId());
        boolean allApproved = !taskApprovals.isEmpty() && taskApprovals.stream()
                .allMatch(item -> item.getStatus() == ApprovalStatus.APPROVED);
        if (allApproved) {
            agentMessageService.appendAssistant(saved.getConversationId(), "모든 승인이 완료되어 작업을 시작합니다.");
            agentOrchestrator.executeApproved(saved.getTaskId());
        }
        return queryService.toResult(saved);
    }

    /**
     * 프리뷰가 회수돼 더는 실행할 수 없는 저장소 연결 승인을 닫는다.
     *
     * <p>REJECTED 가 아니라 CANCELLED 인 것은 의도다 — 사용자는 거절한 적이 없고 오히려 승인을
     * 눌렀다. 닫은 주체는 시스템이므로 감사 기록도 그렇게 남아야 한다.</p>
     *
     * <p>태스크는 거절과 같은 경로로 재개한다. 저장소를 만들지 않는다는 결과가 같고, CODE 작업
     * 자체는 이미 성공했으므로 취소가 아니라 완료로 끝나야 한다.</p>
     */
    private ApprovalResult closeExpiredRepositoryBinding(Approval approval) {
        approval.cancel();
        Approval saved = approvalRepository.save(approval);
        agentOrchestrator.declineAfterResult(
                saved.getTaskId(),
                "프리뷰가 만료되어 저장소를 연결하지 못한 채 작업을 마칩니다."
        );
        agentMessageService.appendAssistant(
                saved.getConversationId(),
                "프리뷰가 만료되어 작업물이 사라졌기 때문에 저장소를 연결하지 못했습니다.\n"
                        + "같은 내용을 다시 요청하면 새로 만들어 연결할 수 있습니다."
        );
        return queryService.toResult(saved);
    }

    @Transactional
    public ApprovalResult reject(Long ownerUserId, Long approvalId) {
        ApprovalRouting routing = routingFor(ownerUserId, approvalId);
        if (routing.isStandalone()) {
            Approval approval = findOwnedForDecide(ownerUserId, approvalId);
            approval.reject();
            Approval saved = approvalRepository.save(approval);
            resolveStandaloneHandler(saved.getType()).onRejected(saved);
            return queryService.toResult(saved);
        }

        // Same ADR-Y1 lock order as approve() above — reject is just as much a task-bound decision
        // and must serialize against a concurrent approve/reject/cancel/sweep on the same task.
        taskStore.lockTask(routing.taskId());
        Approval approval = findOwnedForDecide(ownerUserId, approvalId);
        approval.reject();
        Approval saved = approvalRepository.save(approval);

        if (saved.getType() == ApprovalType.RESULT) {
            resultApprovalService.markRejected(saved);
            // AgentOrchestrator.reject cascades to sibling PENDING approvals (Y6-a) — see its
            // javadoc for why reject and cancel now share that behavior.
            agentOrchestrator.reject(saved.getTaskId(), ownerUserId);
            agentMessageService.appendAssistant(
                    saved.getConversationId(),
                    "결과가 거절되어 main에 반영하지 않았습니다. 변경은 preview 브랜치에만 남아 있습니다.\n"
                            + "이어서 수정을 요청하면 현재 preview 상태 위에서 작업합니다."
            );
            return queryService.toResult(saved);
        }

        if (saved.getType() == ApprovalType.REPOSITORY_BINDING) {
            // 다른 모든 거절과 달리 태스크를 취소하지 않는다. 이 승인은 실행을 막는 게이트가 아니라
            // 이미 성공한 CODE 작업물을 저장소에 남길지 묻는 것이므로, 거절은 "저장소를 만들지
            // 않는다"는 뜻일 뿐 작업 자체는 성공으로 끝나야 한다. cancelTaskCascade 를 태우면
            // 멀쩡히 끝난 작업이 CANCELLED 로 뒤집힌다. 상태 전이는 승인과 같지만 이벤트는 갈라야
            // 한다 — resumeAfterResult 를 쓰면 거절한 시점에 RESULT_APPROVED 가 스트림에 찍힌다.
            agentOrchestrator.declineAfterResult(
                    saved.getTaskId(),
                    "저장소를 연결하지 않기로 하여 남은 작업을 재개합니다."
            );
            agentMessageService.appendAssistant(
                    saved.getConversationId(),
                    "저장소를 연결하지 않았습니다. 작업물은 프리뷰에만 남아 있으며 프리뷰가 만료되면 사라집니다.\n"
                            + "나중에 연결하려면 프로젝트 설정에서 저장소를 연결하거나 배포를 요청해주세요."
            );
            return queryService.toResult(saved);
        }

        agentOrchestrator.reject(saved.getTaskId(), ownerUserId);
        agentMessageService.appendAssistant(
                saved.getConversationId(),
                "작업이 거절되어 실행하지 않았습니다: " + saved.getSummary()
        );
        return queryService.toResult(saved);
    }

    // ADR-Y1 §1 step①: unlocked scalar routing lookup so approve/reject can decide standalone-vs-
    // task-bound (and therefore which lock order to take) BEFORE acquiring any lock. Throws 404
    // here, with no lock ever taken, if the approval does not exist / is not owned by this user.
    private ApprovalRouting routingFor(Long ownerUserId, Long approvalId) {
        return approvalRepository.findRoutingInfo(approvalId, ownerUserId)
                .orElseThrow(() -> new NotFoundException("승인을 찾을 수 없습니다. approvalId=" + approvalId));
    }

    private String buildResultApprovedMessage(ResultApprovalService.ReflectResult reflectResult) {
        StringBuilder message = new StringBuilder("결과가 승인되어 main에 반영되었습니다.");
        if (reflectResult.prNumber() != null) {
            message.append("\n- PR: #").append(reflectResult.prNumber());
        }
        if (reflectResult.mergeCommitSha() != null) {
            message.append("\n- commit: ")
                    .append(reflectResult.mergeCommitSha(), 0, Math.min(7, reflectResult.mergeCommitSha().length()));
        }
        message.append("\n남은 작업을 이어서 진행합니다.");
        return message.toString();
    }

    private String buildRepositoryBoundMessage(RepositoryBindingService.BindResult result) {
        if (!result.pushed()) {
            // 승인 대기 중 다른 경로로 이미 연결된 경우 — 이번 승인은 GitHub 작업을 하지 않았다.
            return "이미 연결된 저장소가 있어 그대로 사용합니다.\n"
                    + "- 저장소: https://github.com/" + result.repositoryFullName();
        }
        return (result.created()
                ? "GitHub 저장소를 새로 만들어 연결했습니다.\n"
                : "기존 GitHub 저장소에 연결했습니다.\n")
                + "- 저장소: https://github.com/" + result.repositoryFullName() + "\n"
                + "- 작업물을 preview 브랜치에 올렸습니다. 프리뷰가 만료돼도 코드는 남습니다.";
    }

    // Both approve() and reject() funnel through this single locked lookup (review F1) — the
    // approval row is the one point of contention for a given approvalId, so a concurrent
    // approve+reject pair serializes here: the second call's SELECT ... FOR UPDATE blocks until
    // the first transaction commits, then observes the now-APPROVED/REJECTED row and fails
    // Approval#decide's PENDING guard (409) instead of silently overwriting the first outcome.
    // Plain reads (ApprovalQueryService) intentionally keep using the unlocked lookup — only
    // decision paths need the lock.
    private Approval findOwnedForDecide(Long ownerUserId, Long approvalId) {
        return approvalRepository.findByIdAndOwnerUserIdForUpdate(approvalId, ownerUserId)
                .orElseThrow(() -> new NotFoundException("승인을 찾을 수 없습니다. approvalId=" + approvalId));
    }

    // A standalone ApprovalType with no registered handler is a server configuration defect
    // (an ApprovalType was wired into standalone creation without a matching handler bean) —
    // not a client error, but it must still fail the request rather than silently no-op, so it
    // surfaces as a 409 that GlobalExceptionHandler logs at warn level for operators to notice.
    private StandaloneApprovalHandler resolveStandaloneHandler(ApprovalType type) {
        return standaloneHandlers.stream()
                .filter(handler -> handler.supports(type))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("standalone 승인을 처리할 핸들러가 없습니다. type=" + type));
    }
}
