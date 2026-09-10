package com.example.dvely.change.application.service;

import com.example.dvely.agent.infrastructure.docker.ContainerPaths;
import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.change.application.result.ChangeResult;
import com.example.dvely.change.infrastructure.persistence.entity.ChangeEntity;
import com.example.dvely.change.infrastructure.persistence.repository.SpringDataChangeRepository;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.preview.application.result.PreviewSessionInfo;
import com.example.dvely.preview.application.service.PreviewSessionService;
import com.example.dvely.project.domain.exception.ProjectNotFoundException;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChangeService {

    private final SpringDataChangeRepository changeRepository;
    private final TaskStore taskStore;
    private final PreviewSessionService previewSessionService;
    private final DockerContainerService dockerService;
    private final ProjectRepository projectRepository;

    @Transactional
    public void record(String taskId, String summary) {
        AgentTask task = taskStore.get(taskId);
        PreviewSessionInfo preview = previewSessionService.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalStateException("Change에 연결할 PreviewSession이 없습니다."));
        String diff = captureDiff(preview.containerId());
        ChangeEntity change = changeRepository.findByTaskId(taskId)
                .orElseGet(() -> new ChangeEntity(
                        task.ownerUserId(),
                        task.projectId(),
                        task.conversationId(),
                        task.taskId(),
                        preview.sessionId(),
                        summary,
                        diff
                ));
        change.update(summary, diff);
        changeRepository.save(change);
    }

    /** 저장할 diff 의 상한. 넘으면 잘라서 표시한다 — 컬럼이 MEDIUMTEXT(16MB) 라 넘기면 저장이 통째로 실패한다. */
    private static final int MAX_DIFF_CHARS = 1_000_000;

    /** 작업 트리 밖에 두는 일회용 git 디렉터리. 워크스페이스에 .git 을 만들지 않기 위한 것이다. */
    private static final String SCRATCH_GIT_DIR = "/tmp/qeploy-diff.git";

    /**
     * 컨테이너의 작업물에서 변경 내역을 뜬다.
     *
     * <p><b>저장소가 아직 없을 때가 문제였다.</b> 신규 프로젝트의 첫 CODE 스텝에는 컨테이너에
     * {@code .git} 이 없다 — clone 은 저장소가 연결된 프로젝트에만 일어나고({@code
     * PreviewWorkspaceService#prepareProject} 가 조기 반환한다), {@code git init} 은 그보다 나중인
     * push 시점에 한 번 돈다. 그런데 이 메서드는 그 앞에서 불린다.</p>
     *
     * <p>그 상태로 {@code git diff} 를 돌리면 git 이 <b>사용법 도움말</b>을 뱉고 129 로 끝나는데,
     * {@code exec} 은 종료 코드를 보지 않으므로 그 도움말 텍스트가 그대로 diff 로 저장됐다. 이후
     * 아무도 덮어쓰지 않아 영구히 남는다 — 즉 <b>모든 신규 프로젝트의 첫 변경 내역이 쓰레기였다.</b></p>
     *
     * <p>그래서 저장소가 없으면 작업 트리 <b>밖</b>({@link #SCRATCH_GIT_DIR})에 일회용 저장소를 만들어
     * 거기서 뜬다. 워크스페이스에 {@code .git} 을 만들지 않는 것이 핵심이다 — 만들면
     * {@code PreviewBranchPushService} 가 "이미 저장소가 있다" 로 분기해 remote 도 없는 상태에서
     * {@code git remote set-url} 을 돌리다 push 가 통째로 실패한다.</p>
     *
     * <p>{@code execWithExitCode} 를 쓰는 것도 같은 이유다. 이번 버그가 조용했던 진짜 원인은
     * 종료 코드를 아무도 안 봤다는 것이다.</p>
     */
    private String captureDiff(String containerId) {
        boolean hasRepository = "yes".equals(dockerService.exec(
                containerId,
                "[ -d " + ContainerPaths.APP_DIR + "/.git ] && echo yes || echo no").trim());

        String gitPrefix = hasRepository
                ? "git "
                : "git --git-dir=" + SCRATCH_GIT_DIR + " --work-tree=. ";
        String prepare = hasRepository
                ? ""
                : "rm -rf " + SCRATCH_GIT_DIR + " && " + gitPrefix + "init -q && ";

        // git 은 이미지(node:20-alpine)에 없다. PreviewBranchPushService 가 깔긴 하지만 그건 결과
        // 승인 이후라 여기보다 한참 뒤다 — 그래서 이 자리에서는 언제나 exit=127 이었고, 저장소가
        // 없는 프로젝트의 diff 는 늘 빈 값이었다(2026-09-10 dev, project 52 에서 확인).
        // 실패를 허용하는 형태는 같은 저장소의 다른 세 곳과 맞춘다 — 이미 깔려 있거나 이미지가
        // alpine 이 아닐 수 있고, 그때는 뒤의 git 이 알아서 동작한다.
        DockerContainerService.ExecResult result = dockerService.execWithExitCode(
                containerId,
                ContainerPaths.inApp("(apk add --no-cache git >/dev/null 2>&1 || true) && "
                        + prepare
                        + "(" + gitPrefix + "add -N . >/dev/null 2>&1 || true) && "
                        + gitPrefix + "diff --no-ext-diff -- ."));

        if (!result.succeeded()) {
            // 변경 내역은 부가 정보라, 못 떴다고 CODE 스텝을 실패시키지는 않는다. 다만 조용히
            // 넘어가면 이번 버그가 반복되므로 사유를 남기고 빈 값으로 저장한다 — 도움말 텍스트가
            // diff 인 척 남는 것보다 낫다.
            log.warn("[Change] 변경 내역을 뜨지 못했습니다(빈 값으로 저장). exitCode={} hasRepository={} output={}",
                    result.exitCode(), hasRepository, truncate(result.output(), 500));
            return "";
        }
        return truncate(result.output(), MAX_DIFF_CHARS);
    }

    /** 상한을 넘으면 잘라내되, 잘렸다는 사실을 본문에 남긴다 — 조용히 사라지지 않게. */
    private static String truncate(String text, int limit) {
        if (text == null || text.length() <= limit) {
            return text == null ? "" : text;
        }
        return text.substring(0, limit) + "\n… (변경 내역이 너무 커서 이후는 생략했습니다)\n";
    }

    @Transactional
    public void markDeployed(String taskId) {
        changeRepository.findByTaskId(taskId).ifPresent(change -> {
            change.markDeployed();
            changeRepository.save(change);
        });
    }

    @Transactional(readOnly = true)
    public List<ChangeResult> getProjectChanges(Long ownerUserId, Long projectId) {
        projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, ownerUserId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId, ownerUserId));
        return changeRepository.findByProjectIdAndOwnerUserIdOrderByCreatedAtDesc(projectId, ownerUserId)
                .stream()
                .map(ChangeEntity::toResult)
                .toList();
    }

    @Transactional(readOnly = true)
    public ChangeResult getChange(Long ownerUserId, Long changeId) {
        return findOwned(ownerUserId, changeId).toResult();
    }

    @Transactional(readOnly = true)
    public String getDiff(Long ownerUserId, Long changeId) {
        return findOwned(ownerUserId, changeId).getDiffText();
    }

    private ChangeEntity findOwned(Long ownerUserId, Long changeId) {
        return changeRepository.findByIdAndOwnerUserId(changeId, ownerUserId)
                .orElseThrow(() -> new NotFoundException("Change를 찾을 수 없습니다. changeId=" + changeId));
    }
}
