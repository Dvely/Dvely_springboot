package com.example.dvely.project.application.facade;

import com.example.dvely.project.application.command.ProjectCommandService;
import com.example.dvely.project.application.command.dto.ConnectProjectRepositoryCommand;
import com.example.dvely.project.application.command.dto.CreateProjectCommand;
import com.example.dvely.project.application.command.dto.ProjectDeleteMode;
import com.example.dvely.project.application.command.dto.UpdateProjectCommand;
import com.example.dvely.project.application.query.ProjectQueryService;
import com.example.dvely.project.application.result.ActivityLogResult;
import com.example.dvely.project.application.result.CommitResult;
import com.example.dvely.project.application.result.GithubRepositoryResult;
import com.example.dvely.project.application.result.ProjectDetailResult;
import com.example.dvely.project.application.result.ProjectOverviewResult;
import com.example.dvely.project.application.result.ProjectCreationResult;
import com.example.dvely.project.application.result.ProjectRepositoryResult;
import com.example.dvely.project.application.result.ProjectRepositorySettingsResult;
import com.example.dvely.project.application.result.ProjectSummaryResult;
import com.example.dvely.project.application.result.RepositoryHealthResult;
import com.example.dvely.project.application.service.ProjectCreationService;
import com.example.dvely.project.application.service.ProjectRepositorySettingsService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProjectFacade {

    private final ProjectCommandService projectCommandService;
    private final ProjectQueryService projectQueryService;
    private final ProjectCreationService projectCreationService;
    private final ProjectRepositorySettingsService projectRepositorySettingsService;
    // 프로젝트 삭제 후 남는 클라우드 자원(프론트 S3 사이트 버킷) 정리. best-effort — 절대 던지지 않는다.
    private final com.example.dvely.project.application.port.out.ProjectCloudCleanupPort projectCloudCleanupPort;
    // 프론트 S3 HTTPS(CloudFront+ACM) 도메인 정리. 도메인 행은 삭제 트랜잭션이 안 지우므로 별도로 정리.
    private final com.example.dvely.project.application.port.out.ProjectCdnCleanupPort projectCdnCleanupPort;

    public ProjectCreationResult createProject(Long ownerUserId, CreateProjectCommand command) {
        return projectCreationService.create(ownerUserId, command);
    }

    public ProjectRepositoryResult connectRepository(Long ownerUserId,
                                                     Long projectId,
                                                     ConnectProjectRepositoryCommand command) {
        return projectCommandService.connectRepository(ownerUserId, projectId, command);
    }

    public ProjectDetailResult updateProject(Long ownerUserId, Long projectId, UpdateProjectCommand command) {
        return projectCommandService.updateProject(ownerUserId, projectId, command);
    }

    public void deleteProject(Long ownerUserId, Long projectId, ProjectDeleteMode deleteMode) {
        projectCommandService.deleteProject(ownerUserId, projectId, deleteMode);
        // 삭제(트랜잭션)가 끝난 뒤 프론트 S3 사이트 버킷을 정리한다 — 트랜잭션 밖에서(외부 네트워크),
        // best-effort(정리 실패가 이미 끝난 삭제를 되돌리지 않는다). S3 안 쓴 프로젝트는 no-op.
        projectCloudCleanupPort.cleanupFrontendS3(projectId, ownerUserId);
        // 프론트 S3 HTTPS 도메인(CloudFront+ACM)도 정리한다 — Cloudflare 레코드 제거 + CloudFront/인증서
        // 정리 큐잉(리퍼가 마무리). 도메인 없으면 no-op. 역시 best-effort.
        projectCdnCleanupPort.cleanupFrontendCdnDomains(projectId);
    }

    public List<GithubRepositoryResult> getGithubRepositories(Long ownerUserId) {
        return projectQueryService.getGithubRepositories(ownerUserId);
    }

    public List<ProjectSummaryResult> getProjects(Long ownerUserId) {
        return projectQueryService.getProjects(ownerUserId);
    }

    public ProjectDetailResult getProject(Long ownerUserId, Long projectId) {
        return projectQueryService.getProject(ownerUserId, projectId);
    }

    public ProjectOverviewResult getOverview(Long ownerUserId, Long projectId) {
        return projectQueryService.getOverview(ownerUserId, projectId);
    }

    public List<ActivityLogResult> getActivityLogs(Long ownerUserId, Long projectId) {
        return projectQueryService.getActivityLogs(ownerUserId, projectId);
    }

    public List<CommitResult> getCommits(Long ownerUserId, Long projectId) {
        return projectQueryService.getCommits(ownerUserId, projectId);
    }

    public RepositoryHealthResult getRepositoryHealth(Long ownerUserId, Long projectId) {
        return projectQueryService.getRepositoryHealth(ownerUserId, projectId);
    }

    public ProjectRepositorySettingsResult getRepositorySettings(Long ownerUserId, Long projectId) {
        return projectRepositorySettingsService.get(ownerUserId, projectId);
    }

    public void disconnectRepository(Long ownerUserId, Long projectId) {
        projectCommandService.disconnectRepository(ownerUserId, projectId);
    }
}
