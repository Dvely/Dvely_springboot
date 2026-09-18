package com.example.dvely.project.application.service;

import com.example.dvely.chat.application.command.ChatCommandService;
import com.example.dvely.project.application.command.dto.ProjectDeleteMode;
import com.example.dvely.project.domain.exception.ProjectNotFoundException;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.service.ProjectDomainService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 프로젝트 삭제의 <b>로컬 정리</b>만 한 트랜잭션으로 묶는다.
 *
 * <p>따로 둔 이유는 트랜잭션 경계다(#337). 예전에는 {@code ProjectCommandService.deleteProject}
 * 하나가 GitHub 저장소 삭제(외부 호출)와 이 정리를 같은 트랜잭션에 담고 있어, GitHub 응답을
 * 기다리는 내내 커넥션이 묶였다. 외부 호출을 트랜잭션 밖으로 빼면서도 대화 삭제와 프로젝트
 * 삭제가 <b>함께</b> 커밋되는 성질은 지켜야 했는데 — 둘이 갈라지면 대화만 사라진 프로젝트가
 * 남는다 — 자기 호출로는 프록시가 걸리지 않으므로 별도 빈으로 뺐다. 같은 패키지의
 * {@link RepositoryProvisioningService} 와 같은 결이다.</p>
 */
@Service
@RequiredArgsConstructor
public class ProjectDeletionService {

    private final ProjectRepository projectRepository;
    private final ProjectDomainService projectDomainService;
    private final ChatCommandService chatCommandService;

    @Transactional
    public void purge(Long ownerUserId, Long projectId, ProjectDeleteMode deleteMode) {
        Project project = projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, ownerUserId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId, ownerUserId));

        if (deleteMode == ProjectDeleteMode.PROJECT_AND_REPOSITORY) {
            chatCommandService.deleteConversationsForProject(ownerUserId, projectId);
        } else {
            chatCommandService.trashConversationsForProject(ownerUserId, projectId);
        }
        projectDomainService.delete(project);
        projectRepository.save(project);
    }
}
