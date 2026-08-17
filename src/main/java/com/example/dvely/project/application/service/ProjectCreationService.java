package com.example.dvely.project.application.service;

import com.example.dvely.project.application.command.ProjectCommandService;
import com.example.dvely.project.application.command.dto.CreateProjectCommand;
import com.example.dvely.project.application.result.ProjectCreationResult;
import com.example.dvely.project.application.result.ProjectDetailResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 프로젝트 생성은 프로젝트 행만 만든다.
 *
 * 예전에는 여기서 초기 코드 생성 Agent task 를 함께 제출했는데, 그 태스크는 한 번도 실행된
 * 적이 없다. conversationId 없이 제출돼서 (a) 승인 정책 기본값이 전부 true 라 CODE 스텝에
 * CHANGE 승인이 붙고, (b) AgentMessageService 가 null 대화에서 no-op 이라 "승인 후 실행합니다"
 * 안내가 버려지고, (c) WAITING_APPROVAL 은 워커가 집을 수 없는 상태라, 아무도 볼 수 없는 승인
 * 뒤에서 current_step=0 인 채로 영구히 남았다.
 *
 * 없애도 잃는 기능이 없다. 사용자가 첫 요청을 보내면 CodeAgentService 가 프로젝트 전체를
 * 처음부터 만들기 때문에 스캐폴딩 결과물은 어차피 덮인다.
 *
 * 다만 startMode=template 로 고른 templateType 을 실제로 적용하는 경로가 지금은 없다. 값은
 * 프로젝트 행에 저장되지만 아무도 읽지 않는다 — 스캐폴딩이 실행된 적이 없으므로 이 PR 이전에도
 * 마찬가지였다.
 */
@Service
@RequiredArgsConstructor
public class ProjectCreationService {

    private final ProjectCommandService projectCommandService;

    public ProjectCreationResult create(Long ownerUserId, CreateProjectCommand command) {
        ProjectDetailResult project = projectCommandService.createProject(ownerUserId, command);
        return new ProjectCreationResult(project);
    }
}
