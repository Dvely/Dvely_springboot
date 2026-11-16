package com.example.dvely.project.application.service;

import com.example.dvely.chat.application.command.ChatCommandService;
import com.example.dvely.cloudresource.application.LiveResource;
import com.example.dvely.cloudresource.infrastructure.LiveResourceQuery;
import com.example.dvely.project.application.command.dto.ProjectDeleteMode;
import com.example.dvely.project.domain.exception.ProjectNotFoundException;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.service.ProjectDomainService;
import java.util.List;
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
 *
 * <p><b>사용자 AWS 에 살아 있는 자원이 있으면 지우지 않는다(#595).</b> 프로젝트가 화면에서 사라지면 그 프로젝트로 만든 서버·DB 를
 * 볼 수도 끌 수도 없는데 과금은 계속된다. 그래서 {@link ProjectDeletionBlockedException} 으로 막고 목록을 돌려준다 — 함께
 * 종료하지 않는 것은 되돌릴 수 없는 삭제를 한 번에 묶지 않으려는 것이다. 판정은 회원 탈퇴와 같은 {@link LiveResourceQuery} 다.</p>
 *
 * <p>잠그지 않는다. 판정과 소프트 삭제 사이에 같은 프로젝트의 서버 생성이 승인되는 좁은 틈은 남는다 — 그 서버는 탈퇴 판정
 * ({@code GET /users/me/withdrawal})이 삭제한 프로젝트의 것까지 보여 주고, 서버 종료는 프로젝트 삭제 여부를 보지 않아 끌 수
 * 있다.</p>
 */
@Service
@RequiredArgsConstructor
public class ProjectDeletionService {

    private final ProjectRepository projectRepository;
    private final ProjectDomainService projectDomainService;
    private final ChatCommandService chatCommandService;
    private final LiveResourceQuery liveResources;

    /**
     * 이 프로젝트를 지우면 주인을 잃을, 사용자 AWS 에 살아 있는 자원. 비어 있으면 지울 수 있다. 삭제 화면 안내와, 되돌릴 수 없는
     * GitHub 저장소 삭제 전 확인에 쓴다.
     *
     * @throws ProjectNotFoundException 내 프로젝트가 아니거나 이미 삭제했다
     */
    @Transactional(readOnly = true)
    public List<LiveResource> findLiveResources(Long ownerUserId, Long projectId) {
        projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, ownerUserId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId, ownerUserId));
        return liveResources.ofProject(projectId);
    }

    @Transactional
    public void purge(Long ownerUserId, Long projectId, ProjectDeleteMode deleteMode) {
        Project project = projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, ownerUserId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId, ownerUserId));
        // 저장소 삭제 전에 이미 한 번 봤지만, 그 사이에 생긴 것을 놓치지 않게 지우기 직전에 다시 본다.
        List<LiveResource> live = liveResources.ofProject(projectId);
        if (!live.isEmpty()) {
            throw new ProjectDeletionBlockedException(live);
        }

        if (deleteMode == ProjectDeleteMode.PROJECT_AND_REPOSITORY) {
            chatCommandService.deleteConversationsForProject(ownerUserId, projectId);
        } else {
            chatCommandService.trashConversationsForProject(ownerUserId, projectId);
        }
        projectDomainService.delete(project);
        projectRepository.save(project);
    }
}
