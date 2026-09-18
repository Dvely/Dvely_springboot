package com.example.dvely.agent.infrastructure.codingagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.port.out.CodingAgentResult;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.docker.ContainerPaths;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.aiaccount.application.service.CodingAgentExecutionService;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 프리뷰 컨테이너와 코딩 에이전트의 워크스페이스 모델을 잇는 다리를 지킨다.
 *
 * <p>둘은 서로의 파일을 볼 수 없다 — 프리뷰 컨테이너에는 바인드 마운트가 없어 에이전트에게 줄
 * 호스트 경로가 아예 없고, 에이전트의 컨테이너는 프리뷰가 뜰 때쯤이면 사라진다. 그래서 프로젝트를
 * 꺼냈다가 되돌려 넣는데, 그 과정에서 조용히 틀릴 수 있는 것들을 여기서 못박는다.</p>
 */
class CodingAgentWorkspaceBridgeTest {

    private DockerContainerService dockerService;
    private CodingAgentExecutionService executionService;
    private CodingAgentWorkspaceBridge bridge;

    @BeforeEach
    void setUp() {
        dockerService = mock(DockerContainerService.class);
        executionService = mock(CodingAgentExecutionService.class);
        bridge = new CodingAgentWorkspaceBridge(dockerService, executionService);

        when(dockerService.copyDirectoryFromContainer(anyString(), anyString(), any(), any()))
                .thenReturn(3L);
        when(dockerService.exec(anyString(), anyString())).thenReturn("no");
    }

    private CodingAgentResult runWith(CodingAgentResult result) {
        when(executionService.run(any(), any(), anyString(), anyString())).thenReturn(result);
        return result;
    }

    @Test
    void handsTheAgentAHostPathAndReturnsItsClosingText() {
        runWith(CodingAgentResult.succeeded("할 일 목록을 추가했습니다.", ""));

        String summary = bridge.run("container-1", 7L, AiProvider.CODEX, "todo 앱 만들어줘");

        assertThat(summary).isEqualTo("할 일 목록을 추가했습니다.");
        ArgumentCaptor<String> workspace = ArgumentCaptor.forClass(String.class);
        verify(executionService).run(eq(7L), eq(AiProvider.CODEX), anyString(), workspace.capture());
        // 에이전트 컨테이너가 이 디렉터리를 /workspace 로 마운트하므로, 프로젝트는 그쪽에서도
        // /workspace/app 이 된다 — 파이프라인의 나머지가 쓰는 경로와 같다.
        assertThat(workspace.getValue()).isNotBlank();
    }

    @Test
    void tellsTheAgentWhereTheProjectLives() {
        runWith(CodingAgentResult.succeeded("done", ""));

        bridge.run("container-1", 7L, AiProvider.CODEX, "버튼 색을 바꿔줘");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(executionService).run(any(), any(), prompt.capture(), anyString());
        // 자유 형식 프롬프트라, 파이프라인이 의존하는 약속은 여기 적지 않으면 성립하지 않는다.
        assertThat(prompt.getValue()).contains(ContainerPaths.APP_DIR);
        assertThat(prompt.getValue()).contains("버튼 색을 바꿔줘");
        // 서버를 띄우면 프리뷰 실행기와 충돌한다.
        assertThat(prompt.getValue()).contains("Do NOT start a preview");
    }

    @Test
    void failureLeavesTheContainerUntouched() {
        runWith(CodingAgentResult.failed("", "boom", 1));

        assertThatThrownBy(() -> bridge.run("container-1", 7L, AiProvider.CODEX, "x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("반영하지 않았습니다");

        // 절반만 적용된 변경을 되돌려 넣으면 프리뷰가 그걸 빌드해 결과인 것처럼 보여준다.
        verify(dockerService, never()).copyDirectoryToContainer(anyString(), any(), anyString());
    }

    @Test
    void aTimeoutSaysSoRatherThanReportingAGenericFailure() {
        // 시간이 모자랐을 뿐일 수 있어서 "한도를 올리거나 요청을 좁혀라" 가 쓸모 있는 조언이 된다.
        runWith(CodingAgentResult.timedOut("", ""));

        assertThatThrownBy(() -> bridge.run("container-1", 7L, AiProvider.CODEX, "x"))
                .hasMessageContaining("제한 시간");
    }

    @Test
    void clearsTheContainerSideBeforeCopyingBackButKeepsNodeModules() {
        runWith(CodingAgentResult.succeeded("done", ""));

        bridge.run("container-1", 7L, AiProvider.CODEX, "x");

        ArgumentCaptor<String> commands = ArgumentCaptor.forClass(String.class);
        verify(dockerService, org.mockito.Mockito.atLeastOnce())
                .exec(eq("container-1"), commands.capture());
        String clear = commands.getAllValues().stream()
                .filter(c -> c.contains("-mindepth 1"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("컨테이너 쪽을 비우는 명령이 없다"));
        // Docker 의 copy 는 덮어쓸 뿐 지우지 않는다. 비우지 않으면 에이전트가 지운 파일이 살아남아
        // 프로젝트가 "지운 줄 아는 코드" 와 함께 빌드된다.
        assertThat(clear).contains(ContainerPaths.APP_DIR);
        // 유일하게 꺼내오지 않은 것이므로, 유일하게 지우면 안 되는 것이기도 하다.
        assertThat(clear).contains("-not -name node_modules");
    }

    @Test
    void reinstallsDependenciesWhenTheProjectHasAPackageJson() {
        runWith(CodingAgentResult.succeeded("done", ""));
        when(dockerService.exec(anyString(), org.mockito.ArgumentMatchers.contains("package.json")))
                .thenReturn("yes");

        bridge.run("container-1", 7L, AiProvider.CODEX, "x");

        // 에이전트는 자기 컨테이너에 설치했고 그 컨테이너는 사라졌다. 새 의존성이 package.json 에
        // 추가됐다면 이걸 안 돌리면 빌드가 없는 import 에서 죽는다.
        verify(dockerService).exec("container-1",
                "cd " + ContainerPaths.APP_DIR + " && npm install");
    }

    @Test
    void skipsTheInstallWhenThereIsNoPackageJson() {
        runWith(CodingAgentResult.succeeded("done", ""));
        // 기본 스텁이 "no" 를 준다 — 순수 HTML 프로젝트가 그렇다.

        bridge.run("container-1", 7L, AiProvider.CODEX, "x");

        verify(dockerService, never()).exec(anyString(),
                eq("cd " + ContainerPaths.APP_DIR + " && npm install"));
    }

    @Test
    void carriesNeitherNodeModulesNorGit() {
        runWith(CodingAgentResult.succeeded("done", ""));

        bridge.run("container-1", 7L, AiProvider.CODEX, "x");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> skipped = ArgumentCaptor.forClass(Set.class);
        verify(dockerService).copyDirectoryFromContainer(
                eq("container-1"), eq(ContainerPaths.APP_DIR), any(Path.class), skipped.capture());
        // 둘 다 반대편에서 다시 만들 수 있고, 둘 다 나르면 복사가 실행 전체를 지배한다.
        assertThat(skipped.getValue()).containsExactlyInAnyOrder("node_modules", ".git");
    }
}
