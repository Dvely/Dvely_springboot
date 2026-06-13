package com.example.dvely.project.application.query;

import com.example.dvely.approval.application.query.ApprovalQueryService;
import com.example.dvely.approval.application.result.ApprovalResult;
import com.example.dvely.change.application.result.ChangeResult;
import com.example.dvely.change.application.service.ChangeService;
import com.example.dvely.deployment.application.query.DeploymentQueryService;
import com.example.dvely.deployment.application.result.DeploymentHistoryResult;
import com.example.dvely.domainbinding.application.query.DomainBindingQueryService;
import com.example.dvely.domainbinding.application.result.DomainBindingResult;
import com.example.dvely.domainbinding.domain.value.DomainStatus;
import com.example.dvely.domainbinding.domain.value.DomainType;
import com.example.dvely.project.application.port.out.GithubRepositoryPort;
import com.example.dvely.project.application.result.ActivityLogResult;
import com.example.dvely.project.application.result.CommitResult;
import com.example.dvely.project.application.result.GithubRepositoryResult;
import com.example.dvely.project.application.result.ProjectCloudSummaryResult;
import com.example.dvely.project.application.result.ProjectDetailResult;
import com.example.dvely.project.application.result.ProjectDomainSummaryResult;
import com.example.dvely.project.application.result.ProjectInfrastructureSettingsResult;
import com.example.dvely.project.application.result.ProjectOperationActionResult;
import com.example.dvely.project.application.result.ProjectOverviewResult;
import com.example.dvely.project.application.result.ProjectSummaryResult;
import com.example.dvely.project.application.result.RepositoryHealthResult;
import com.example.dvely.project.application.service.ProjectInfrastructureSettingsService;
import com.example.dvely.project.domain.exception.ProjectNotFoundException;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectQueryService {

    private static final int DEFAULT_COMMIT_LIMIT = 20;
    private static final int OVERVIEW_ACTIVITY_LIMIT = 3;

    private final ProjectRepository projectRepository;
    private final GithubRepositoryPort githubRepositoryPort;
    private final DeploymentQueryService deploymentQueryService;
    private final ChangeService changeService;
    private final ApprovalQueryService approvalQueryService;
    private final DomainBindingQueryService domainBindingQueryService;
    private final ProjectInfrastructureSettingsService infrastructureSettingsService;

    public List<GithubRepositoryResult> getGithubRepositories(Long ownerUserId) {
        return githubRepositoryPort.listRepositories(ownerUserId).stream()
                .map(repository -> new GithubRepositoryResult(
                        repository.fullName(),
                        repository.name(),
                        repository.owner(),
                        repository.description(),
                        repository.privateRepository() ? "PRIVATE" : "PUBLIC",
                        repository.defaultBranch(),
                        repository.updatedAt()
                ))
                .toList();
    }

    public List<ProjectSummaryResult> getProjects(Long ownerUserId) {
        return projectRepository.findAllByOwnerUserIdAndDeletedFalseOrderByUpdatedAtDesc(ownerUserId).stream()
                .map(this::toSummaryResult)
                .toList();
    }

    public ProjectDetailResult getProject(Long ownerUserId, Long projectId) {
        Project project = findProject(ownerUserId, projectId);
        return toDetailResult(project);
    }

    public ProjectOverviewResult getOverview(Long ownerUserId, Long projectId) {
        Project project = findProject(ownerUserId, projectId);
        List<CommitResult> commits = getCommits(ownerUserId, projectId);
        CommitResult latestCommit = commits.isEmpty() ? null : commits.get(0);
        RepositoryHealthResult repositoryHealth = getRepositoryHealth(ownerUserId, projectId);
        List<DeploymentHistoryResult> deployments =
                deploymentQueryService.getDeploymentHistories(ownerUserId, projectId);
        List<ChangeResult> changes = changeService.getProjectChanges(ownerUserId, projectId);
        List<ApprovalResult> approvals = approvalQueryService.getProjectApprovals(ownerUserId, projectId);
        List<DomainBindingResult> domains =
                domainBindingQueryService.getProjectDomains(ownerUserId, projectId);
        ProjectInfrastructureSettingsResult infrastructure =
                infrastructureSettingsService.get(ownerUserId, projectId);

        DeploymentHistoryResult latestDeployment = deployments.stream().findFirst().orElse(null);
        DeploymentHistoryResult latestLiveDeployment = deployments.stream()
                .filter(deployment -> DeployStatus.LIVE.name().equals(deployment.status()))
                .findFirst()
                .orElse(null);
        DomainBindingResult currentDomain = selectCurrentDomain(domains);
        DomainBindingResult connectedDomain = selectConnectedDomain(domains);
        ProjectCloudSummaryResult cloudSummary = toCloudSummary(infrastructure);
        List<ActivityLogResult> activityLogs =
                buildActivityLogs(project, deployments, changes, approvals, domains);

        String currentUrl = connectedDomain == null
                ? firstNonBlank(
                        latestLiveDeployment == null ? null : latestLiveDeployment.deployedUrl(),
                        project.getCurrentUrl()
                )
                : toHttpsUrl(connectedDomain.hostname());
        String deployStatus = latestDeployment == null
                ? project.getDeployStatus().name()
                : latestDeployment.status();
        String currentVersion = firstNonBlank(
                latestLiveDeployment == null ? null : latestLiveDeployment.versionLabel(),
                project.getCurrentVersion()
        );

        return new ProjectOverviewResult(
                currentUrl,
                deployStatus,
                currentVersion,
                activityLogs.stream().limit(OVERVIEW_ACTIVITY_LIMIT).toList(),
                latestCommit,
                repositoryHealth,
                toDomainSummary(currentDomain),
                cloudSummary,
                buildOperationActions(project, latestDeployment, currentDomain, cloudSummary)
        );
    }

    public List<ActivityLogResult> getActivityLogs(Long ownerUserId, Long projectId) {
        Project project = findProject(ownerUserId, projectId);
        return buildActivityLogs(
                project,
                deploymentQueryService.getDeploymentHistories(ownerUserId, projectId),
                changeService.getProjectChanges(ownerUserId, projectId),
                approvalQueryService.getProjectApprovals(ownerUserId, projectId),
                domainBindingQueryService.getProjectDomains(ownerUserId, projectId)
        );
    }

    public List<CommitResult> getCommits(Long ownerUserId, Long projectId) {
        Project project = findProject(ownerUserId, projectId);
        if (!project.hasSourceRepository()) {
            return List.of();
        }

        return githubRepositoryPort.getRecentCommits(ownerUserId, project.getSourceRepository(), DEFAULT_COMMIT_LIMIT).stream()
                .map(commit -> new CommitResult(commit.sha(), commit.message(), commit.author(), commit.committedAt()))
                .toList();
    }

    public RepositoryHealthResult getRepositoryHealth(Long ownerUserId, Long projectId) {
        Project project = findProject(ownerUserId, projectId);
        if (!project.hasSourceRepository()) {
            return new RepositoryHealthResult(RepositoryHealthStatus.REPOSITORY_NOT_FOUND.name());
        }

        RepositoryHealthStatus health = githubRepositoryPort.checkRepositoryHealth(ownerUserId, project.getSourceRepository());
        return new RepositoryHealthResult(health.name());
    }

    private Project findProject(Long ownerUserId, Long projectId) {
        return projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, ownerUserId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId, ownerUserId));
    }

    private List<ActivityLogResult> buildActivityLogs(Project project,
                                                      List<DeploymentHistoryResult> deployments,
                                                      List<ChangeResult> changes,
                                                      List<ApprovalResult> approvals,
                                                      List<DomainBindingResult> domains) {
        List<ActivityLogResult> logs = new ArrayList<>();

        deployments.stream()
                .filter(deployment -> latest(deployment.updatedAt(), deployment.triggeredAt()) != null)
                .map(deployment -> new ActivityLogResult(
                        "DEPLOYMENT_" + deployment.status(),
                        "배포 상태가 " + deployment.status() + "로 변경되었습니다"
                                + suffix(deployment.versionLabel()),
                        toOffsetDateTime(latest(deployment.updatedAt(), deployment.triggeredAt()))
                ))
                .forEach(logs::add);

        changes.stream()
                .filter(change -> latest(change.updatedAt(), change.createdAt()) != null)
                .map(change -> new ActivityLogResult(
                        "CHANGE_" + change.status(),
                        "변경사항 상태가 " + change.status() + "로 변경되었습니다"
                                + suffix(change.summary()),
                        toOffsetDateTime(latest(change.updatedAt(), change.createdAt()))
                ))
                .forEach(logs::add);

        approvals.stream()
                .filter(approval -> latest(approval.decidedAt(), approval.createdAt()) != null)
                .map(approval -> new ActivityLogResult(
                        "APPROVAL_" + approval.status(),
                        approval.type() + " 승인 상태가 " + approval.status() + "로 변경되었습니다"
                                + suffix(approval.summary()),
                        toOffsetDateTime(latest(approval.decidedAt(), approval.createdAt()))
                ))
                .forEach(logs::add);

        domains.stream()
                .filter(domain -> latest(domain.updatedAt(), domain.createdAt()) != null)
                .map(domain -> new ActivityLogResult(
                        "DOMAIN_" + domain.status().name(),
                        "도메인 " + domain.hostname() + " 상태가 " + domain.status().name() + "로 변경되었습니다",
                        toOffsetDateTime(latest(domain.updatedAt(), domain.createdAt()))
                ))
                .forEach(logs::add);

        if (project.getCreatedAt() != null) {
            logs.add(new ActivityLogResult(
                    "PROJECT_CREATED",
                    "프로젝트가 생성되었습니다: " + project.getName(),
                    toOffsetDateTime(project.getCreatedAt())
            ));
        }

        return logs.stream()
                .sorted(Comparator.comparing(ActivityLogResult::occurredAt).reversed())
                .toList();
    }

    private DomainBindingResult selectConnectedDomain(List<DomainBindingResult> domains) {
        return domains.stream()
                .filter(domain -> domain.status() == DomainStatus.CONNECTED)
                .min(domainComparator())
                .orElse(null);
    }

    private DomainBindingResult selectCurrentDomain(List<DomainBindingResult> domains) {
        return domains.stream()
                .min(domainComparator())
                .orElse(null);
    }

    private Comparator<DomainBindingResult> domainComparator() {
        return Comparator
                .comparingInt((DomainBindingResult domain) -> domainStatusPriority(domain.status()))
                .thenComparingInt(domain -> domainTypePriority(domain.type()))
                .thenComparing(
                        domain -> latest(domain.updatedAt(), domain.createdAt()),
                        Comparator.nullsLast(Comparator.reverseOrder())
                );
    }

    private int domainStatusPriority(DomainStatus status) {
        return switch (status) {
            case CONNECTED -> 0;
            case VERIFYING -> 1;
            case PROVISIONING -> 2;
            case REQUESTED -> 3;
            case FAILED -> 4;
        };
    }

    private int domainTypePriority(DomainType type) {
        return switch (type) {
            case CUSTOM_DOMAIN -> 0;
            case MANAGED_SUBDOMAIN -> 1;
            case PURCHASABLE_DOMAIN -> 2;
        };
    }

    private ProjectDomainSummaryResult toDomainSummary(DomainBindingResult domain) {
        if (domain == null) {
            return null;
        }
        return new ProjectDomainSummaryResult(
                domain.domainId(),
                domain.hostname(),
                domain.status() == DomainStatus.CONNECTED ? toHttpsUrl(domain.hostname()) : null,
                domain.type().name(),
                domain.status().name(),
                domain.lastCheckedAt()
        );
    }

    private ProjectCloudSummaryResult toCloudSummary(ProjectInfrastructureSettingsResult infrastructure) {
        return new ProjectCloudSummaryResult(
                infrastructure.cloudConnectionId() != null,
                infrastructure.cloudConnectionId(),
                infrastructure.provider(),
                infrastructure.displayName(),
                infrastructure.region(),
                infrastructure.status(),
                infrastructure.lastCheckedAt()
        );
    }

    private List<ProjectOperationActionResult> buildOperationActions(
            Project project,
            DeploymentHistoryResult latestDeployment,
            DomainBindingResult currentDomain,
            ProjectCloudSummaryResult cloudSummary
    ) {
        boolean deploymentRunning = latestDeployment != null
                && (DeployStatus.PENDING.name().equals(latestDeployment.status())
                || DeployStatus.IN_PROGRESS.name().equals(latestDeployment.status()));
        String deploymentReason;
        if (!project.hasSourceRepository()) {
            deploymentReason = "배포 전에 GitHub 저장소 연결이 필요합니다.";
        } else if (deploymentRunning) {
            deploymentReason = "현재 배포 작업이 진행 중입니다.";
        } else if (latestDeployment != null && DeployStatus.FAILED.name().equals(latestDeployment.status())) {
            deploymentReason = "최근 배포가 실패해 재시도할 수 있습니다.";
        } else {
            deploymentReason = "새 배포를 시작할 수 있습니다.";
        }

        String domainReason = currentDomain == null
                ? "연결된 도메인이 없습니다."
                : "현재 도메인 상태: " + currentDomain.status().name();
        String cloudReason = cloudSummary.configured()
                ? "현재 클라우드 연결 상태: " + cloudSummary.status()
                : "프로젝트에 선택된 클라우드 연결이 없습니다.";

        return List.of(
                new ProjectOperationActionResult(
                        "DEPLOY",
                        project.hasSourceRepository() && !deploymentRunning,
                        deploymentReason
                ),
                new ProjectOperationActionResult("MANAGE_DOMAIN", true, domainReason),
                new ProjectOperationActionResult("MANAGE_CLOUD", true, cloudReason),
                new ProjectOperationActionResult("OPEN_AI_AGENT", true, "AI Workspace에서 운영 작업을 요청합니다."),
                new ProjectOperationActionResult("PROJECT_SETTINGS", true, "프로젝트 설정을 관리합니다."),
                new ProjectOperationActionResult("REMOVE_PROJECT", true, "프로젝트 제거 화면으로 이동합니다.")
        );
    }

    private LocalDateTime latest(LocalDateTime primary, LocalDateTime fallback) {
        return primary == null ? fallback : primary;
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private String toHttpsUrl(String hostname) {
        return "https://" + hostname;
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    private String suffix(String value) {
        return value == null || value.isBlank() ? "" : ": " + value;
    }

    private ProjectSummaryResult toSummaryResult(Project project) {
        return new ProjectSummaryResult(
                project.getId(),
                project.getName(),
                project.getDeployStatus().name(),
                project.getCurrentUrl(),
                project.getUpdatedAt()
        );
    }

    private ProjectDetailResult toDetailResult(Project project) {
        return new ProjectDetailResult(
                project.getId(),
                project.getName(),
                project.getStatus().name(),
                project.getStartMode(),
                project.getTemplateType(),
                project.getDraftMode(),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }
}
