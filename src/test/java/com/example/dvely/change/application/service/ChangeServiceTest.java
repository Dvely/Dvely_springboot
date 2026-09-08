package com.example.dvely.change.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.change.infrastructure.persistence.entity.ChangeEntity;
import com.example.dvely.change.infrastructure.persistence.repository.SpringDataChangeRepository;
import com.example.dvely.preview.application.result.PreviewSessionInfo;
import com.example.dvely.preview.application.service.PreviewSessionService;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChangeServiceTest {

    @Test
    void recordsTaskScopedPreviewDiff() {
        SpringDataChangeRepository repository = mock(SpringDataChangeRepository.class);
        TaskStore taskStore = mock(TaskStore.class);
        PreviewSessionService previewSessionService = mock(PreviewSessionService.class);
        DockerContainerService dockerService = mock(DockerContainerService.class);
        ChangeService service = new ChangeService(
                repository,
                taskStore,
                previewSessionService,
                dockerService,
                mock(ProjectRepository.class)
        );
        when(taskStore.get("task-1")).thenReturn(new AgentTask(
                "task-1",
                1L,
                11L,
                21L,
                TaskStatus.RUNNING,
                null,
                null,
                null,
                null,
                Instant.now()
        ));
        when(previewSessionService.findByTaskId("task-1")).thenReturn(Optional.of(
                new PreviewSessionInfo(
                        "preview-1",
                        1L,
                        11L,
                        21L,
                        "task-1",
                        "container-1",
                        32768,
                        "https://preview.qeploy.test/preview-1/",
                        LocalDateTime.now().plusMinutes(30)
                )
        ));
        when(repository.findByTaskId("task-1")).thenReturn(Optional.empty());
        // 저장소가 이미 있는 프로젝트 — 워크스페이스의 .git 을 그대로 쓴다.
        when(dockerService.exec("container-1", "[ -d /workspace/app/.git ] && echo yes || echo no"))
                .thenReturn("yes");
        when(dockerService.execWithExitCode(
                "container-1",
                "cd /workspace/app && (git add -N . >/dev/null 2>&1 || true) && git diff --no-ext-diff -- ."
        )).thenReturn(new DockerContainerService.ExecResult(0, "diff --git a/src/App.jsx b/src/App.jsx"));

        service.record("task-1", "FAQ 추가");

        ArgumentCaptor<ChangeEntity> captor = ArgumentCaptor.forClass(ChangeEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getTaskId()).isEqualTo("task-1");
        assertThat(captor.getValue().getPreviewSessionId()).isEqualTo("preview-1");
        assertThat(captor.getValue().getDiffText()).contains("src/App.jsx");
    }

    /**
     * 신규 프로젝트의 첫 CODE 스텝에는 컨테이너에 .git 이 없다 — clone 은 저장소가 연결된
     * 프로젝트에만 일어나고 git init 은 그보다 나중인 push 시점에 돈다. 그 상태로 git diff 를
     * 돌리면 git 이 <b>사용법 도움말</b>을 뱉고 129 로 끝나는데, exec 은 종료 코드를 안 보므로
     * 그 도움말이 diff 로 저장됐다. 아무도 덮어쓰지 않아 모든 신규 프로젝트의 첫 변경 내역이
     * 쓰레기였다.
     */
    @Test
    void capturesDiffOutsideTheWorktreeWhenTheWorkspaceHasNoRepositoryYet() {
        Fixture f = fixture();
        when(f.docker.exec("container-1", "[ -d /workspace/app/.git ] && echo yes || echo no"))
                .thenReturn("no");
        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        when(f.docker.execWithExitCode(eq("container-1"), command.capture()))
                .thenReturn(new DockerContainerService.ExecResult(0, "diff --git a/App.jsx b/App.jsx"));

        f.service.record("task-1", "todo 앱 생성");

        // 작업 트리 밖의 일회용 저장소를 쓴다. 워크스페이스에 .git 을 만들면
        // PreviewBranchPushService 가 "이미 저장소가 있다" 로 분기해 push 가 통째로 실패한다.
        assertThat(command.getValue())
                .contains("--git-dir=/tmp/qeploy-diff.git")
                .contains("--work-tree=.")
                .doesNotContain("git init -b preview");

        ArgumentCaptor<ChangeEntity> saved = ArgumentCaptor.forClass(ChangeEntity.class);
        verify(f.repository).save(saved.capture());
        assertThat(saved.getValue().getDiffText()).contains("App.jsx");
    }

    /** 못 떴으면 사유를 남기고 빈 값으로 둔다 — 도움말 텍스트가 diff 인 척 남는 것보다 낫다. */
    @Test
    void storesNothingRatherThanGarbageWhenTheDiffCommandFails() {
        Fixture f = fixture();
        when(f.docker.exec("container-1", "[ -d /workspace/app/.git ] && echo yes || echo no"))
                .thenReturn("no");
        when(f.docker.execWithExitCode(eq("container-1"), anyString()))
                .thenReturn(new DockerContainerService.ExecResult(129, "usage: git diff --no-index ..."));

        f.service.record("task-1", "todo 앱 생성");

        ArgumentCaptor<ChangeEntity> saved = ArgumentCaptor.forClass(ChangeEntity.class);
        verify(f.repository).save(saved.capture());
        assertThat(saved.getValue().getDiffText()).isEmpty();
    }

    /** 컬럼(MEDIUMTEXT) 을 넘기면 저장이 통째로 실패한다 — 자르되 잘렸다는 것을 본문에 남긴다. */
    @Test
    void truncatesAnOversizedDiffInsteadOfFailingTheSave() {
        Fixture f = fixture();
        when(f.docker.exec("container-1", "[ -d /workspace/app/.git ] && echo yes || echo no"))
                .thenReturn("yes");
        when(f.docker.execWithExitCode(eq("container-1"), anyString()))
                .thenReturn(new DockerContainerService.ExecResult(0, "x".repeat(1_200_000)));

        f.service.record("task-1", "큰 변경");

        ArgumentCaptor<ChangeEntity> saved = ArgumentCaptor.forClass(ChangeEntity.class);
        verify(f.repository).save(saved.capture());
        String diff = saved.getValue().getDiffText();
        assertThat(diff).hasSizeLessThan(1_100_000);
        assertThat(diff).endsWith("… (변경 내역이 너무 커서 이후는 생략했습니다)\n");
    }

    private record Fixture(ChangeService service,
                           SpringDataChangeRepository repository,
                           DockerContainerService docker) {}

    private Fixture fixture() {
        SpringDataChangeRepository repository = mock(SpringDataChangeRepository.class);
        TaskStore taskStore = mock(TaskStore.class);
        PreviewSessionService previewSessionService = mock(PreviewSessionService.class);
        DockerContainerService docker = mock(DockerContainerService.class);
        ChangeService service = new ChangeService(
                repository, taskStore, previewSessionService, docker, mock(ProjectRepository.class));
        when(taskStore.get("task-1")).thenReturn(new AgentTask(
                "task-1", 1L, 11L, 21L, TaskStatus.RUNNING, null, null, null, null, Instant.now()));
        when(previewSessionService.findByTaskId("task-1")).thenReturn(Optional.of(
                new PreviewSessionInfo("preview-1", 1L, 11L, 21L, "task-1", "container-1", 32768,
                        "https://preview.qeploy.test/preview-1/", LocalDateTime.now().plusMinutes(30))));
        when(repository.findByTaskId("task-1")).thenReturn(Optional.empty());
        return new Fixture(service, repository, docker);
    }
}
