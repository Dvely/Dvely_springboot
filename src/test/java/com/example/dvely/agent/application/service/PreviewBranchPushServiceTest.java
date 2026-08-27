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
}
