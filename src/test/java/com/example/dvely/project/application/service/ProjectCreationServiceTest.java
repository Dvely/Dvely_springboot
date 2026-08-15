package com.example.dvely.project.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.dvely.project.application.command.ProjectCommandService;
import com.example.dvely.project.application.command.dto.CreateProjectCommand;
import com.example.dvely.project.application.result.ProjectCreationResult;
import com.example.dvely.project.application.result.ProjectDetailResult;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 프로젝트 생성은 프로젝트 행만 만든다. 예전에는 초기 코드 생성 태스크를 함께 제출했는데,
 * conversationId 없이 제출돼 아무도 볼 수 없는 승인 뒤에 갇힌 채 한 번도 실행되지 않았다.
 */
@ExtendWith(MockitoExtension.class)
class ProjectCreationServiceTest {

    @Mock
    private ProjectCommandService projectCommandService;

    private ProjectCreationService service;

    @BeforeEach
    void setUp() {
        service = new ProjectCreationService(projectCommandService);
    }

    @Test
    void createsTheProjectAndNothingElse() {
        CreateProjectCommand command = new CreateProjectCommand("shop", "template", "e-commerce", "quality");
        ProjectDetailResult project = project("shop", "template", "e-commerce", "quality");
        when(projectCommandService.createProject(1L, command)).thenReturn(project);

        ProjectCreationResult result = service.create(1L, command);

        assertThat(result.project()).isEqualTo(project);
    }

    @Test
    void blankStartModeAlsoSubmitsNoTask() {
        // 생성 시점에 에이전트를 부르지 않는다는 것이 이 클래스의 계약 전부다. 협력자가
        // ProjectCommandService 하나뿐이라는 사실 자체가 그 계약을 강제한다 — 오케스트레이터를
        // 다시 주입하려면 생성자를 고쳐야 하고, 그러면 이 테스트가 컴파일되지 않는다.
        CreateProjectCommand command = new CreateProjectCommand("starter", "blank", null, "fast");
        ProjectDetailResult project = project("starter", "blank", null, "fast");
        when(projectCommandService.createProject(1L, command)).thenReturn(project);

        assertThat(service.create(1L, command).project().startMode()).isEqualTo("blank");
    }

    private ProjectDetailResult project(String name,
                                        String startMode,
                                        String templateType,
                                        String draftMode) {
        return new ProjectDetailResult(
                11L,
                name,
                "DRAFT",
                startMode,
                templateType,
                draftMode,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }
}
