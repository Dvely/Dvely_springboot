package com.example.dvely.project.application.service;

import com.example.dvely.audit.application.AuditEvent;
import com.example.dvely.audit.application.AuditRecorder;
import com.example.dvely.audit.domain.value.AuditAction;
import com.example.dvely.audit.domain.value.AuditActorType;
import com.example.dvely.audit.domain.value.AuditOutcome;
import com.example.dvely.project.application.port.out.GithubRepositoryPort;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 저장소를 프로젝트에 연결하는 마지막 공통 단계.
 *
 * 저장소를 만들지 기존 것을 쓸지 정하는 규칙은 호출부마다 다르지만(설정 화면은 이름이 겹치면
 * 거부하고, 저장소 연결 승인은 그냥 재사용한다), 이름이 정해진 뒤의 순서는 어디서나 같다.
 * preview 브랜치 준비 → 바인딩 → 헬스 갱신 → 저장 → 감사 기록.
 *
 * 이 순서를 한 곳에 모아둔 이유는 preparePreviewBranch 를 빠뜨리기 쉬워서다. 이걸 건너뛰면
 * 저장소의 기본 브랜치와 preview 가 공통 조상 없이 갈라진다. 그러면 나중에 preview 를 기본
 * 브랜치로 병합할 때 GitHub 비교 API 가 404 를 주고, 그 404 는 "비교할 게 없다"로 해석되어
 * 병합이 조용히 생략된다. 실패가 성공으로 기록되는 셈이라 발견하기 어렵다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepositoryProvisioningService {

    private final GithubRepositoryPort githubRepositoryPort;
    private final ProjectRepository projectRepository;
    private final AuditRecorder auditRecorder;

    /**
     * @param created   저장소를 방금 새로 만들었으면 true. 감사 기록의 액션만 가른다.
     * @param taskId    Agent task 안에서 일어난 연결이면 그 taskId, 사용자가 직접 연결했으면 null.
     * @param metadata  감사 기록에 남길 부가 정보. 없으면 null.
     */
    public Project bindToProject(Project project,
                                 Long ownerUserId,
                                 String repositoryFullName,
                                 RepositoryVisibility visibility,
                                 boolean created,
                                 AuditActorType actor,
                                 String taskId,
                                 String metadata) {
        // 기본 브랜치 HEAD 에서 preview 를 만들어 둔다. 이미 있으면 아무것도 하지 않는다.
        githubRepositoryPort.preparePreviewBranch(ownerUserId, repositoryFullName);

        project.bindRepository(repositoryFullName, visibility);
        project.updateRepositoryHealth(RepositoryHealthStatus.HEALTHY);
        Project saved = projectRepository.save(project);

        // 외부 작업이 끝나고 바인딩이 저장된 뒤에 기록한다.
        auditRecorder.record(new AuditEvent(
                created ? AuditAction.REPOSITORY_CREATED : AuditAction.REPOSITORY_CONNECTED,
                AuditOutcome.SUCCEEDED,
                actor,
                ownerUserId,
                saved.getId(),
                "REPOSITORY",
                repositoryFullName,
                taskId,
                null,
                metadata,
                null
        ));
        log.info("[RepositoryProvisioning] 저장소 연결 완료 | projectId={} repo={} created={}",
                saved.getId(), repositoryFullName, created);
        return saved;
    }
}
