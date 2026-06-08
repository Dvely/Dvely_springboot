package com.example.dvely.agent.application.orchestrator;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.chat.domain.model.Conversation;
import com.example.dvely.chat.domain.repository.ConversationRepository;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.project.domain.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AgentOrchestrator {

    private final AgentPlanExecutor      agentPlanExecutor;
    private final TaskStore              taskStore;
    private final ProjectRepository      projectRepository;
    private final ConversationRepository conversationRepository;

    public String submitAsync(AgentPlan plan, Long userId, Long conversationId) {
        Long projectId = resolveProjectId(userId, plan.projectId(), conversationId);
        AgentPlan normalizedPlan = new AgentPlan(plan.steps(), plan.reasoning(), plan.aiProvider(), projectId);
        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        taskStore.save(new AgentTask(
                taskId,
                userId,
                normalizedPlan.projectId(),
                conversationId,
                TaskStatus.PENDING,
                null,
                null,
                null,
                null,
                Instant.now()
        ));
        agentPlanExecutor.execute(normalizedPlan, taskId, userId);
        return taskId;
    }

    public Long resolveProjectId(Long userId, Long requestedProjectId, Long conversationId) {
        Long projectId = requestedProjectId;
        if (conversationId != null) {
            Conversation conversation = conversationRepository
                    .findByIdAndUserIdAndDeletedFalse(conversationId, userId)
                    .orElseThrow(() -> new NotFoundException("대화를 찾을 수 없습니다. conversationId=" + conversationId));
            if (projectId != null && !projectId.equals(conversation.getProjectId())) {
                throw new IllegalArgumentException("대화와 프로젝트가 일치하지 않습니다.");
            }
            projectId = conversation.getProjectId();
        }

        if (projectId != null && projectRepository
                .findByIdAndOwnerUserIdAndDeletedFalse(projectId, userId)
                .isEmpty()) {
            throw new NotFoundException("프로젝트를 찾을 수 없습니다. projectId=" + projectId);
        }

        return projectId;
    }
}
