package com.example.dvely.agent.presentation;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentSubmission;
import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.application.service.DecisionAgentService;
import com.example.dvely.agent.infrastructure.docker.UserContainerRegistry;
import com.example.dvely.agent.infrastructure.store.InputWaitStore;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.agent.presentation.dto.DecisionRequest;
import com.example.dvely.agent.presentation.dto.DecisionResponse;
import com.example.dvely.agent.presentation.dto.TaskStatusResponse;
import com.example.dvely.agent.presentation.dto.TaskInputRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Agent", description = "AI 에이전트 요청 처리 API. 자연어 요청을 분석하여 코드 생성·수정, GitHub Pages 배포, 도메인 연결을 비동기로 실행합니다.")
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentController {

    private final DecisionAgentService  decisionAgentService;
    private final AgentOrchestrator     agentOrchestrator;
    private final TaskStore             taskStore;
    private final UserContainerRegistry containerRegistry;
    private final InputWaitStore        inputWaitStore;

    @Operation(
            summary = "에이전트 요청 제출",
            description = "자연어 요청을 분석해 실행 계획(steps)을 수립하고 비동기로 실행합니다. " +
                          "응답의 taskId로 진행 상태를 폴링하세요. " +
                          "기존 프로젝트 수정 시 projectId를 함께 전달하면 해당 저장소를 클론하여 작업합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 접수 완료. taskId 반환"),
            @ApiResponse(responseCode = "400", description = "요청 본문 유효성 오류"),
            @ApiResponse(responseCode = "401", description = "인증 필요")
    })
    @PostMapping("/decision")
    public DecisionResponse decide(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody DecisionRequest request) {

        Long projectId = agentOrchestrator.resolveProjectId(
                userId,
                request.projectId(),
                request.conversationId()
        );
        AgentPlan plan = decisionAgentService.decide(request.content(), request.aiProvider(), projectId);
        AgentSubmission submission = agentOrchestrator.submit(plan, userId, request.conversationId());
        return new DecisionResponse(
                plan.steps(),
                plan.reasoning(),
                request.aiProvider(),
                submission.taskId(),
                submission.status().name(),
                submission.approvalIds()
        );
    }

    @Operation(
            summary = "태스크 상태 조회",
            description = "비동기 에이전트 작업의 현재 상태를 반환합니다. " +
                          "status가 WAITING_INPUT이면 question 필드를 읽고 /input 엔드포인트로 응답을 보내야 합니다. " +
                          "status가 DONE이면 previewUrl(Docker 프리뷰) 또는 summary(배포/도메인 결과)를 확인하세요."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "태스크 상태 반환"),
            @ApiResponse(responseCode = "404", description = "존재하지 않는 taskId")
    })
    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<TaskStatusResponse> getTaskStatus(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "에이전트 태스크 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
            @PathVariable String taskId) {
        AgentTask task = taskStore.getOwned(taskId, userId);
        if (task == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(new TaskStatusResponse(
                task.taskId(), task.status(), task.previewUrl(), task.summary(), task.error(), task.question()
        ));
    }

    @Operation(
            summary = "사용자 입력 제출",
            description = "태스크가 WAITING_INPUT 상태일 때 에이전트가 요구하는 값을 제출합니다. " +
                          "예: 새 프로젝트의 GitHub 저장소 이름, 연결할 도메인 주소 등."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "입력 수신 완료, 에이전트 재개"),
            @ApiResponse(responseCode = "400", description = "태스크가 WAITING_INPUT 상태가 아님"),
            @ApiResponse(responseCode = "404", description = "존재하지 않는 taskId 또는 입력 대기 만료")
    })
    @PostMapping("/tasks/{taskId}/input")
    public ResponseEntity<Void> submitInput(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "에이전트 태스크 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
            @PathVariable String taskId,
            @Valid @RequestBody TaskInputRequest request) {

        AgentTask task = taskStore.getOwned(taskId, userId);
        if (task == null) return ResponseEntity.notFound().build();
        if (task.status() != com.example.dvely.agent.application.dto.TaskStatus.WAITING_INPUT) {
            return ResponseEntity.badRequest().build();
        }
        boolean accepted = inputWaitStore.supply(taskId, request.value());
        return accepted ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @Operation(summary = "에이전트 태스크 취소", description = "현재 사용자가 소유한 대기 또는 실행 중 태스크를 취소합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "태스크 취소 완료"),
            @ApiResponse(responseCode = "404", description = "존재하지 않거나 소유하지 않은 taskId")
    })
    @DeleteMapping("/tasks/{taskId}")
    public ResponseEntity<Void> cancelTask(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "에이전트 태스크 ID")
            @PathVariable String taskId) {
        if (!taskStore.cancel(taskId, userId)) {
            return ResponseEntity.notFound().build();
        }
        inputWaitStore.cancel(taskId);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "에이전트 세션 종료",
            description = "현재 사용자의 Docker 컨테이너를 종료하고 레지스트리에서 제거합니다. " +
                          "작업이 끝난 후 리소스를 반환할 때 호출하세요."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "컨테이너 삭제 완료"),
            @ApiResponse(responseCode = "404", description = "실행 중인 컨테이너 없음"),
            @ApiResponse(responseCode = "401", description = "인증 필요")
    })
    @DeleteMapping("/session")
    public ResponseEntity<Void> closeSession(@AuthenticationPrincipal Long userId) {
        boolean existed = containerRegistry.find(userId).isPresent();
        containerRegistry.remove(userId);
        return existed
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
