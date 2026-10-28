package com.example.dvely.agent.application.service;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.git.ServerGitCheckout;
import com.example.dvely.agent.infrastructure.git.ServerGitCheckout.Target;
import com.example.dvely.agent.infrastructure.git.ServerGitProperties;
import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Component;

/**
 * 저장소를 컨테이너에 넣는 진입점(#523 PR-3). 플래그 {@code qeploy.git.server-side.clone-enabled} 가 켜졌고
 * push 도 서버 측일 때만 쓰인다 — 이 경로로 넣은 작업 트리에는 {@code .git} 이 없어 컨테이너 git 으로 push 하는
 * 옛 경로와 함께 쓸 수 없다.
 *
 * <p>모든 경로가 컨테이너의 {@code /workspace} 밑 {@code app} 디렉터리로 넣는다(clone 과 같은 자리).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServerRepositoryCheckout {

    /** 작업 트리 디렉터리의 부모와 이름. {@code ContainerPaths.APP_DIR} 과 같은 자리다. */
    public static final String PARENT = "/workspace";
    public static final String APP = "app";
    /** 프리뷰 작업 트리를 어느 저장소에서 넣었는지. 같은 컨테이너를 다시 준비할 때 작업 중인 트리를 지키는 데 쓴다. */
    public static final String SOURCE_MARKER = ".qeploy-source";

    private final ServerGitProperties properties;
    private final ServerGitCheckout checkout;
    private final DockerContainerService dockerService;
    private final UserRepository userRepository;
    private final AuthCommandService authCommandService;

    public boolean enabled() {
        return properties.cloneEnabled() && properties.pushEnabled();
    }

    /** 프리뷰 작업 트리: preview(없으면 기본 브랜치). 저장소 이름을 표시 파일로 함께 남긴다. */
    public Optional<String> intoPreview(String containerId, Long ownerUserId, String repoFullName) {
        return into(containerId, ownerUserId, repoFullName, Target.branchesThenDefault(ServerPreviewBranch.PREVIEW),
                Map.of(SOURCE_MARKER, repoFullName.getBytes(StandardCharsets.UTF_8)));
    }

    /** 배포 빌드: 기본 브랜치, 또는 지정 태그. */
    public Optional<String> intoBuild(String containerId, Long ownerUserId, String repoFullName, String tag) {
        return into(containerId, ownerUserId, repoFullName,
                tag == null || tag.isBlank() ? Target.branchesThenDefault() : Target.tag(tag), Map.of());
    }

    private Optional<String> into(String containerId, Long ownerUserId, String repoFullName, Target target,
                                  Map<String, byte[]> extras) {
        User user = userRepository.findById(ownerUserId)
                .orElseThrow(() -> new IllegalStateException("유저를 찾을 수 없습니다. userId=" + ownerUserId));
        String token = user.isUserAccessTokenExpired()
                ? authCommandService.refreshGithubUserToken(ownerUserId)
                : user.getGithubUserAccessToken();
        Optional<String> commit = checkout.checkout(
                PreviewBranchPushService.githubUrl(repoFullName),
                new UsernamePasswordCredentialsProvider(user.getUsername(), token),
                target, APP, extras,
                tar -> dockerService.copyArchiveToContainer(containerId, tar, PARENT));
        log.info("[ServerCheckout] 저장소를 작업 공간에 넣음 | repo={} commit={}", repoFullName, commit.orElse("-"));
        return commit;
    }
}
