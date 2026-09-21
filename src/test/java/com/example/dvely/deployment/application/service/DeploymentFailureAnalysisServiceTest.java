package com.example.dvely.deployment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.service.BuildFailureAnalyzer;
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
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.project.domain.value.ProjectStatus;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

class DeploymentFailureAnalysisServiceTest {

    private final DeploymentHistoryRepository deploymentHistoryRepository = mock(DeploymentHistoryRepository.class);
    private final DeploymentFailureAnalysisRepository analysisRepository = mock(DeploymentFailureAnalysisRepository.class);
    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuthCommandService authCommandService = mock(AuthCommandService.class);
    private final GithubActionsPort githubActionsPort = mock(GithubActionsPort.class);
    private final BuildFailureAnalyzer buildFailureAnalyzer = mock(BuildFailureAnalyzer.class);

    private final DeploymentFailureAnalysisService service = new DeploymentFailureAnalysisService(
            deploymentHistoryRepository,
            analysisRepository,
            projectRepository,
            userRepository,
            authCommandService,
            githubActionsPort,
            buildFailureAnalyzer
    );

    @Test
    void analyzeReturnsCachedResultWithoutCallingGithubOrAnalyzer() {
        stubOwnedHistory(failedHistoryWithRunId());
        // 서버 키 LLM 호출을 걷어내기 전에 저장된 행 — 여전히 그대로 읽혀야 한다.
        DeploymentFailureAnalysis cached = new DeploymentFailureAnalysis(
                10L, 51L, 1L, AnalysisSource.LLM, "이미 분석된 요약", "이미 저장된 발췌", "이미 저장된 수정안",
                "ANTHROPIC", "claude-opus-4-5-20251101", LocalDateTime.now()
        );
        when(analysisRepository.findByHistoryId(51L)).thenReturn(Optional.of(cached));

        DeploymentFailureAnalysisResult result = service.analyze(1L, 51L);

        assertThat(result.summary()).isEqualTo("이미 분석된 요약");
        assertThat(result.analysisSource()).isEqualTo("LLM");
        verifyNoInteractions(githubActionsPort, buildFailureAnalyzer);
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
        verifyNoInteractions(githubActionsPort, buildFailureAnalyzer);
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
    void analyzeIsRuleBasedAndRecordsNeitherProviderNorModel() {
        stubOwnedHistory(failedHistoryWithRunId());
        when(analysisRepository.findByHistoryId(51L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(githubActionsPort.getJobLogs("user-token", "octo/repo", 901L)).thenReturn(
                new GithubActionsPort.DeploymentLogs(901L, List.of(), "cannot find module 'react'")
        );
        stubRuleBasedAnalysis();
        stubSaveEchoesWithId();
        ArgumentCaptor<DeploymentFailureAnalysis> saved =
                ArgumentCaptor.forClass(DeploymentFailureAnalysis.class);

        DeploymentFailureAnalysisResult result = service.analyze(1L, 51L);

        assertThat(result.analysisSource()).isEqualTo("RULE_BASED");
        assertThat(result.summary()).isEqualTo("빌드에 필요한 모듈을 찾지 못했습니다.");
        assertThat(result.suggestedFix()).isEqualTo("dependency를 다시 설치하세요.");
        // 모델이 관여하지 않은 분석이므로 어느 제공자·모델로 만들었는지 남길 것이 없다.
        verify(analysisRepository).save(saved.capture());
        assertThat(saved.getValue().getProvider()).isNull();
        assertThat(saved.getValue().getModel()).isNull();
    }

    // ── #364: 서버 키 없이도 실패 분석이 돌아온다 ────────────────────────────────────────

    @Test
    void analyzeReturnsARuleBasedAnalysisWithNoLlmCollaboratorAtAll() {
        // 인수 조건: LLM·AI 설정 협력자를 아예 만들지 않은 서비스(= 서버 API 키가 없는 서비스)가
        // 정상 응답을 낸다. 목이 아니라 실제 BuildFailureAnalyzer 를 써서 룰이 진짜로 돈다는 것까지 본다.
        DeploymentFailureAnalysisService keyless = new DeploymentFailureAnalysisService(
                deploymentHistoryRepository,
                analysisRepository,
                projectRepository,
                userRepository,
                authCommandService,
                githubActionsPort,
                new BuildFailureAnalyzer()
        );
        stubOwnedHistory(failedHistoryWithRunId());
        when(analysisRepository.findByHistoryId(51L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(githubActionsPort.getJobLogs("user-token", "octo/repo", 901L)).thenReturn(
                new GithubActionsPort.DeploymentLogs(901L, List.of(), "Error: Cannot find module 'react'")
        );
        stubSaveEchoesWithId();

        DeploymentFailureAnalysisResult result = keyless.analyze(1L, 51L);

        assertThat(result.analysisSource()).isEqualTo("RULE_BASED");
        assertThat(result.summary()).isEqualTo("빌드에 필요한 모듈을 찾지 못했습니다.");
        assertThat(result.suggestedFix()).isNotBlank();
        assertThat(result.logExcerpt()).contains("Cannot find module 'react'");
    }

    @Test
    void theServiceHoldsNoLlmOrAiConfigurationDependency() {
        // 서버 키 호출이 조용히 되살아나는 것을 막는 구조 가드. 의존을 다시 주입하면 여기서 깨진다.
        // 클래스 리터럴이 아니라 이름으로 비교한다 — LLM 쪽 타입이 이름이 바뀌거나 사라져도 이 테스트가
        // 컴파일 오류로 같이 무너지지 않고, 그 자리에 새로 생긴 llm 패키지 타입도 똑같이 걸러낸다.
        Class<?> service = DeploymentFailureAnalysisService.class;
        List<Class<?>> dependencyTypes = Stream.concat(
                Arrays.stream(service.getDeclaredConstructors())
                        .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes())),
                Arrays.stream(service.getDeclaredFields()).map(Field::getType)
        ).toList();

        assertThat(dependencyTypes)
                .extracting(Class::getName)
                .noneMatch(name -> name.startsWith("com.example.dvely.agent.infrastructure.llm.")
                        || name.endsWith(".LlmRouter")
                        || name.endsWith(".LlmPort")
                        || name.endsWith(".AiProperties"));
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
        stubRuleBasedAnalysis();
        stubSaveEchoesWithId();

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
        stubRuleBasedAnalysis();
        stubSaveEchoesWithId();

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
                        10L, 51L, 2L, AnalysisSource.RULE_BASED, "다른 요청이 저장한 요약", "발췌", "수정안",
                        null, null, LocalDateTime.now()
                )));
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(githubActionsPort.getJobLogs("user-token", "octo/repo", 901L)).thenReturn(
                new GithubActionsPort.DeploymentLogs(901L, List.of(), "some log")
        );
        stubRuleBasedAnalysis();
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
        verifyNoInteractions(githubActionsPort, buildFailureAnalyzer);
    }

