package com.example.dvely.agent.presentation;

import com.example.dvely.agent.application.dto.AgentPlan;
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

@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentController {

    private final DecisionAgentService  decisionAgentService;
    private final AgentOrchestrator     agentOrchestrator;
    private final TaskStore             taskStore;
    private final UserContainerRegistry containerRegistry;
    private final InputWaitStore        inputWaitStore;

    @PostMapping("/decision")
    public DecisionResponse decide(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody DecisionRequest request) {

        AgentPlan plan   = decisionAgentService.decide(request.content(), request.aiProvider(), request.projectId());
        String    taskId = agentOrchestrator.submitAsync(plan, userId);
        return new DecisionResponse(plan.steps(), plan.reasoning(), request.aiProvider(), taskId);
    }

    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<TaskStatusResponse> getTaskStatus(@PathVariable String taskId) {
        AgentTask task = taskStore.get(taskId);
        if (task == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(new TaskStatusResponse(
                task.taskId(), task.status(), task.previewUrl(), task.summary(), task.error(), task.question()
        ));
    }

    @PostMapping("/tasks/{taskId}/input")
    public ResponseEntity<Void> submitInput(
            @PathVariable String taskId,
            @Valid @RequestBody TaskInputRequest request) {

        AgentTask task = taskStore.get(taskId);
        if (task == null) return ResponseEntity.notFound().build();
        if (task.status() != com.example.dvely.agent.application.dto.TaskStatus.WAITING_INPUT) {
            return ResponseEntity.badRequest().build();
        }
        boolean accepted = inputWaitStore.supply(taskId, request.value());
        return accepted ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/session")
    public ResponseEntity<Void> closeSession(@AuthenticationPrincipal Long userId) {
        boolean existed = containerRegistry.find(userId).isPresent();
        containerRegistry.remove(userId);
        return existed
                ? ResponseEntity.noContent().build()   // 204: 컨테이너 삭제됨
                : ResponseEntity.notFound().build();   // 404: 컨테이너 없었음
    }
}
