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
     * 옛 serve 를 실제로 죽이는지.
     *
     * 패턴이 'npx serve' 였을 때는 아무것도 잡지 못했다. 컨테이너 안 프로세스의 cmdline 은
     * "npm exec serve -s ..." 와 "node .../serve -s ..." 라 리터럴 'npx serve' 가 없고, 대신
     * pkill 을 실행하는 sh -c 자신의 cmdline 에는 그 문자열이 있어 자기 자신을 죽였다.
     * 그래서 옛 serve 가 포트 3000 을 계속 쥐고, 새 serve 는 랜덤 포트로 옮겨 붙어
     * 게이트웨이에는 낡은 디렉터리가 응답했다(2026-08-25 컨테이너 실측).
     */
    @Test
    void theOldServeIsKilledWithAPatternThatActuallyMatchesIt() {
        when(dockerService.exec(eq(CONTAINER_ID), anyString())).thenReturn("");
        // 빌드 산출물 디렉터리 감지가 첫 후보에서 걸리게 한다.
        when(dockerService.exec(eq(CONTAINER_ID), contains("exists"))).thenReturn("exists");
        when(dockerService.exec(eq(CONTAINER_ID), contains("ready="))).thenReturn("serve_ready=yes");

        service.startPreviewServer(CONTAINER_ID);

        ArgumentCaptor<String> commands = ArgumentCaptor.forClass(String.class);
        verify(dockerService, org.mockito.Mockito.atLeastOnce())
                .exec(eq(CONTAINER_ID), commands.capture());
        String pkill = commands.getAllValues().stream()
                .filter(c -> c.startsWith("pkill"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("pkill 명령이 없다"));

        // 문자열 모양이 아니라 성질을 본다. pkill -f 는 패턴을 정규식으로 cmdline 전체에
        // 맞춰보므로, 두 가지가 동시에 성립해야 한다.
        String pattern = pkill.replaceAll("^pkill -f '(.*?)'.*$", "$1");
        java.util.regex.Pattern regex = java.util.regex.Pattern.compile(pattern);

        // 1) 컨테이너에서 실제로 도는 프로세스를 잡아야 한다(2026-08-25 실측 cmdline).
        assertThat(regex.matcher("npm exec serve -s /workspace/app/dist -l 3000").find()).isTrue();
        assertThat(regex.matcher(
                "node /root/.npm/_npx/aab42732f01924e5/node_modules/.bin/serve -s /workspace/app/dist -l 3000")
                .find()).isTrue();

        // 2) 자기 자신은 잡으면 안 된다. 이 pkill 을 실행하는 sh -c 의 cmdline 이 곧 이 명령
        //    문자열이고, 예전 패턴('npx serve')은 여기에 걸려 스스로에게 SIGTERM 을 보냈다.
        assertThat(regex.matcher(pkill).find()).isFalse();
    }

    /**
     * 빌드 실패가 호출자에게 도달하는지.
     *
     * exec 이 종료 코드를 읽지 않던 동안에는 set -o pipefail 도 의미가 없었다. 빌드가 깨져도
     * 성공으로 반환됐고, 컨테이너를 재사용하는 프리뷰에서는 이전 빌드의 dist 가 남아 있어
     * detectBuildOutputDir 이 그것을 잡았다 — 실패한 빌드가 옛 화면으로 성공처럼 보였다.
     */
    @Test
    void aFailedBuildReachesTheCaller() {
        when(dockerService.exec(eq(CONTAINER_ID), contains("package.json"))).thenReturn("yes");
        when(dockerService.execWithExitCode(eq(CONTAINER_ID), contains("npm run build")))
                .thenReturn(new DockerContainerService.ExecResult(1, "build failed"));
        when(dockerService.exec(eq(CONTAINER_ID), contains("tail"))).thenReturn("error TS2304");

        assertThatThrownBy(() -> service.buildIfConfigured(CONTAINER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("프리뷰 빌드에 실패했습니다");
    }

    @Test
    void aSucceededBuildJustReturns() {
        when(dockerService.exec(eq(CONTAINER_ID), contains("package.json"))).thenReturn("yes");
        when(dockerService.execWithExitCode(eq(CONTAINER_ID), contains("npm run build")))
                .thenReturn(new DockerContainerService.ExecResult(0, "built"));

        service.buildIfConfigured(CONTAINER_ID);

        verify(dockerService).execWithExitCode(eq(CONTAINER_ID), contains("npm run build"));
    }

    @Test
    void aProjectWithoutABuildScriptIsServedWithoutBuilding() {
        when(dockerService.exec(eq(CONTAINER_ID), contains("package.json"))).thenReturn("no");

        service.buildIfConfigured(CONTAINER_ID);

        verify(dockerService, org.mockito.Mockito.never())
                .execWithExitCode(eq(CONTAINER_ID), contains("npm run build"));
    }

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
                .isInstanceOf(PreviewServeException.class)
                .hasMessageContaining("제한 시간");
    }

    @Test
    void throwsWhenTheProbeProducesNoOutput() {
        // exec 이 빈 문자열이나 null 을 돌려주는 경우에도 성공으로 넘어가면 안 된다.
        stubBuildDir("/workspace/app/dist");
        when(dockerService.exec(eq(CONTAINER_ID), contains("serve_ready"))).thenReturn("");

        assertThatThrownBy(() -> service.startPreviewServer(CONTAINER_ID))
                .isInstanceOf(PreviewServeException.class);
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
