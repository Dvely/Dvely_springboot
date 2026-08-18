package com.example.dvely.preview.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.project.domain.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PreviewWorkspaceServiceTest {

    private static final String CONTAINER_ID = "container-1";

    private final DockerContainerService dockerService = mock(DockerContainerService.class);
    private final PreviewWorkspaceService service = new PreviewWorkspaceService(
            dockerService,
            mock(ProjectRepository.class),
            mock(UserRepository.class),
            mock(AuthCommandService.class)
    );

    // startPreviewServer 가 반환하면 호출자가 세션을 ACTIVE 로 올린다. 그래서 "반환됐다 = 열면
    // 보인다"가 성립해야 한다. 예전에는 nohup 으로 띄우고 sleep 3 만 했는데, 그 3초가 모자라
    // ACTIVE 인데 첫 요청이 502 가 되는 일이 있었다(2026-08-15 dev 실측).

    /**
     * clone 명령줄에 토큰이 실리면 두 곳으로 샌다. 하나는 컨테이너의 프로세스 목록 — 이 컨테이너는
     * 에이전트가 만든 코드를 실행하는 곳이라 그 코드가 ps 로 사용자의 GitHub 토큰을 가져갈 수 있다.
     * 다른 하나는 서버 로그 — DockerContainerService#exec 가 명령 전문을 log.debug 로 찍는다.
     *
     * 자격 증명은 credential helper 가 파일로 공급하므로 URL 에 넣을 이유가 없다.
     */
    @Test
    void cloneCommandNeverCarriesTheTokenOnTheCommandLine() {
        DockerContainerService docker = mock(DockerContainerService.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        UserRepository users = mock(UserRepository.class);
        PreviewWorkspaceService target =
                new PreviewWorkspaceService(docker, projects, users, mock(AuthCommandService.class));

        when(projects.findByIdAndOwnerUserId(11L, 7L)).thenReturn(java.util.Optional.of(boundProject()));
        when(users.findById(7L)).thenReturn(java.util.Optional.of(tokenUser()));
        // .git 이 없는 상태 → 처음 clone 경로를 탄다.
        // exec 결과에 .trim() 을 부르는 지점이 여러 곳이라 기본값을 준다.
        when(docker.exec(eq(CONTAINER_ID), anyString())).thenReturn("");
        when(docker.exec(eq(CONTAINER_ID), contains("/.git ]"))).thenReturn("no");

        target.prepareProject(CONTAINER_ID, 7L, 11L);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(docker, org.mockito.Mockito.atLeastOnce()).exec(eq(CONTAINER_ID), captor.capture());
        var cloneCommands = captor.getAllValues().stream()
                .filter(command -> command.contains("git clone"))
                .toList();

        assertThat(cloneCommands).isNotEmpty();
        assertThat(cloneCommands).allSatisfy(command -> {
            assertThat(command).doesNotContain(TOKEN);
            assertThat(command).contains("https://github.com/octo/repo.git");
            // 자격 증명 공급이 실패하면 git 이 프롬프트에서 멈춘다 — 즉시 실패시켜야 한다.
            assertThat(command).contains("GIT_TERMINAL_PROMPT=0");
        });
    }

    @Test
    void credentialsAreWrittenToAFileNotPassedAsArguments() {
        // 토큰은 base64 로 감싸 파일에만 들어가야 한다. 이 명령 자체에는 평문 토큰이 없다.
        DockerContainerService docker = mock(DockerContainerService.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        UserRepository users = mock(UserRepository.class);
        PreviewWorkspaceService target =
                new PreviewWorkspaceService(docker, projects, users, mock(AuthCommandService.class));
        when(projects.findByIdAndOwnerUserId(11L, 7L)).thenReturn(java.util.Optional.of(boundProject()));
        when(users.findById(7L)).thenReturn(java.util.Optional.of(tokenUser()));
        // exec 결과에 .trim() 을 부르는 지점이 여러 곳이라 기본값을 준다.
        when(docker.exec(eq(CONTAINER_ID), anyString())).thenReturn("");
        when(docker.exec(eq(CONTAINER_ID), contains("/.git ]"))).thenReturn("no");

        target.prepareProject(CONTAINER_ID, 7L, 11L);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(docker, org.mockito.Mockito.atLeastOnce()).exec(eq(CONTAINER_ID), captor.capture());
        assertThat(captor.getAllValues()).noneSatisfy(command -> assertThat(command).contains(TOKEN));
        assertThat(captor.getAllValues())
                .anySatisfy(command -> assertThat(command).contains("credential.helper"));
    }

    @Test
    void returnsOnceTheServePortAnswers() {
        stubBuildDir("/workspace/app/dist");
        when(dockerService.exec(eq(CONTAINER_ID), contains("serve_ready")))
                .thenReturn("serve_ready=yes\nINFO  Accepting connections at http://localhost:3000");

        service.startPreviewServer(CONTAINER_ID);

        // 고정 대기가 아니라 포트를 직접 확인해야 한다.
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(dockerService, org.mockito.Mockito.atLeastOnce()).exec(eq(CONTAINER_ID), captor.capture());
        assertThat(captor.getAllValues())
                .anySatisfy(command -> assertThat(command).contains("serve_ready"));
        assertThat(captor.getAllValues())
                .noneSatisfy(command -> assertThat(command).isEqualTo("sleep 3 && cat /tmp/serve.log"));
    }

    @Test
    void throwsWhenThePortNeverAnswers() {
        // 포트가 안 열린 채 반환하면 세션이 ACTIVE 가 되고 사용자는 502 를 본다. 던져야 호출자가
        // 세션을 FAILED 로 닫고 FE 가 실패 화면을 띄운다.
        stubBuildDir("/workspace/app/dist");
        when(dockerService.exec(eq(CONTAINER_ID), contains("serve_ready")))
                .thenReturn("serve_ready=no\nError: listen EADDRINUSE");

        assertThatThrownBy(() -> service.startPreviewServer(CONTAINER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("제한 시간");
    }

    @Test
    void throwsWhenTheProbeProducesNoOutput() {
        // exec 이 빈 문자열이나 null 을 돌려주는 경우에도 성공으로 넘어가면 안 된다.
        stubBuildDir("/workspace/app/dist");
        when(dockerService.exec(eq(CONTAINER_ID), contains("serve_ready"))).thenReturn("");

        assertThatThrownBy(() -> service.startPreviewServer(CONTAINER_ID))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void servesTheDetectedBuildOutputDirectory() {
        stubBuildDir("/workspace/app/build");
        when(dockerService.exec(eq(CONTAINER_ID), contains("serve_ready"))).thenReturn("serve_ready=yes");

        service.startPreviewServer(CONTAINER_ID);

        verify(dockerService).exec(CONTAINER_ID,
                "nohup npx serve -s /workspace/app/build -l 3000 > /tmp/serve.log 2>&1 &");
    }

    private static final String TOKEN = "ghu_SECRETtokenVALUE123";

    private com.example.dvely.project.domain.model.Project boundProject() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        return new com.example.dvely.project.domain.model.Project(
                11L, 7L, "sample",
                com.example.dvely.project.domain.value.ProjectStatus.ACTIVE, "blank", null, "fast",
                com.example.dvely.project.domain.value.DeployStatus.DRAFT,
                null, null, "octo/repo", "octo/repo",
                com.example.dvely.project.domain.value.RepositoryVisibility.PUBLIC,
                com.example.dvely.project.domain.value.RepositoryBindingStatus.BOUND,
                com.example.dvely.project.domain.value.RepositoryHealthStatus.HEALTHY,
                false, now, now
        );
    }

    private com.example.dvely.auth.domain.model.User tokenUser() {
        var user = new com.example.dvely.auth.domain.model.User(
                new com.example.dvely.auth.domain.value.GithubId("1"), "octo", null);
        user.updateUserToken(TOKEN, "refresh", java.time.LocalDateTime.now().plusHours(6));
        return user;
    }

    private void stubBuildDir(String dir) {
        when(dockerService.exec(eq(CONTAINER_ID), anyString())).thenReturn("missing");
        when(dockerService.exec(eq(CONTAINER_ID), contains("[ -d " + dir + " ]"))).thenReturn("exists");
    }
}
