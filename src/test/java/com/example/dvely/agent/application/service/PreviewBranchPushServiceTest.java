package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService.ExecResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PreviewBranchPushServiceTest {

    private static final String CONTAINER_ID = "container-1";
    private static final String TOKEN = "ghu_supersecrettoken";

    private DockerContainerService dockerService;
    private PreviewBranchPushService service;

    @BeforeEach
    void setUp() {
        dockerService = mock(DockerContainerService.class);
        service = new PreviewBranchPushService(dockerService);
        // .git 이 없는 상태 → init 경로를 탄다.
        lenient().when(dockerService.exec(eq(CONTAINER_ID), anyString())).thenReturn("");
        lenient().when(dockerService.exec(eq(CONTAINER_ID), contains("/.git ]"))).thenReturn("no");
        // 작업물은 /workspace/app 에 있다(정상 상태). 없는 경우는 아래 전용 테스트가 다룬다.
        lenient().when(dockerService.exec(eq(CONTAINER_ID), contains("[ -d /workspace/app ]"))).thenReturn("yes");
        lenient().when(dockerService.execWithExitCode(eq(CONTAINER_ID), anyString()))
                .thenReturn(new ExecResult(0, ""));
    }

    /**
     * 이 테스트가 이 클래스의 존재 이유다.
     *
     * push 가 실패해도 예외가 없으면 호출자는 성공으로 알고 다음으로 간다. 그러면 감사 로그에
     * PREVIEW_BRANCH_PUSHED 가 성공으로 남고, 사용자에게는 "작업물을 preview 브랜치에
     * 올렸습니다 — 프리뷰가 만료돼도 코드는 남습니다"가 표시된다. 실제로는 아무것도 올라가지
     * 않았고, 컨테이너가 만료되면 작업물은 사라진다.
     */
    @Test
    void aFailedPushIsNotReportedAsSuccess() {
        when(dockerService.execWithExitCode(eq(CONTAINER_ID), contains("git push")))
                .thenReturn(new ExecResult(128, "remote: Permission to octo/app.git denied"));

        assertThatThrownBy(() -> push())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("preview 브랜치에 올리지 못했습니다")
                .hasMessageContaining("git push");
    }

    /**
     * 이 클래스는 자격 증명을 다룬다. 실패 메시지가 로그와 사용자 화면으로 흘러가므로 명령 전문을
     * 넣으면 토큰이 함께 샌다.
     */
    @Test
    void theFailureMessageNeverCarriesTheToken() {
        when(dockerService.execWithExitCode(eq(CONTAINER_ID), contains("git push")))
                .thenReturn(new ExecResult(128, "fatal: Authentication failed"));

        assertThatThrownBy(() -> push())
                .isInstanceOf(IllegalStateException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain(TOKEN));
    }

    /** 푸시 전 단계가 깨졌는데 계속 진행하면, 올라가는 내용이 의도와 달라진다. */
    @Test
    void aFailedStageStopsBeforeThePush() {
        when(dockerService.execWithExitCode(eq(CONTAINER_ID), contains("git add")))
                .thenReturn(new ExecResult(1, "fatal: not a git repository"));

        assertThatThrownBy(() -> push())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("git add");

        verify(dockerService, never()).execWithExitCode(eq(CONTAINER_ID), contains("git push"));
    }

    /**
     * 변경이 없으면 git diff --cached --quiet 가 0 으로 끝나 커밋을 건너뛴다. 그 경우까지 실패로
     * 보면 "고칠 것이 없었다"가 오류가 된다.
     */
    @Test
    void anEmptyCommitIsNotAFailure() {
        when(dockerService.execWithExitCode(eq(CONTAINER_ID), contains("git commit")))
                .thenReturn(new ExecResult(0, ""));

        assertThatCode(this::push).doesNotThrowAnyException();
    }

    @Test
    void aSucceededPushGoesThroughEveryStageInOrder() {
        assertThatCode(this::push).doesNotThrowAnyException();

        verify(dockerService).execWithExitCode(eq(CONTAINER_ID), contains("git init -b preview"));
        verify(dockerService).execWithExitCode(eq(CONTAINER_ID), contains("git remote add origin"));
        verify(dockerService).execWithExitCode(eq(CONTAINER_ID), contains("git add -A"));
        verify(dockerService).execWithExitCode(eq(CONTAINER_ID), contains("git push -u origin preview"));
    }

    /**
     * apk 는 이미 git 이 깔려 있거나 이미지가 alpine 이 아닐 수 있다. 그것까지 실패로 보면 멀쩡히
     * 돌던 컨테이너에서 푸시가 막힌다 — 정말 git 이 없으면 뒤의 strict 단계가 드러낸다.
     */
    @Test
    void installingGitIsAllowedToFail() {
        service = new PreviewBranchPushService(dockerService);

        assertThatCode(this::push).doesNotThrowAnyException();

        verify(dockerService).exec(eq(CONTAINER_ID), contains("apk add"));
        verify(dockerService, never()).execWithExitCode(eq(CONTAINER_ID), contains("apk add"));
    }

    private void push() {
        service.push(CONTAINER_ID, TOKEN, "octo", "octo/app", true, "task-1");
    }

    /**
     * 작업물이 /workspace/app 이 아니면, 첫 cd 가 죽으며 "git init 실패"로 보고되던 것을 원인
     * 그대로 말해야 한다. 2026-09-07 dev(project 45)에서 프레임워크 없는 vanilla 요청이 이 상태를
     * 만들었다 — app 디렉터리를 만들어 주는 것이 스캐폴더뿐이라, 스캐폴더가 안 돌면 코드가
     * /workspace 루트에 쌓인다. 프리뷰는 index.html 폴백이 있어 멀쩡히 떠서 여기 와서야 드러났다.
     */
    @Test
    void 작업물이_appDir_밖에_있으면_원인을_말하고_멈춘다() {
        when(dockerService.exec(eq(CONTAINER_ID), contains("[ -d /workspace/app ]"))).thenReturn("no");
        when(dockerService.exec(eq(CONTAINER_ID), contains("ls -A /workspace")))
                .thenReturn("index.html\napp.js\nstyles.css");

        assertThatThrownBy(() -> service.push(CONTAINER_ID, TOKEN, "octo", "octo/repo", true, "task-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/workspace/app 에 없어")
                .hasMessageContaining("index.html");

        // 짐작해서 /workspace 를 올리지 않는다 — 사용자의 저장소에 잘못된 트리가 올라가면
        // 되돌리기가 훨씬 비싸다.
        verify(dockerService, never()).execWithExitCode(eq(CONTAINER_ID), contains("git init"));
        verify(dockerService, never()).execWithExitCode(eq(CONTAINER_ID), contains("git push"));
    }
}
