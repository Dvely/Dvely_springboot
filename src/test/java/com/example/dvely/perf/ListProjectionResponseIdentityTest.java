package com.example.dvely.perf;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.approval.application.query.ApprovalQueryService;
import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.infrastructure.persistence.repository.SpringDataAgentRunRepository;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import com.example.dvely.change.application.result.ChangeResult;
import com.example.dvely.change.application.service.ChangeService;
import com.example.dvely.change.infrastructure.persistence.entity.ChangeEntity;
import com.example.dvely.change.infrastructure.persistence.repository.SpringDataChangeRepository;
import com.example.dvely.cloudconnection.application.query.CloudConnectionQueryService;
import com.example.dvely.cloudconnection.application.result.CloudConnectionResult;
import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.domain.value.CloudProvider;
import com.example.dvely.deployment.application.query.DeploymentQueryService;
import com.example.dvely.deployment.application.result.DeploymentCandidateResult;
import com.example.dvely.deployment.application.result.DeploymentHistoryResult;
import com.example.dvely.deployment.application.result.VersionResult;
import com.example.dvely.deployment.infrastructure.persistence.entity.DeploymentHistoryEntity;
import com.example.dvely.deployment.infrastructure.persistence.repository.SpringDataDeploymentHistoryRepository;
import com.example.dvely.environment.application.query.EnvironmentVariableQueryService;
import com.example.dvely.environment.application.result.EnvironmentVariableResult;
import com.example.dvely.environment.domain.model.EnvironmentVariable;
import com.example.dvely.environment.domain.repository.EnvironmentVariableRepository;
import com.example.dvely.environment.domain.value.EnvironmentScope;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import com.example.dvely.provisioning.application.query.DatabaseProvisioningQueryService;
import com.example.dvely.provisioning.application.result.ProvisionedDatabaseResult;
import com.example.dvely.provisioning.domain.model.ProvisionedDatabase;
import com.example.dvely.provisioning.domain.repository.ProvisionedDatabaseRepository;
import com.example.dvely.provisioning.domain.value.DatabaseEngine;
import com.example.dvely.provisioning.domain.value.ProvisionMethod;
import com.example.dvely.provisioning.domain.value.ProvisionOrigin;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * U6(#341) 의 완료 기준: <b>프로젝션·전용 쿼리로 바꾼 목록들의 응답이 한 글자도 달라지지 않는다.</b>
 *
 * <p>방식은 전부 같다 — 실 MySQL 에 행을 심고, <b>예전 경로</b>(엔티티를 통째로 읽어 매핑)와
 * <b>새 경로</b>(프로젝션/전용 쿼리)의 결과를 각각 JSON 으로 직렬화해 문자열로 비교한다. 예전 매핑은
 * 이 파일 안에 그대로 옮겨 적어 <b>계약을 리터럴로 고정</b>한다 — 본문 코드가 바뀌어도 이 테스트가
 * 같이 바뀌지 않도록 하는 것이 요점이다.</p>
 *
 * <p>비밀 컬럼을 다루는 목록(6-5)에는 동일성 비교에 더해 <b>평문이 응답 JSON 어디에도 없다</b>는
 * 검사를 붙인다. 프로젝션이 실수로 비밀 컬럼을 포함하면 그게 곧 유출이므로, 성능 회귀가 아니라
 * 보안 회귀로 잡히게 한다.</p>
 *
 * <p>정렬 tiebreaker 주의: 바뀐 쿼리들은 (created_at desc, id desc) 처럼 id 를 덧붙였다. 원래는
 * created_at 만이었고 그 컬럼이 DATETIME(초) 라 같은 초의 행 순서가 <b>비결정적</b>이었다(즉 "예전
 * 순서" 라는 것이 애초에 하나로 정해지지 않았다). 그래서 이 테스트는 심는 행마다 created_at 을
 * 다르게 줘서 비교 대상이 유일하게 정해지도록 한다.</p>
 */
@SpringBootTest
class ListProjectionResponseIdentityTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private TaskStore taskStore;
    @Autowired private ChangeService changeService;
    @Autowired private SpringDataChangeRepository changeRepository;
    @Autowired private DeploymentQueryService deploymentQueryService;
    @Autowired private SpringDataDeploymentHistoryRepository deploymentHistoryRepository;
    @Autowired private CloudConnectionQueryService cloudConnectionQueryService;
    @Autowired private CloudConnectionRepository cloudConnectionRepository;
    @Autowired private EnvironmentVariableQueryService environmentVariableQueryService;
    @Autowired private EnvironmentVariableRepository environmentVariableRepository;
    @Autowired private DatabaseProvisioningQueryService databaseProvisioningQueryService;
    @Autowired private ProvisionedDatabaseRepository provisionedDatabaseRepository;
    @Autowired private ApprovalQueryService approvalQueryService;
    @Autowired private SpringDataAgentRunRepository agentRunRepository;

    // ---------------------------------------------------------------- 6-1 변경 목록

    @Test
    void changeListResponseIsUnchangedAfterDroppingDiffText() {
        Long userId = seedUser();
        Long projectId = seedProject(userId);
        // diff_text 를 크게 심는 것이 핵심이다 — 예전 경로는 이걸 전부 읽어 버렸다.
        String bigDiff = "diff --git a/x b/x\n".repeat(20_000);
        for (int i = 0; i < 5; i++) {
            seedChange(userId, projectId, i, bigDiff);
        }

        List<ChangeResult> before = changeRepository.findAll().stream()
                .filter(change -> projectId.equals(change.getProjectId())
                        && userId.equals(change.getOwnerUserId()))
                .sorted(Comparator.comparing(ChangeEntity::getCreatedAt).reversed()
                        .thenComparing(Comparator.comparing(ChangeEntity::getId).reversed()))
                .map(ChangeEntity::toResult)
                .toList();
        List<ChangeResult> after = changeService.getProjectChanges(userId, projectId);

        assertThat(before).hasSize(5);
        assertThat(json(after)).isEqualTo(json(before));
    }

    // ---------------------------------------------------------------- 6-2 배포 이력

    @Test
    void deploymentHistoryVersionAndCandidateResponsesAreUnchanged() {
        Long userId = seedUser();
        Long projectId = seedProject(userId);
        seedDeployment(userId, projectId, "v1", "LIVE", 1);
        seedDeployment(userId, projectId, "v2", "FAILED", 2);
        seedDeployment(userId, projectId, "v2", "LIVE", 3);
        seedDeployment(userId, projectId, null, "LIVE", 4);
        seedDeployment(userId, projectId, "   ", "LIVE", 5);

        List<DeploymentHistoryEntity> rows = deploymentHistoryRepository.findAll().stream()
                .filter(row -> projectId.equals(row.getProjectId()))
                .sorted(Comparator.comparing(DeploymentHistoryEntity::getTriggeredAt).reversed()
                        .thenComparing(Comparator.comparing(DeploymentHistoryEntity::getId).reversed()))
                .toList();

        // ① 이력 목록 — 예전 매핑을 그대로 옮겨 적었다.
        List<DeploymentHistoryResult> historiesBefore = rows.stream()
                .map(h -> new DeploymentHistoryResult(
                        h.getId(), h.getProjectId(), h.getDeployTargetType(), h.getVersionLabel(),
                        h.getDeployedUrl(), h.getStatus(), h.getFailureCode(), h.getErrorMessage(),
                        h.getTriggeredAt(), h.getUpdatedAt(), h.getRetriedFromHistoryId()))
                .toList();
        assertThat(json(deploymentQueryService.getDeploymentHistories(userId, projectId)))
                .isEqualTo(json(historiesBefore));

        // ② 버전 목록 — version_label 있는 것만, 라벨별 최신 1건.
        List<VersionResult> versionsBefore = latestPerLabel(rows).stream()
                .map(h -> new VersionResult(
                        h.getId(), h.getVersionLabel(), h.getCommitSha(), h.getTitle(), h.getStatus(),
                        h.getMergedAt() == null ? h.getTriggeredAt() : h.getMergedAt()))
                .toList();
        assertThat(json(deploymentQueryService.getVersions(userId, projectId)))
                .isEqualTo(json(versionsBefore));
        assertThat(versionsBefore).hasSize(2); // v1 · v2 (라벨 없는/공백인 두 건은 빠진다)

        // ③ 배포 후보 — ② 에 "LIVE 만" 이 더 붙는다.
        List<DeploymentCandidateResult> candidatesBefore =
                latestPerLabel(rows.stream().filter(h -> "LIVE".equals(h.getStatus())).toList()).stream()
                        .map(h -> new DeploymentCandidateResult(
                                h.getId(), h.getVersionLabel(), h.getCommitSha(), h.getTitle(),
                                h.getStatus(), h.getDeployedUrl(), h.getUpdatedAt()))
                        .toList();
        assertThat(json(deploymentQueryService.getDeploymentCandidates(userId, projectId)))
                .isEqualTo(json(candidatesBefore));
    }

    /** 예전 코드의 "라벨별 최신 1건" 을 그대로 옮긴 것. 최신순 입력을 전제한다. */
    private List<DeploymentHistoryEntity> latestPerLabel(List<DeploymentHistoryEntity> rows) {
        Map<String, DeploymentHistoryEntity> latest = rows.stream()
                .filter(h -> h.getVersionLabel() != null && !h.getVersionLabel().isBlank())
                .collect(Collectors.toMap(
                        DeploymentHistoryEntity::getVersionLabel, h -> h, (existing, later) -> existing));
        return latest.values().stream()
                .sorted(Comparator.comparing(DeploymentHistoryEntity::getTriggeredAt).reversed())
                .toList();
    }

    // ---------------------------------------------------------------- 6-5 비밀 컬럼

    @Test
    void cloudConnectionListResponseIsUnchangedAndLeaksNoSecret() {
        Long userId = seedUser();
        String secretKey = "SECRET-" + userId + "-aws-secret-access-key";
        String sessionToken = "SESSION-" + userId + "-token";
        String serviceAccountKeyJson = "{\"private_key\":\"SAKEY-" + userId + "\"}";
        Long awsId = cloudConnectionRepository.save(new CloudConnection(
                userId, CloudProvider.AWS, "prod", "123456789012", "ap-northeast-2", null,
                "ACCESS_KEY", "AKIAEXAMPLE", secretKey, sessionToken, null, null, null, null)).getId();
        Long gcpId = cloudConnectionRepository.save(new CloudConnection(
                userId, CloudProvider.GCP, "gcp-prod", null, "asia-northeast3", null,
                null, null, null, null, "SERVICE_ACCOUNT_KEY", serviceAccountKeyJson,
                "gcp-project", "sa@example.iam.gserviceaccount.com")).getId();
        // created_at 이 DATETIME(초) 라 같은 초에 심으면 순서가 유일하게 정해지지 않는다(클래스
        // javadoc 참고). 비교가 성립하도록 두 행의 created_at 을 벌려 둔다.
        jdbc.update("update cloud_connections set created_at = ? where cloud_connection_id = ?",
                LocalDateTime.now().minusMinutes(2), awsId);
        jdbc.update("update cloud_connections set created_at = ? where cloud_connection_id = ?",
                LocalDateTime.now().minusMinutes(1), gcpId);

        // 예전 경로: 엔티티를 통째로 읽어(= 비밀 세 컬럼을 행마다 AES 복호화해) != null 로 바꿨다.
        List<CloudConnectionResult> before = cloudConnectionRepository
                .findAllByOwnerUserIdOrderByCreatedAtDesc(userId).stream()
                .map(c -> new CloudConnectionResult(
                        c.getId(), c.getProvider(), c.getDisplayName(), c.getAccountId(), c.getRegion(),
                        c.getRoleArn(), c.getAwsCredentialType(), c.getAccessKeyId(),
                        c.getSecretAccessKey() != null, c.getSessionToken() != null,
                        c.getGcpCredentialType(), c.getServiceAccountKeyJson() != null,
                        c.getGcpProjectId(), c.getServiceAccountEmail(), c.getStatus(),
                        c.getLastCheckedAt(), c.getCreatedAt(), c.getUpdatedAt()))
                .toList();
        String after = json(cloudConnectionQueryService.getCloudConnections(userId));

        assertThat(before).hasSize(2);
        assertThat(after).isEqualTo(json(before));
        assertThat(after)
                .as("목록 응답에 비밀 평문이 실리면 성능 회귀가 아니라 유출이다")
                .doesNotContain(secretKey)
                .doesNotContain(sessionToken)
                .doesNotContain("SAKEY-" + userId);
    }

    @Test
    void environmentVariableListResponseIsUnchangedAndLeaksNoSecret() {
        Long userId = seedUser();
        Long projectId = seedProject(userId);
        String secretValue = "sk_live_" + projectId + "_must_never_appear";
        environmentVariableRepository.save(new EnvironmentVariable(
                projectId, EnvironmentScope.PRODUCTION, "STRIPE_SECRET_KEY", secretValue, true));
        environmentVariableRepository.save(new EnvironmentVariable(
                projectId, EnvironmentScope.PREVIEW, "API_BASE_URL", "https://api.example.com", false));

        // 예전 경로: 엔티티를 통째로 읽어(= secret 값까지 복호화해) 응답에서 null 로 지웠다.
        List<EnvironmentVariableResult> before = environmentVariableRepository
                .findByProjectIdOrderByScopeAscKeyAsc(projectId).stream()
                .map(v -> new EnvironmentVariableResult(
                        v.getId(), v.getScope().name(), v.getKey(),
                        v.isSecret() ? null : v.getValue(), v.isSecret(),
                        v.getCreatedAt(), v.getUpdatedAt()))
                .toList();
        String after = json(environmentVariableQueryService.getVariables(userId, projectId, null));

        assertThat(before).hasSize(2);
        assertThat(after).isEqualTo(json(before));
        assertThat(after).doesNotContain(secretValue);
        // 비밀이 아닌 값은 예전처럼 그대로 나간다 — 두 방향을 같이 못박는다.
        assertThat(after).contains("https://api.example.com");
    }

    @Test
    void provisionedDatabaseListResponseIsUnchangedAndLeaksNoPassword() {
        Long userId = seedUser();
        Long projectId = seedProject(userId);
        String password = "pw_" + projectId + "_must_never_appear";
        ProvisionedDatabase ready = ProvisionedDatabase.pending(
                projectId, ProvisionMethod.LOCAL, DatabaseEngine.MYSQL, ProvisionOrigin.MANUAL);
        ready = provisionedDatabaseRepository.save(ready);
        ready.markReady("res-1", "127.0.0.1", 13306, "appdb", "appuser", password,
                LocalDateTime.now().plusMinutes(30));
        provisionedDatabaseRepository.save(ready);

        // 예전 경로: 엔티티를 통째로 읽어(= password 를 행마다 복호화해) 응답에서 버렸다.
        List<ProvisionedDatabaseResult> before = provisionedDatabaseRepository.findById(ready.getId())
                .stream()
                .map(d -> new ProvisionedDatabaseResult(
                        d.getId(), d.getProjectId(), d.getMethod().name(), d.getEngine().name(),
                        d.getOrigin().name(), d.getStatus().name(), d.getHost(), d.getPort(),
                        d.getDatabaseName(), d.getUsername(), d.getExpiresAt(),
                        d.getFailureCode() == null ? null : d.getFailureCode().name(),
                        d.getErrorMessage(), d.getCreatedAt(), d.getUpdatedAt()))
                .toList();
        String after = json(databaseProvisioningQueryService.list(userId, projectId));

        assertThat(before).hasSize(1);
        assertThat(after).isEqualTo(json(before));
        assertThat(after).doesNotContain(password);
    }

    // ---------------------------------------------------------------- 6-6 살아있는 태스크

    @Test
    void activeRunProjectionReturnsTheSameTaskIdAndStatusTheEntityHolds() {
        Long userId = seedUser();
        Long conversationId = seedConversation(userId, seedProject(userId));
        String taskId = "u6-active-" + System.nanoTime();
        taskStore.save(new AgentTask(
                taskId, userId, null, conversationId, TaskStatus.QUEUED,
                null, null, null, null, Instant.now()));

        var view = agentRunRepository.findActiveRuns(
                conversationId, userId, List.of("DONE", "FAILED", "CANCELLED"),
                org.springframework.data.domain.PageRequest.of(0, 1));

        // 프로젝션 별칭(as taskId / as status)이 실제로 매핑되는지는 실 쿼리로만 확인된다.
        assertThat(view).singleElement().satisfies(row -> {
            assertThat(row.getTaskId()).isEqualTo(taskId);
            assertThat(row.getStatus()).isEqualTo(TaskStatus.QUEUED.name());
        });
        assertThat(taskStore.findActiveTask(conversationId, userId))
                .contains(new TaskStore.ActiveTask(taskId, TaskStatus.QUEUED));
    }

    // ---------------------------------------------------------------- 6-4 승인 목록

    @Test
    void approvalListStaysTheSameWhenItFitsUnderTheCap() {
        Long userId = seedUser();
        Long projectId = seedProject(userId);
        for (int i = 0; i < 3; i++) {
            seedApproval(userId, projectId, i);
        }

        var page = approvalQueryService.getProjectApprovals(userId, projectId, null, null);

        assertThat(page.items()).hasSize(3);
        assertThat(page.nextCursor())
                .as("상한 아래면 커서가 붙지 않는다 — 기존 FE 는 예전과 똑같이 본다")
                .isNull();
        assertThat(json(page.items()))
                .isEqualTo(json(approvalQueryService.getProjectApprovals(userId, projectId)));
    }

    // ---------------------------------------------------------------- 시딩

    private String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Long seedUser() {
        return userRepository.save(
                new User(new GithubId("u6-identity-" + System.nanoTime()), "octo", null)).getId();
    }

    private Long seedProject(Long userId) {
        return projectRepository.save(new Project(
                userId, "u6-identity", "scratch", null, "fast", RepositoryVisibility.PUBLIC)).getId();
    }

    private Long seedConversation(Long userId, Long projectId) {
        jdbc.update("insert into chat_sessions (user_id, project_id, title) values (?, ?, ?)",
                userId, projectId, "u6");
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    /** created_at 을 행마다 다르게 준다 — 비교 대상 순서가 유일하게 정해지도록(클래스 javadoc 참고). */
    private void seedChange(Long userId, Long projectId, int index, String diff) {
        String taskId = "u6-change-" + System.nanoTime() + "-" + index;
        taskStore.save(new AgentTask(
                taskId, userId, projectId, null, TaskStatus.DONE,
                null, null, null, null, Instant.now()));
        String previewSessionId = seedPreviewSession(userId, projectId, taskId);
        jdbc.update("""
                insert into project_changes
                    (user_id, project_id, task_id, preview_session_id, status, summary, diff_text,
                     created_at, updated_at, pr_number, merge_commit_sha)
                values (?, ?, ?, ?, 'MERGED', ?, ?, ?, ?, ?, ?)
                """,
                userId, projectId, taskId, previewSessionId, "요약 " + index, diff,
                LocalDateTime.now().minusMinutes(index), LocalDateTime.now().minusMinutes(index),
                100 + index, "sha-" + index);
    }

    /** project_changes.preview_session_id 에 FK 가 걸려 있어 대응 행이 먼저 있어야 한다. */
    private String seedPreviewSession(Long userId, Long projectId, String taskId) {
        String sessionId = java.util.UUID.randomUUID().toString();
        jdbc.update("""
                insert into preview_sessions
                    (preview_session_id, access_token, user_id, project_id, task_id, container_id,
                     host_port, status, public_url, expires_at, last_accessed_at)
                values (?, ?, ?, ?, ?, 'container', 30000, 'RUNNING', 'http://localhost:30000', ?, ?)
                """,
                sessionId, sessionId.replace("-", ""), userId, projectId, taskId,
                LocalDateTime.now().plusMinutes(30), LocalDateTime.now());
        return sessionId;
    }

    private void seedDeployment(Long userId, Long projectId, String versionLabel, String status, int index) {
        jdbc.update("""
                insert into deployment_histories
                    (user_id, project_id, deploy_target_type, version_label, deployed_url, status,
                     correlation_id, commit_sha, title, description, error_message, triggered_at, updated_at)
                values (?, ?, 'LATEST', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                userId, projectId, versionLabel, "https://example.com/" + index, status,
                "corr-" + System.nanoTime() + "-" + index, "commit-" + index, "제목 " + index,
                "설명 ".repeat(500), "에러 ".repeat(200),
                LocalDateTime.now().minusMinutes(index), LocalDateTime.now().minusMinutes(index));
    }

    private void seedApproval(Long userId, Long projectId, int index) {
        jdbc.update("""
                insert into approvals
                    (user_id, project_id, approval_type, status, summary, created_at)
                values (?, ?, 'CHANGE', 'PENDING', ?, ?)
                """,
                userId, projectId, "승인 " + index, LocalDateTime.now().minusMinutes(index));
    }
}