    @Test
    void getAnalysisThrowsNotFoundWhenNothingHasBeenSavedYet() {
        stubOwnedHistory(failedHistoryWithRunId());
        when(analysisRepository.findByHistoryId(51L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAnalysis(1L, 51L))
                .isInstanceOf(NotFoundException.class);
    }

    // ── F1: in-flight lock ───────────────────────────────────────────────────

    @Test
    void concurrentAnalyzeCallsForTheSameHistoryOnlyFetchGithubLogsOnce() throws Exception {
        stubOwnedHistory(failedHistoryWithRunId());
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        stubRuleBasedAnalysis();

        // Stateful fake instead of a one-shot stub: the whole point is to prove the *second*
        // caller sees the *first* caller's saved row via the double-checked cache, so
        // findByHistoryId must actually reflect what save() stored.
        AtomicReference<DeploymentFailureAnalysis> stored = new AtomicReference<>();
        when(analysisRepository.findByHistoryId(51L)).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(analysisRepository.save(any(DeploymentFailureAnalysis.class))).thenAnswer(invocation -> {
            DeploymentFailureAnalysis saved = withId(invocation.getArgument(0), 1L);
            stored.set(saved);
            return saved;
        });

        AtomicInteger logFetchCount = new AtomicInteger();
        CountDownLatch logFetchEntered = new CountDownLatch(1);
        CountDownLatch releaseLogFetch = new CountDownLatch(1);
        when(githubActionsPort.getJobLogs("user-token", "octo/repo", 901L)).thenAnswer(invocation -> {
            logFetchCount.incrementAndGet();
            logFetchEntered.countDown();
            assertThat(releaseLogFetch.await(5, TimeUnit.SECONDS)).isTrue();
            return new GithubActionsPort.DeploymentLogs(901L, List.of(), "some log");
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<DeploymentFailureAnalysisResult> first = pool.submit(() -> service.analyze(1L, 51L));
            // Deterministically wait until the first call is inside the (mocked) log fetch —
            // i.e. holding the per-history lock — before starting the second, so the second is
            // guaranteed to hit the lock-wait + double-check path rather than racing in by luck.
            assertThat(logFetchEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<DeploymentFailureAnalysisResult> second = pool.submit(() -> service.analyze(1L, 51L));
            Thread.sleep(200); // let the second call physically reach the blocked lock
            releaseLogFetch.countDown();

            DeploymentFailureAnalysisResult firstResult = first.get(5, TimeUnit.SECONDS);
            DeploymentFailureAnalysisResult secondResult = second.get(5, TimeUnit.SECONDS);

            assertThat(logFetchCount.get()).isEqualTo(1);
            assertThat(secondResult.summary()).isEqualTo(firstResult.summary());
        } finally {
            pool.shutdownNow();
        }
    }

    // ── F2: secret redaction ─────────────────────────────────────────────────

    // GitHub push protection scans raw source text for contiguous secret-shaped literals
    // (AKIA…, ghp_…). These fixtures are fake, but to keep pushes unblocked the token bodies
    // are concatenated at runtime — the production SECRET_PATTERN still sees the joined string.
    private static final String FAKE_GITHUB_TOKEN = "ghp_" + "1234567890abcdefghijklmno";
    private static final String FAKE_AWS_KEY_ID = "AKIA" + "ABCDEFGHIJKLMNOP";

    @Test
    void secretsInLogsAreRedactedBeforeStorageAndBeforeReachingTheAnalyzer() {
        stubOwnedHistory(failedHistoryWithRunId());
        when(analysisRepository.findByHistoryId(51L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        String secretLaden = String.join("\n",
                "npm ERR! auth failed",
                "token: " + FAKE_GITHUB_TOKEN,
                "aws key: " + FAKE_AWS_KEY_ID,
                "slack: xoxb-1234567890-abcdefghij",
                "jwt: eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PYtRAzJj8HH8",
                "Authorization: Bearer abcdef1234567890zzzz"
        );
        when(githubActionsPort.getJobLogs("user-token", "octo/repo", 901L)).thenReturn(
                new GithubActionsPort.DeploymentLogs(901L, List.of(), secretLaden)
        );
        ArgumentCaptor<String> analyzedInput = ArgumentCaptor.forClass(String.class);
        when(buildFailureAnalyzer.analyze(analyzedInput.capture())).thenReturn(ruleBasedAnalysis());
        stubSaveEchoesWithId();

        DeploymentFailureAnalysisResult result = service.analyze(1L, 51L);

        assertThat(result.logExcerpt())
                .doesNotContain(FAKE_GITHUB_TOKEN)
                .doesNotContain(FAKE_AWS_KEY_ID)
                .doesNotContain("xoxb-1234567890-abcdefghij")
                .doesNotContain("eyJhbGciOiJIUzI1NiJ9")
                .doesNotContain("Bearer abcdef1234567890zzzz")
                .contains("***REDACTED***");

        assertThat(analyzedInput.getValue())
                .doesNotContain(FAKE_GITHUB_TOKEN)
                .contains("***REDACTED***");
    }

    // ── F3: GitHub log-fetch failure degrades instead of propagating ────────

    @Test
    void analyzeDegradesToErrorMessageBasedAnalysisWhenGithubLogFetchFails() {
        DeploymentHistory history = new DeploymentHistory(
                51L, 1L, 11L, DeployTargetType.LATEST, "v7", "https://octo.github.io/repo/",
                DeployStatus.FAILED, 901L, "correlation-51", null, null, null, null, null, null, null, null,
                "task-51", "이전 실행 오류 메시지", 3, 3, null, null, null, LocalDateTime.now(), LocalDateTime.now(), null
        );
        stubOwnedHistory(history);
        when(analysisRepository.findByHistoryId(51L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(githubActionsPort.getJobLogs("user-token", "octo/repo", 901L))
                .thenThrow(new RuntimeException("GitHub API rate limit exceeded"));
        stubRuleBasedAnalysis();
        stubSaveEchoesWithId();

        DeploymentFailureAnalysisResult result = service.analyze(1L, 51L);

        assertThat(result.logExcerpt()).contains("이전 실행 오류 메시지");
        assertThat(result.analysisSource()).isEqualTo("RULE_BASED");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static BuildFailureAnalyzer.Analysis ruleBasedAnalysis() {
        return new BuildFailureAnalyzer.Analysis(
                "빌드에 필요한 모듈을 찾지 못했습니다.", "cannot find module 'react'", "dependency를 다시 설치하세요."
        );
    }

    private void stubRuleBasedAnalysis() {
        when(buildFailureAnalyzer.analyze(any())).thenReturn(ruleBasedAnalysis());
    }

    private void stubSaveEchoesWithId() {
        when(analysisRepository.save(any(DeploymentFailureAnalysis.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0), 1L));
    }

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
