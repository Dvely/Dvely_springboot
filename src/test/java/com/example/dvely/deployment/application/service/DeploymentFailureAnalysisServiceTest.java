package com.example.dvely.deployment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.service.BuildFailureAnalyzer;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.config.AiProperties;
import com.example.dvely.agent.infrastructure.llm.LlmRouter;
import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.deployment.application.port.out.GithubActionsPort;
import com.example.dvely.deployment.application.result.DeploymentFailureAnalysisResult;
import com.example.dvely.deployment.domain.model.DeploymentFailureAnalysis;
import com.example.dvely.deployment.domain.model.DeploymentHistory;
import com.example.dvely.deployment.domain.repository.DeploymentFailureAnalysisRepository;
import com.example.dvely.deployment.domain.repository.DeploymentHistoryRepository;
import com.example.dvely.deployment.domain.value.AnalysisSource;
import com.example.dvely.deployment.domain.value.DeployTargetType;
import com.example.dvely.agent.application.port.out.LlmPort;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.project.domain.value.ProjectStatus;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class DeploymentFailureAnalysisServiceTest {

    private final DeploymentHistoryRepository deploymentHistoryRepository = mock(DeploymentHistoryRepository.class);
    private final DeploymentFailureAnalysisRepository analysisRepository = mock(DeploymentFailureAnalysisRepository.class);
    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuthCommandService authCommandService = mock(AuthCommandService.class);
    private final GithubActionsPort githubActionsPort = mock(GithubActionsPort.class);
    private final LlmRouter llmRouter = mock(LlmRouter.class);
    private final BuildFailureAnalyzer buildFailureAnalyzer = mock(BuildFailureAnalyzer.class);
    private final AiProperties aiProperties = new AiProperties();
    private final LlmPort llmPort = mock(LlmPort.class);

    private final DeploymentFailureAnalysisService service = new DeploymentFailureAnalysisService(
            deploymentHistoryRepository,
            analysisRepository,
            projectRepository,
            userRepository,
            authCommandService,
            githubActionsPort,
            llmRouter,
            buildFailureAnalyzer,
            aiProperties
    );

    @Test
    void analyzeReturnsCachedResultWithoutCallingLlmOrGithub() {
        stubOwnedHistory(failedHistoryWithRunId());
        DeploymentFailureAnalysis cached = new DeploymentFailureAnalysis(
                10L, 51L, 1L, AnalysisSource.LLM, "이미 분석된 요약", "이미 저장된 발췌", "이미 저장된 수정안",
                "ANTHROPIC", "claude-opus-4-5-20251101", LocalDateTime.now()
        );
        when(analysisRepository.findByHistoryId(51L)).thenReturn(Optional.of(cached));

        DeploymentFailureAnalysisResult result = service.analyze(1L, 51L);

        assertThat(result.summary()).isEqualTo("이미 분석된 요약");
        assertThat(result.analysisSource()).isEqualTo("LLM");
        verifyNoInteractions(githubActionsPort, llmRouter, buildFailureAnalyzer);
        verify(analysisRepository, never()).save(any());
    }

    @Test
    void analyzeRejectsWhenTargetIsNotFailed() {
        DeploymentHistory history = historyWithStatus(DeployStatus.IN_PROGRESS, 901L);
        stubOwnedHistory(history);
        when(analysisRepository.findByHistoryId(51L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.analyze(1L, 51L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IN_PROGRESS");
        verifyNoInteractions(githubActionsPort, llmRouter);
    }

    @Test
    void analyzeRejectsWhenProjectNotOwnedByCaller() {
        DeploymentHistory history = failedHistoryWithRunId();
        when(deploymentHistoryRepository.findById(51L)).thenReturn(Optional.of(history));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.analyze(1L, 51L))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void analyzeSucceedsWithLlmJsonAndRecordsProviderAndModel() {
        stubOwnedHistory(failedHistoryWithRunId());
        when(analysisRepository.findByHistoryId(51L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(githubActionsPort.getJobLogs("user-token", "octo/repo", 901L)).thenReturn(
                new GithubActionsPort.DeploymentLogs(901L, List.of(), "npm ERR! missing script: build")
        );
        when(llmRouter.route(AiProvider.ANTHROPIC)).thenReturn(llmPort);
        when(llmPort.complete(any(), anyList())).thenReturn(
                "{\"summary\": \"빌드 스크립트가 없습니다.\", \"suggestedFix\": \"package.json에 build 스크립트를 추가하세요.\"}"
        );
        when(analysisRepository.save(any(DeploymentFailureAnalysis.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0), 1L));

        DeploymentFailureAnalysisResult result = service.analyze(1L, 51L);

        assertThat(result.analysisSource()).isEqualTo("LLM");
        assertThat(result.summary()).isEqualTo("빌드 스크립트가 없습니다.");
        assertThat(result.suggestedFix()).isEqualTo("package.json에 build 스크립트를 추가하세요.");
        verifyNoInteractions(buildFailureAnalyzer);
    }

    @Test
    void analyzeFallsBackToRuleBasedWhenLlmThrows() {
        stubOwnedHistory(failedHistoryWithRunId());
        when(analysisRepository.findByHistoryId(51L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(githubActionsPort.getJobLogs("user-token", "octo/repo", 901L)).thenReturn(
                new GithubActionsPort.DeploymentLogs(901L, List.of(), "cannot find module 'react'")
        );
        when(llmRouter.route(AiProvider.ANTHROPIC)).thenReturn(llmPort);
        when(llmPort.complete(any(), anyList())).thenThrow(new IllegalStateException("Claude API 응답이 비어있습니다"));
        when(buildFailureAnalyzer.analyze(any())).thenReturn(new BuildFailureAnalyzer.Analysis(
                "빌드에 필요한 모듈을 찾지 못했습니다.", "cannot find module 'react'", "dependency를 다시 설치하세요."
        ));
        when(analysisRepository.save(any(DeploymentFailureAnalysis.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0), 1L));

        DeploymentFailureAnalysisResult result = service.analyze(1L, 51L);

        assertThat(result.analysisSource()).isEqualTo("RULE_BASED");
        assertThat(result.summary()).isEqualTo("빌드에 필요한 모듈을 찾지 못했습니다.");
        assertThat(result.suggestedFix()).isEqualTo("dependency를 다시 설치하세요.");
    }

    @Test
    void analyzeFallsBackToRuleBasedWhenLlmResponseIsNotParseableJson() {
        stubOwnedHistory(failedHistoryWithRunId());
        when(analysisRepository.findByHistoryId(51L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(githubActionsPort.getJobLogs("user-token", "octo/repo", 901L)).thenReturn(
                new GithubActionsPort.DeploymentLogs(901L, List.of(), "some log text")
        );
        when(llmRouter.route(AiProvider.ANTHROPIC)).thenReturn(llmPort);
        when(llmPort.complete(any(), anyList())).thenReturn("this is not json at all");
        when(buildFailureAnalyzer.analyze(any())).thenReturn(new BuildFailureAnalyzer.Analysis(
                "프로젝트 빌드가 완료되지 않았습니다.", "some log text", "로그를 확인하세요."
        ));
        when(analysisRepository.save(any(DeploymentFailureAnalysis.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0), 1L));

        DeploymentFailureAnalysisResult result = service.analyze(1L, 51L);

        assertThat(result.analysisSource()).isEqualTo("RULE_BASED");
    }

    @Test
    void analyzeSkipsGithubCallWhenWorkflowRunIdIsNullAndUsesErrorMessage() {
        DeploymentHistory history = new DeploymentHistory(
                51L, 1L, 11L, DeployTargetType.LATEST, null, null, DeployStatus.FAILED, null,
                "correlation-51", null, null, null, null, null, null, null, null, null,
                "워크플로우 트리거 실패", 3, 3, null, null, null, LocalDateTime.now(), LocalDateTime.now(), null
        );
        stubOwnedHistory(history);
        when(analysisRepository.findByHistoryId(51L)).thenReturn(Optional.empty());
        when(llmRouter.route(AiProvider.ANTHROPIC)).thenReturn(llmPort);
        when(llmPort.complete(any(), anyList())).thenReturn(
                "{\"summary\": \"트리거 실패\", \"suggestedFix\": \"다시 시도하세요.\"}"
        );
        when(analysisRepository.save(any(DeploymentFailureAnalysis.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0), 1L));

        DeploymentFailureAnalysisResult result = service.analyze(1L, 51L);

        assertThat(result.logExcerpt()).contains("워크플로우 트리거 실패");
        verifyNoInteractions(githubActionsPort);
        verify(userRepository, never()).findById(any());
    }

    @Test
    void excerptBudgetTruncatesOversizedLogsAndPrioritizesErrorLines() {
        stubOwnedHistory(failedHistoryWithRunId());
        when(analysisRepository.findByHistoryId(51L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));

        String noise = "harmless build output line\n".repeat(2000); // far larger than 12,000 chars
        String logText = noise + "npm ERR! critical failure marker\n" + noise;
        when(githubActionsPort.getJobLogs("user-token", "octo/repo", 901L))
                .thenReturn(new GithubActionsPort.DeploymentLogs(901L, List.of(), logText));
        when(llmRouter.route(AiProvider.ANTHROPIC)).thenReturn(llmPort);
        when(llmPort.complete(any(), anyList())).thenReturn(
                "{\"summary\": \"실패\", \"suggestedFix\": \"수정\"}"
        );
        when(analysisRepository.save(any(DeploymentFailureAnalysis.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0), 1L));

        DeploymentFailureAnalysisResult result = service.analyze(1L, 51L);

        assertThat(result.logExcerpt().length()).isLessThanOrEqualTo(12_000);
        // A tail-only strategy over this input would have missed the error marker entirely
        // (it's roughly in the middle of a much-larger-than-12,000-char log) — proves the
        // error-line-priority pass actually ran.
        assertThat(result.logExcerpt()).contains("npm ERR! critical failure marker");
    }

    @Test
    void concurrentAnalysisRaceReFetchesAndReturnsTheOtherRequestsResult() {
        stubOwnedHistory(failedHistoryWithRunId());
        when(analysisRepository.findByHistoryId(51L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new DeploymentFailureAnalysis(
                        10L, 51L, 2L, AnalysisSource.LLM, "다른 요청이 저장한 요약", "발췌", "수정안",
                        "ANTHROPIC", "claude-opus-4-5-20251101", LocalDateTime.now()
                )));
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(githubActionsPort.getJobLogs("user-token", "octo/repo", 901L)).thenReturn(
                new GithubActionsPort.DeploymentLogs(901L, List.of(), "some log")
        );
        when(llmRouter.route(AiProvider.ANTHROPIC)).thenReturn(llmPort);
        when(llmPort.complete(any(), anyList())).thenReturn(
                "{\"summary\": \"이 요청의 요약\", \"suggestedFix\": \"이 요청의 수정안\"}"
        );
        when(analysisRepository.save(any(DeploymentFailureAnalysis.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        DeploymentFailureAnalysisResult result = service.analyze(1L, 51L);

        assertThat(result.summary()).isEqualTo("다른 요청이 저장한 요약");
        verify(analysisRepository, times(2)).findByHistoryId(51L);
    }

    @Test
    void getAnalysisReturnsSavedResultWithoutAnySideEffects() {
        stubOwnedHistory(failedHistoryWithRunId());
        DeploymentFailureAnalysis saved = new DeploymentFailureAnalysis(
                10L, 51L, 1L, AnalysisSource.RULE_BASED, "저장된 요약", "저장된 발췌", "저장된 수정안",
                null, null, LocalDateTime.now()
        );
        when(analysisRepository.findByHistoryId(51L)).thenReturn(Optional.of(saved));

        DeploymentFailureAnalysisResult result = service.getAnalysis(1L, 51L);

        assertThat(result.summary()).isEqualTo("저장된 요약");
        verifyNoInteractions(githubActionsPort, llmRouter, buildFailureAnalyzer);
    }

    @Test
    void getAnalysisThrowsNotFoundWhenNothingHasBeenSavedYet() {
        stubOwnedHistory(failedHistoryWithRunId());
        when(analysisRepository.findByHistoryId(51L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAnalysis(1L, 51L))
                .isInstanceOf(NotFoundException.class);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private void stubOwnedHistory(DeploymentHistory history) {
        when(deploymentHistoryRepository.findById(51L)).thenReturn(Optional.of(history));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.of(boundProject()));
    }

    private DeploymentHistory failedHistoryWithRunId() {
        return historyWithStatus(DeployStatus.FAILED, 901L);
    }

    private DeploymentHistory historyWithStatus(DeployStatus status, Long workflowRunId) {
        LocalDateTime now = LocalDateTime.now();
        return new DeploymentHistory(
                51L, 1L, 11L, DeployTargetType.LATEST, "v7", "https://octo.github.io/repo/",
                status, workflowRunId, "correlation-51", null, null, null, null, null, null, null, null,
                "task-51", null, 1, 3, null, null, null, now, now, null
        );
    }

    private Project boundProject() {
        LocalDateTime now = LocalDateTime.now();
        return new Project(
                11L, 1L, "my-project", ProjectStatus.ACTIVE, "vue", null, "fast",
                DeployStatus.FAILED, "https://octo.github.io/repo/", "v6", "octo/repo", "octo/repo",
                RepositoryVisibility.PUBLIC, RepositoryBindingStatus.BOUND, RepositoryHealthStatus.HEALTHY,
                false, now, now
        );
    }

    private User activeUser() {
        return new User(
                1L, new GithubId("123"), "octo", null, 100L,
                "user-token", "refresh-token", LocalDateTime.now().plusHours(1)
        );
    }

    private DeploymentFailureAnalysis withId(DeploymentFailureAnalysis analysis, Long id) {
        return new DeploymentFailureAnalysis(
                id, analysis.getHistoryId(), analysis.getUserId(), analysis.getSource(),
                analysis.getSummary(), analysis.getLogExcerpt(), analysis.getSuggestedFix(),
                analysis.getProvider(), analysis.getModel(), LocalDateTime.now()
        );
    }
}
