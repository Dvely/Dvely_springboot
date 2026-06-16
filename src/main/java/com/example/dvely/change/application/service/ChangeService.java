package com.example.dvely.change.application.service;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        String diff = dockerService.exec(
                preview.containerId(),
                "cd /workspace/app && "
                        + "(git add -N . >/dev/null 2>&1 || true) && "
                        + "git diff --no-ext-diff -- ."
        );
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
