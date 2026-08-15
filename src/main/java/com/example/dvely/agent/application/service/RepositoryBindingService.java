package com.example.dvely.agent.application.service;

import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.audit.application.AuditEvent;
import com.example.dvely.audit.application.AuditRecorder;
import com.example.dvely.audit.domain.value.AuditAction;
import com.example.dvely.audit.domain.value.AuditActorType;
import com.example.dvely.audit.domain.value.AuditOutcome;
import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.preview.application.result.PreviewSessionInfo;
import com.example.dvely.preview.application.service.PreviewSessionService;
import com.example.dvely.project.application.port.out.GithubRepositoryPort;
import com.example.dvely.project.application.service.RepositoryProvisioningService;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import com.example.dvely.project.domain.value.RepositoryNamePolicy;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * RepositoryBindingGate 가 연 REPOSITORY_BINDING 승인이 승인됐을 때 저장소를 만들고 프로젝트에
 * 연결한 뒤 작업물을 preview 브랜치에 올린다. ResultApprovalService.reflect 의 짝이며 같은
 * 자리에서 호출된다(ApprovalCommandService.approve).
 *
 * ResultApprovalService 와 마찬가지로 approve 의 기존 트랜잭션 안에서 실행된다. 여기서 던지면
 * 승인 상태 변경까지 함께 롤백되므로 "승인은 됐는데 저장소는 안 생긴" 상태가 남지 않는다. 대신
 * GitHub 호출과 docker exec git push 가 트랜잭션을 붙든 채 일어나는데, reflect 가 GitHub 머지를
 * 같은 방식으로 수행하는 것과 동일한 트레이드오프다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepositoryBindingService {

    private final ProjectRepository projectRepository;
    private final PreviewSessionService previewSessionService;
    private final PreviewBranchPushService previewBranchPushService;
    private final GithubRepositoryPort githubRepositoryPort;
    private final RepositoryProvisioningService repositoryProvisioningService;
    private final UserRepository userRepository;
    private final AuthCommandService authCommandService;
    private final AuditRecorder auditRecorder;

    /**
     * @param requestedRepoName 승인 요청 본문으로 들어온 저장소 이름. null 이거나 공백이면 게이트가
     *                          승인 요약에 적어 사용자에게 보여준 후보 이름으로 폴백한다.
     */
    public BindResult bind(Approval approval, String requestedRepoName) {
        Long userId = approval.getOwnerUserId();
        Project project = projectRepository
                .findByIdAndOwnerUserIdAndDeletedFalse(approval.getProjectId(), userId)
                .orElseThrow(() -> new IllegalStateException(
                        "저장소를 연결할 프로젝트를 찾을 수 없습니다. projectId=" + approval.getProjectId()));
        if (project.getRepositoryBindingStatus() == RepositoryBindingStatus.BOUND) {
            // 승인 대기 중에 다른 경로(설정 화면의 POST /projects/{id}/repository, 또는 DEPLOY 스텝)로
            // 이미 연결됐다. 목적은 달성됐으므로 새 저장소를 만들지 않고 그대로 받아들인다.
            log.info("[RepositoryBindingService] 이미 연결된 프로젝트 — 생성 생략 projectId={} repo={}",
                    project.getId(), project.getSourceRepository());
            return new BindResult(project.getSourceRepository(), false, false);
        }

        // 되돌릴 수 없는 GitHub 저장소 생성보다 먼저 확인한다. 게이트가 발동할 땐 세션이 분명히
        // 있었지만 그 뒤로 사람이 결정할 때까지 시간이 흐르고, 프리뷰 컨테이너 TTL 은 기본 30분이라
        // 만료된 채 승인이 도착하는 일이 실제로 생긴다. 이 조회가 생성 뒤에 있으면 그때마다 롤백은
        // DB 만 되돌리고 GitHub 에는 빈 저장소가 남는다(프로젝트는 NOT_BOUND 그대로).
        PreviewSessionInfo previewSession = previewSessionService.findByTaskId(approval.getTaskId())
                .orElseThrow(() -> new IllegalStateException(
                        "저장소 연결에 필요한 PreviewSession이 없습니다. taskId=" + approval.getTaskId()));

        String repoName = resolveRepoName(requestedRepoName, project, userId);
        User user = resolveUser(userId);
        String username = user.getUsername();
        String fullName = username + "/" + repoName;

        boolean created = false;
        if (githubRepositoryPort.repositoryExists(userId, fullName)) {
            log.info("[RepositoryBindingService] 기존 저장소 재사용: {}", fullName);
        } else {
            log.info("[RepositoryBindingService] 신규 저장소 생성: {}", fullName);
            fullName = githubRepositoryPort.createRepository(userId, repoName, RepositoryVisibility.PUBLIC);
            created = true;
        }

        // preview 브랜치 준비·바인딩·저장·감사는 설정 화면의 저장소 연결과 같은 순서라 공용
        // 코어에 맡긴다. 특히 preview 를 기본 브랜치에서 갈라두는 일을 여기서 직접 하면 빠뜨리기
        // 쉽다. 연결 저장이 push 보다 먼저인 것도 그대로다. push 가 실패하면 트랜잭션이 통째로
        // 롤백되므로 순서가 결과를 바꾸지는 않지만, 반대 순서는 push 성공 후 저장 실패 시
        // GitHub 에는 코드가 올라간 채 프로젝트만 NOT_BOUND 로 남는 창을 만든다.
        Project bound = repositoryProvisioningService.bindToProject(
                project,
                userId,
                fullName,
                RepositoryVisibility.PUBLIC,
                created,
                AuditActorType.AGENT,
                approval.getTaskId(),
                null
        );

        // isNew 는 "GitHub 저장소를 방금 만들었는가"가 아니라 "이 컨테이너에 재사용할 .git 이
        // 있는가"다(PreviewBranchPushService#push javadoc). 이 게이트는 정의상 NOT_BOUND
        // 프로젝트에서만 발동하고, CodeAgentService.prepareProjectInContainer 는
        // sourceRepository 가 없으면 clone 없이 그대로 반환하므로 여기서는 .git 이 존재한 적이
        // 없다 — created 를 넘기면 이름이 겹쳐 기존 저장소를 재사용할 때(created=false)
        // .gitignore 작성만 건너뛰어 node_modules/·dist/·.env 가 PUBLIC 저장소로 커밋된다.
        previewBranchPushService.push(
                previewSession.containerId(),
                user.getGithubUserAccessToken(),
                username,
                fullName,
                true,
                approval.getTaskId()
        );
        auditRecorder.record(auditEvent(AuditAction.PREVIEW_BRANCH_PUSHED, userId, bound.getId(), fullName, approval));

        log.info("[RepositoryBindingService] 저장소 연결 완료 | taskId={} projectId={} repo={} created={}",
                approval.getTaskId(), bound.getId(), fullName, created);
        return new BindResult(fullName, created, true);
    }

    private String resolveRepoName(String requested, Project project, Long userId) {
        String candidate = RepositoryNamePolicy.sanitize(requested);
        if (!candidate.isEmpty()) {
            return candidate;
        }
        return RepositoryNamePolicy.forProject(project.getName(), userId);
    }

    private User resolveUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("유저를 찾을 수 없습니다. userId=" + userId));
        if (user.isUserAccessTokenExpired()) {
            authCommandService.refreshGithubUserToken(userId);
            user = userRepository.findById(userId).orElseThrow();
        }
        return user;
    }

    private AuditEvent auditEvent(AuditAction action, Long userId, Long projectId, String repoFullName, Approval approval) {
        return new AuditEvent(
                action,
                AuditOutcome.SUCCEEDED,
                AuditActorType.AGENT,
                userId,
                projectId,
                "REPOSITORY",
                repoFullName,
                approval.getTaskId(),
                null,
                null,
                null
        );
    }

    /**
     * @param pushed false면 승인 대기 중 다른 경로로 이미 연결돼 이번 승인은 아무 GitHub 작업도
     *               하지 않았다는 뜻 — 사용자 메시지가 "새로 만들었다"고 말하면 안 된다.
     */
    public record BindResult(String repositoryFullName, boolean created, boolean pushed) {
    }
}
