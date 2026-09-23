package com.example.dvely.agent.infrastructure.worker;

import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.preview.application.service.PreviewSessionService;
import com.example.dvely.preview.infrastructure.config.PreviewProperties;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 프리뷰가 회수된 뒤에도 남아 있는 결과 승인 대기 태스크를 닫는다.
 *
 * <p>승인 대상 작업물은 컨테이너 안에만 있다. 컨테이너가 사라지면 승인해도 반영할 것이 없는데,
 * 승인은 {@code PENDING} 으로 남아 카드가 계속 떠 있었다 — 사용자는 <b>누르면 실패하는 버튼</b>을
 * 보고 있었다(#380). {@code PreviewProperties} 의 javadoc 은 이 정리가 이미 일어나는 것처럼
 * 적고 있었지만 그런 코드는 없었다.</p>
 *
 * <p>후보를 {@code WAITING_RESULT_APPROVAL} 로만 좁히는 것이 이 클래스의 핵심이다.
 * {@code WAITING_APPROVAL}(계획 승인)은 CODE 실행 <b>전</b>이라 프리뷰가 아예 없으므로, 같이
 * 묶으면 "프리뷰가 없다"는 조건에 전부 걸려 모든 계획 승인이 즉시 취소된다.</p>
 *
 * <p>거짓 양성이 없는 이유: {@code ResultApprovalGate} 가 preview 브랜치를 밀 때 ACTIVE 세션을
 * {@code orElseThrow} 로 요구하므로, 이 상태에 도달한 태스크는 반드시 프리뷰를 가졌다. 따라서
 * "지금 ACTIVE 세션이 없다"는 "한 번도 없었다"가 아니라 "회수됐다"를 뜻한다.</p>
 *
 * <p>이 스윕이 {@code AbandonedApprovalSweeper}(7일)를 대신하지는 않는다. 저쪽은 프리뷰와 무관하게
 * 결정이 오지 않는 것을 닫고, 이쪽은 결정할 대상이 사라진 것을 닫는다. 실제로는 hold(기본 6시간)가
 * 지난 직후 이쪽이 먼저 잡는다.</p>
 */
@Slf4j
@Component
public class LostPreviewApprovalSweeper {

    private final TaskStore taskStore;
    private final PreviewSessionService previewSessionService;
    private final AgentOrchestrator agentOrchestrator;
    private final PreviewProperties previewProperties;

    public LostPreviewApprovalSweeper(
            TaskStore taskStore,
            PreviewSessionService previewSessionService,
            AgentOrchestrator agentOrchestrator,
            PreviewProperties previewProperties
    ) {
        this.taskStore = taskStore;
        this.previewSessionService = previewSessionService;
        this.agentOrchestrator = agentOrchestrator;
        this.previewProperties = previewProperties;
    }

    @Scheduled(fixedDelayString = "${qeploy.agent.approval.lost-preview-sweep-interval-ms:300000}")
    public void sweep() {
        List<String> candidates = taskStore.findResultApprovalWaitingTaskIds();
        for (String taskId : candidates) {
            try {
                if (previewSessionService.findByTaskId(taskId).isPresent()) {
                    continue;   // 아직 살아 있다 — 사용자가 결정할 대상이 남아 있다
                }
                agentOrchestrator.abandonApprovalTaskWithLostPreview(
                        taskId, previewProperties.getApprovalHold());
            } catch (Exception exception) {
                // 한 건의 실패가 나머지 배치를 멈추지 않게 한다 — AbandonedApprovalSweeper 와 같은
                // 태스크 단위 격리 원칙이다.
                log.warn("[LostPreviewApprovalSweeper] 정리 실패 — 다음 스윕에서 재시도합니다. taskId={}",
                        taskId, exception);
            }
        }
    }
}
