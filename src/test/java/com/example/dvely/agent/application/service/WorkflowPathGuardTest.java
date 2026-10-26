package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** 결과물이 GitHub Actions 를 건드리는지 판정한다(#508). 우회 표기를 모두 같은 경로로 본다. */
class WorkflowPathGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            ".github/workflows/deploy.yml",
            ".github/workflows/nested/ci.yaml",
            ".GITHUB/Workflows/deploy.yml",          // 대소문자
            "./.github/workflows/deploy.yml",         // ./ 접두사
            "././.github//workflows/deploy.yml",      // 겹친 ./ 와 //
            "/.github/workflows/deploy.yml",          // 앞 슬래시
            ".github\\workflows\\deploy.yml",         // 역슬래시
            " .github/workflows/deploy.yml ",         // 앞뒤 공백
            ".github/actions/setup/action.yml",       // workflow 가 부르는 로컬 액션
            ".github",                                // .github 자체를 링크·파일로 바꿔치기
            ".github/"
    })
    void guardedPathsAreCaughtWhateverTheSpelling(String path) {
        assertThat(WorkflowPathGuard.touchesGuardedPath(List.of("src/App.vue", path))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "src/.github/workflows/deploy.yml",       // 저장소 루트가 아니면 Actions 가 읽지 않는다
            "docs/workflows.md",
            ".github-pages/index.html",
            ".github/ISSUE_TEMPLATE/bug.md",
            ""
    })
    void otherPathsAreNotGuarded(String path) {
        assertThat(WorkflowPathGuard.touchesGuardedPath(List.of(path))).isFalse();
    }

    @Test
    void aRenameIsCaughtFromEitherSide() {
        // --no-renames 로 이름 변경이 삭제+추가로 펼쳐진다. 들여오는 쪽도 내보내는 쪽도 잡는다.
        assertThat(WorkflowPathGuard.touchesGuardedPath(List.of("ci/deploy.yml", ".github/workflows/deploy.yml")))
                .isTrue();
        assertThat(WorkflowPathGuard.touchesGuardedPath(List.of(".github/workflows/deploy.yml", "ci/deploy.yml")))
                .isTrue();
    }

    @Test
    void readsNulSeparatedPathsFromTheContainer() {
        DockerContainerService docker = mock(DockerContainerService.class);
        when(docker.execWithExitCode(eq("c-1"), anyString()))
                .thenReturn(new DockerContainerService.ExecResult(0, "src/App.vue\0.github/workflows/x.yml\0"));

        assertThat(new WorkflowPathGuard(docker).touchesGuardedPath("c-1")).isTrue();
        // 이름 변경을 펼치고, 커밋된 변경까지 보도록 기본 브랜치와 비교한다.
        verify(docker).execWithExitCode(eq("c-1"), argThat(command ->
                command.contains("--no-renames") && command.contains("origin/HEAD") && command.contains("-z")));
    }

    @Test
    void anUnrelatedChangeSetPasses() {
        DockerContainerService docker = mock(DockerContainerService.class);
        when(docker.execWithExitCode(eq("c-1"), anyString()))
                .thenReturn(new DockerContainerService.ExecResult(0, "src/App.vue\0package.json\0"));

        assertThat(new WorkflowPathGuard(docker).touchesGuardedPath("c-1")).isFalse();
    }

    @Test
    void failingToLookCountsAsTouching() {
        // 확인을 못 했다는 이유로 승인을 건너뛰지 않는다.
        DockerContainerService docker = mock(DockerContainerService.class);
        when(docker.execWithExitCode(eq("c-1"), anyString()))
                .thenReturn(new DockerContainerService.ExecResult(128, "fatal: not a git repository"));

        assertThat(new WorkflowPathGuard(docker).touchesGuardedPath("c-1")).isTrue();
    }
}
