package com.example.dvely.agent.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentSubmission;
import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.service.AgentMessageService;
import com.example.dvely.agent.domain.value.AgentType;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.chat.domain.model.Conversation;
import com.example.dvely.chat.domain.repository.ConversationRepository;
import com.example.dvely.project.domain.model.ProjectApprovalPolicy;
import com.example.dvely.project.domain.repository.ProjectApprovalPolicyRepository;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentOrchestratorTest {

    @Test
    void waitsForAllRequiredApprovalsBeforeExecution() {
        AgentPlanExecutor executor = mock(AgentPlanExecutor.class);
        TaskStore taskStore = new TaskStore();
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        ProjectApprovalPolicyRepository policyRepository = mock(ProjectApprovalPolicyRepository.class);
        ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
        AgentMessageService messageService = mock(AgentMessageService.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                executor,
                taskStore,
                projectRepository,
                conversationRepository,
                policyRepository,
                approvalRepository,
                messageService
        );
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(mock(Project.class)));
        when(policyRepository.findByProjectId(11L)).thenReturn(Optional.empty());
        when(approvalRepository.save(any(Approval.class)))
                .thenAnswer(invocation -> {
                    Approval source = invocation.getArgument(0);
                    long id = source.getType() == ApprovalType.CHANGE ? 101L : 102L;
                    return new Approval(
                            id,
                            source.getOwnerUserId(),
                            source.getProjectId(),
                            source.getConversationId(),
                            source.getTaskId(),
                            source.getType(),
                            ApprovalStatus.PENDING,
                            source.getSummary(),
                            LocalDateTime.now(),
                            null
                    );
                });
        AgentPlan plan = new AgentPlan(
                List.of(
                        new AgentStep(AgentType.CODE, Map.of("instruction", "FAQ를 추가한다")),
                        new AgentStep(AgentType.DEPLOY, Map.of("instruction", "최신 버전을 배포한다"))
                ),
                "reason",
                AiProvider.OPENAI,
                11L
        );

        AgentSubmission submission = orchestrator.submit(plan, 1L, null);

        assertThat(submission.status()).isEqualTo(TaskStatus.WAITING_APPROVAL);
        assertThat(submission.approvalIds()).containsExactly(101L, 102L);
        assertThat(taskStore.getOwned(submission.taskId(), 1L).status())
                .isEqualTo(TaskStatus.WAITING_APPROVAL);
        verifyNoInteractions(executor);
        verify(messageService).appendAssistant(
                null,
                "작업 계획을 만들었습니다. 승인 후 실행합니다.\n"
                        + "- [101] CHANGE: FAQ를 추가한다\n"
                        + "- [102] DEPLOYMENT: 최신 버전을 배포한다"
        );
    }

    @Test
    void storesOwnerProjectAndConversationContext() {
        AgentPlanExecutor executor = mock(AgentPlanExecutor.class);
        TaskStore taskStore = new TaskStore();
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        ProjectApprovalPolicyRepository policyRepository = mock(ProjectApprovalPolicyRepository.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                executor,
                taskStore,
                projectRepository,
                conversationRepository,
                policyRepository,
                mock(ApprovalRepository.class),
                mock(AgentMessageService.class)
        );
        Conversation conversation = new Conversation(
                21L,
                1L,
                11L,
                false,
                null,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        Project project = mock(Project.class);
        when(conversationRepository.findByIdAndUserIdAndDeletedFalse(21L, 1L))
                .thenReturn(Optional.of(conversation));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(project));
        when(policyRepository.findByProjectId(11L)).thenReturn(Optional.of(
                new ProjectApprovalPolicy(11L, false, false, false, false)
        ));
        AgentPlan plan = new AgentPlan(
                List.of(new AgentStep(AgentType.CODE, Map.of("instruction", "수정한다"))),
                "reason",
                AiProvider.OPENAI,
                null
        );

        AgentSubmission submission = orchestrator.submit(plan, 1L, 21L);
        String taskId = submission.taskId();

        AgentTask task = taskStore.getOwned(taskId, 1L);
        assertThat(task.ownerUserId()).isEqualTo(1L);
        assertThat(task.projectId()).isEqualTo(11L);
        assertThat(task.conversationId()).isEqualTo(21L);
        assertThat(task.status()).isEqualTo(TaskStatus.RUNNING);
        verify(executor).execute(
                new AgentPlan(
                        List.of(new AgentStep(AgentType.CODE, Map.of("instruction", "수정한다"))),
                        "reason",
                        AiProvider.OPENAI,
                        11L
                ),
                taskId,
                1L
        );
    }

    @Test
    void resolvesConversationProjectBeforeDecision() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                mock(AgentPlanExecutor.class),
                new TaskStore(),
                projectRepository,
                conversationRepository,
                mock(ProjectApprovalPolicyRepository.class),
                mock(ApprovalRepository.class),
                mock(AgentMessageService.class)
        );
        Conversation conversation = new Conversation(
                21L,
                1L,
                11L,
                false,
                null,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        when(conversationRepository.findByIdAndUserIdAndDeletedFalse(21L, 1L))
                .thenReturn(Optional.of(conversation));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(mock(Project.class)));

        assertThat(orchestrator.resolveProjectId(1L, null, 21L)).isEqualTo(11L);
    }
}
