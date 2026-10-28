package com.example.dvely.agent.application.service;

import com.example.dvely.agent.infrastructure.docker.ContainerPaths;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.git.ServerGitProperties;
import com.example.dvely.agent.infrastructure.git.ServerGitPublisher;
import com.example.dvely.agent.infrastructure.git.ServerGitPublisher.Inspection;
import com.example.dvely.agent.infrastructure.git.ServerGitPublisher.ProtectedChangeRequiresApprovalException;
import com.example.dvely.agent.infrastructure.git.ServerGitPublisher.Published;
import com.example.dvely.agent.infrastructure.git.ServerGitPublisher.Request;
import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.change.application.service.ServerPushLedger;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Component;

/**
 * 프리뷰 컨테이너 작업 트리를 서버에서 검토·반영하는 진입점(#523 PR-2). 플래그
 * {@code qeploy.git.server-side.push-enabled} 가 켜졌을 때만 쓰인다.
 *
 * <p>컨테이너에서는 데몬 tar 로 작업 트리만 받는다. 커밋·push·diff 는 서버의 {@link ServerGitPublisher} 가 하고,
 * GitHub 자격은 서버 밖으로 나가지 않는다.</p>
 */
@Component
@RequiredArgsConstructor
public class ServerPreviewBranch {

    static final String PREVIEW = "preview";

    private final ServerGitProperties properties;
    private final ServerGitPublisher publisher;
    private final DockerContainerService dockerService;
    private final UserRepository userRepository;
    private final AuthCommandService authCommandService;
    private final ServerPushLedger pushLedger;

    public boolean enabled() {
        return properties.pushEnabled();
    }

    /** 바뀐 경로·승인이 필요한 경로·diff·트리. 저장소가 없으면(null) 빈 트리 기준이다. push 하지 않는다. */
    public Inspection inspect(String containerId, Long ownerUserId, String repoFullName) {
        if (repoFullName == null) {
            return publisher.inspect(request(containerId, null, null, null, ""), Set.of());
        }
        Credentials credentials = credentials(ownerUserId);
        return publisher.inspect(
                request(containerId, repoFullName, credentials.username(), credentials.token(), ""), Set.of());
    }

    /** 사용자 id 로 자격을 찾아 반영한다(승인 뒤 반영처럼 호출자가 토큰을 들고 있지 않을 때). */
    public Published publish(String containerId, Long ownerUserId, String repoFullName, String taskId,
                             Optional<ObjectId> approvedTree, boolean allowProtected) {
        Credentials credentials = credentials(ownerUserId);
        return publish(containerId, credentials.token(), credentials.username(), repoFullName, taskId, false,
                approvedTree, allowProtected);
    }

    /**
     * 작업 트리를 preview 로 반영한다.
     *
     * @param isNew          새 저장소면 시작용 .gitignore 를 먼저 쓴다(기존 컨테이너 경로와 같은 동작)
     * @param approvedTree   승인한 트리. 있으면 지금 작업 트리가 그것과 같을 때만 push 한다
     * @param allowProtected 보호 경로 변경을 그대로 올려도 되는지(승인 뒤 반영일 때만 true)
     */
    public Published publish(String containerId, String userToken, String username, String repoFullName, String taskId,
                             boolean isNew, Optional<ObjectId> approvedTree, boolean allowProtected) {
        if (isNew) {
            PreviewBranchPushService.writeDefaultGitignore(dockerService, containerId);
        }
        try {
            Published published = publisher.publish(request(containerId, repoFullName, username, userToken, taskId),
                    approvedTree, allowProtected, Set.of());
            published.commit().ifPresent(commit -> pushLedger.recordTask(repoFullName, commit.name(), taskId));
            return published;
        } catch (ProtectedChangeRequiresApprovalException e) {
            // 사용자에게 "작업 중 오류가 발생했습니다: …" 로 보인다. 새 저장소의 첫 반영에는 결과 승인 단계가
            // 없으므로(미연결 프로젝트) 따를 수 있는 안내를 따로 준다.
            throw new ProtectedChangeRequiresApprovalException(e.paths(), isNew
                    ? "새 저장소에 처음 올리는 결과에는 .github 아래 파일을 넣을 수 없습니다. 그 파일을 빼 달라고 다시 요청하거나, "
                            + "저장소를 연결한 뒤 결과 승인을 거쳐 추가해 주세요."
                    : "작업을 다시 요청하면 결과 승인 단계에서 변경 내역을 확인한 뒤 반영됩니다.");
        }
    }

    /**
     * preview 를 {@code baseBranch} 와 같은 내용으로 되돌리는 커밋을 올린다(#532 거절 REVERT). 이미 같으면 올리지 않는다.
     * 플래그와 무관하게 동작하지만, 호출부는 플래그가 켜진 환경에서만 이 옵션을 연다.
     */
    public Optional<ObjectId> revertTo(Long ownerUserId, String repoFullName, String baseBranch, Long approvalId,
                                       String taskId) {
        Credentials credentials = credentials(ownerUserId);
        Optional<ObjectId> commit = publisher.resetTo(
                PreviewBranchPushService.githubUrl(repoFullName),
                new UsernamePasswordCredentialsProvider(credentials.username(), credentials.token()),
                PREVIEW,
                baseBranch,
                "revert: preview 를 " + baseBranch + " 상태로 되돌림 (approval " + approvalId + ")");
        commit.ifPresent(id -> pushLedger.recordRevert(repoFullName, id.name(), taskId));
        return commit;
    }

    private Request request(String containerId, String repoFullName, String username, String userToken, String taskId) {
        return new Request(
                repoFullName == null ? null : PreviewBranchPushService.githubUrl(repoFullName),
                userToken == null ? null : new UsernamePasswordCredentialsProvider(username, userToken),
                PREVIEW,
                List.of(PREVIEW),
                () -> dockerService.openDirectoryArchive(containerId, ContainerPaths.APP_DIR),
                "feat: apply Qeploy Agent task " + taskId);
    }

    /** 만료됐으면 갱신한다. 갱신 뒤에는 다시 읽지 않고 돌려받은 토큰을 쓴다(AuthCommandService 주석 참고). */
    private Credentials credentials(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("유저를 찾을 수 없습니다. userId=" + userId));
        String token = user.isUserAccessTokenExpired()
                ? authCommandService.refreshGithubUserToken(userId)
                : user.getGithubUserAccessToken();
        return new Credentials(user.getUsername(), token);
    }

    private record Credentials(String username, String token) {
    }
}
